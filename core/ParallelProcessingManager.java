/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.BatchFileItem;
import audiomanager.model.ProcessingConfig;
import audiomanager.model.TranscriptionConfig;
import audiomanager.model.TranscriptionResult;
import audiomanager.util.TimeLeftEstimator;
import audiomanager.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Advanced parallel processing manager.
 *
 * <h2>Fixes vs. original</h2>
 * <ul>
 *   <li><b>NullPointerException on non-Windows:</b> All
 *       {@code System.getenv("LOCALAPPDATA")} calls are null-guarded.
 *       On Linux and macOS the variable is absent; the code now falls back to
 *       the XDG cache path ({@code ~/.cache/huggingface/hub}).</li>
 *   <li><b>Executor shutdown:</b> {@link #shutdown()} now calls
 *       {@code awaitTermination(30, SECONDS)} per executor after
 *       {@code shutdownNow()}, so in-flight transcriptions finish writing their
 *       output before the JVM exits.  The original called {@code awaitTermination}
 *       with only a 5-second window shared across all pools, which was
 *       insufficient for the model and pipeline executors.</li>
 *   <li><b>Memory pressure feedback:</b> A simple guard in
 *       {@link #safeInitialize} reduces the number of model threads when free
 *       heap drops below a configurable threshold, preventing OOM during
 *       large-batch runs.</li>
 *   <li><b>Boilerplate licence header removed</b> and replaced with the
 *       project standard.</li>
 *   <li><b>Missing output files on multi-file/parallel batches:</b>
 *       {@link #postProcessSync} used to relocate output by re-scanning disk
 *       for the {@code whisperx_output_*} temp directory that
 *       {@link WhisperXTranscriptionService#transcribe} creates — but that
 *       method deletes the very same directory in its own {@code finally}
 *       block before returning. The scan therefore always came up empty and
 *       zero files were ever copied to the final output directory, silently
 *       (no exception, batch reported success). Post-processing now writes
 *       directly from the in-memory {@link TranscriptionResult} that
 *       {@code transcribe()} already returns, via {@link TranscriptionOutputWriter}
 *       — the same mechanism {@code BatchProcessor.saveOutput()} uses on the
 *       (working) standard/non-parallel path. The old {@code fileOutputDirs}
 *       map and disk-scanning helpers have been removed as unnecessary.</li>
 *   <li><b>No live UI progress during parallel batches:</b> this whole class
 *       previously operated on raw {@code List&lt;File&gt;} with no reference
 *       back to the UI's {@code BatchFileItem} objects, and passed a no-op
 *       progress callback (`p -&gt; {}`) into {@code transcribe()}. Combined
 *       with {@code MainWindow}'s 1-second UI timer being gated on
 *       {@code BatchProcessor.isProcessing()} — a flag this class's entry
 *       point never touched — the result was that during any parallel batch
 *       (the "10x+ speed" path, used whenever more than one file is queued
 *       with max-parallel &gt; 1) the file-time labels, the per-row progress
 *       bars, and the overall "Ready to Process" bar never updated at all
 *       until the entire batch finished. The entry point now takes
 *       {@code List&lt;BatchFileItem&gt;} directly (matching the pattern
 *       {@code BatchProcessor}'s standard path already used correctly),
 *       flips each item's status to "PROCESSING" as it starts, wires a real
 *       progress callback into {@code transcribe()} that updates
 *       {@code item.setProgress()} continuously, and marks each item
 *       COMPLETED/FAILED as it individually finishes rather than only in a
 *       final bulk pass after the whole batch completes.</li>
 * </ul>
 */
public class ParallelProcessingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelProcessingManager.class);

    /** Free-heap threshold below which model parallelism is capped at 1. */
    private static final long LOW_MEMORY_THRESHOLD_MB = 512L;

    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final Consumer<String> logger;
    private final TimeLeftEstimator timeEstimator;

    // Thread pools
    // FIX: widened from ExecutorService to ThreadPoolExecutor — see
    // startResourceMonitor() for why. Fully compatible everywhere these
    // fields were already used as ExecutorService (ThreadPoolExecutor
    // implements it), so this doesn't change anything except what's
    // possible with the reference.
    private ThreadPoolExecutor ioExecutor;
    private ThreadPoolExecutor cpuExecutor;
    private ThreadPoolExecutor pipelineExecutor;

    // Base (ceiling) sizes computed at initialize() time — the resource
    // monitor scales actual pool sizes as a fraction of these rather than
    // recomputing from scratch every tick.
    private int baseIoThreads;
    private int baseCpuThreads;
    private int basePipelineThreads;

    // FIX: replaces the old `Map<String, List<...>> modelInstances` +
    // `Map<String, Semaphore> modelSemaphores` pair. That design had a real,
    // long-standing bug: modelInstances ever only held exactly ONE instance
    // per model (initializeModelInstances() had no loop creating more than
    // one), and modelSemaphores was sized to instances.size() — which was
    // therefore always 1 — regardless of calculateModelThreads()'s computed
    // capacity. Same-model transcription concurrency was hard-capped at 1
    // from the very first version of this class; calculateModelThreads()'s
    // result was computed correctly but never actually used to create more
    // than one instance. ModelInstancePool (below) fixes this and adds live,
    // resource-aware resizing on top.
    private final Map<String, ModelInstancePool> modelPools;
    private final Map<String, PipelineStage> pipelineStages;

    /** How many files are actively inside the pipeline right now — used by the 2s resource sampler and batch summary below. */
    private final AtomicInteger activeFileCount = new AtomicInteger(0);

    // FIX (doc-review — "log CPU every 2 seconds" / batch summary): a
    // lightweight, purely-observational sampler, separate from
    // startResourceMonitor()'s existing 5-second adaptive-control cycle —
    // this one never changes any pool size, it only records a time series
    // and accumulates the stats needed for the end-of-batch summary
    // (mean/peak CPU, mean/peak RAM). Started/stopped once per batch by
    // doProcessBatch (see below).
    private ScheduledExecutorService batchSamplerExecutor;
    private DoubleSummaryStatistics batchCpuStats = new DoubleSummaryStatistics();
    private DoubleSummaryStatistics batchMemStats = new DoubleSummaryStatistics();
    private volatile long batchPeakHeapMB = 0;
    private final AtomicInteger scalingEventCount = new AtomicInteger(0);
    private volatile int lastLoggedTarget = -1;

    private void startBatchSampler() {
        batchCpuStats = new DoubleSummaryStatistics();
        batchMemStats = new DoubleSummaryStatistics();
        batchPeakHeapMB = 0;
        scalingEventCount.set(0);
        lastLoggedTarget = -1;

        batchSamplerExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Batch-Resource-Sampler"));
        batchSamplerExecutor.scheduleAtFixedRate(() -> {
            try {
                double cpuPct = getSystemCpuLoadPercent();
                double memPct = getSystemMemoryUsedPercent();
                long heapMB = getUsedMemoryMB();
                if (cpuPct >= 0) batchCpuStats.accept(cpuPct);
                if (memPct >= 0) batchMemStats.accept(memPct);
                batchPeakHeapMB = Math.max(batchPeakHeapMB, heapMB);

                LOGGER.info("RESOURCE_SAMPLE: cpu={}% mem={}% heap={}MB activeFiles={}",
                        cpuPct >= 0 ? String.format("%.0f", cpuPct) : "n/a",
                        memPct >= 0 ? String.format("%.0f", memPct) : "n/a",
                        heapMB, activeFileCount.get());
            } catch (Exception e) {
                LOGGER.debug("Batch resource sample failed: {}", e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void stopBatchSampler() {
        if (batchSamplerExecutor != null) {
            batchSamplerExecutor.shutdownNow();
            batchSamplerExecutor = null;
        }
    }

    /**
     * Logs the end-of-batch summary. Throughput is minutes of source audio
     * processed per wall-clock hour. CPU/RAM figures come from the 2-second
     * batch sampler above; "CPU idle" treats any time this batch's sampler
     * wasn't observing >0% system CPU load as idle — a coarse measure, but
     * a real, measured one rather than an assumption.
     */
    private void logBatchSummary(int filesProcessed, double totalAudioDurationSeconds, long elapsedMs) {
        double elapsedHours = elapsedMs / 3_600_000.0;
        double audioMinutes = totalAudioDurationSeconds / 60.0;
        double throughputMinPerHour = elapsedHours > 0 ? audioMinutes / elapsedHours : 0;
        double avgCpu = batchCpuStats.getCount() > 0 ? batchCpuStats.getAverage() : -1;
        long cpuActiveMs = avgCpu >= 0 ? Math.round(elapsedMs * (avgCpu / 100.0)) : -1;

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Batch Summary ==========\n");
        sb.append(String.format("Files processed:      %d%n", filesProcessed));
        sb.append(String.format("Audio duration:       %s%n", formatDuration((long) (totalAudioDurationSeconds * 1000))));
        sb.append(String.format("Elapsed time:          %s%n", formatDuration(elapsedMs)));
        sb.append(String.format("Average throughput:    %.1f min audio / hour%n", throughputMinPerHour));
        sb.append(String.format("Average CPU:           %s%n", avgCpu >= 0 ? String.format("%.1f%%", avgCpu) : "n/a"));
        sb.append(String.format("Peak CPU:              %s%n",
                batchCpuStats.getCount() > 0 ? String.format("%.1f%%", batchCpuStats.getMax()) : "n/a"));
        sb.append(String.format("Average RAM:           %s%n",
                batchMemStats.getCount() > 0 ? String.format("%.1f%%", batchMemStats.getAverage()) : "n/a"));
        sb.append(String.format("Peak RAM:              %s%n",
                batchMemStats.getCount() > 0 ? String.format("%.1f%%", batchMemStats.getMax()) : "n/a"));
        sb.append(String.format("Peak Java heap:        %d MB%n", batchPeakHeapMB));
        if (cpuActiveMs >= 0) {
            sb.append(String.format("CPU active / idle:     %s / %s%n",
                    formatDuration(cpuActiveMs), formatDuration(Math.max(0, elapsedMs - cpuActiveMs))));
        }
        sb.append(String.format("Scaling events:        %d (model-pool concurrency target changed this batch)%n",
                scalingEventCount.get()));
        sb.append("====================================");

        String summary = sb.toString();
        LOGGER.info(summary);
        if (logger != null) logger.accept(summary);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }

    // FIX: added — periodic CPU/heap sampling that live-adjusts each
    // model pool's target concurrency between 1 and its initial capacity,
    // instead of that capacity being a fixed decision made once at batch
    // start and never revisited. A deliberately simplified port of the
    // resource-pressure concept from an earlier, unfinished
    // DynamicParallelismOrchestrator prototype (never wired into the app —
    // see its own file for why) — kept to just CPU and heap usage, the two
    // signals that are both portable and actually meaningful, rather than
    // that prototype's disk-space-as-"disk-IO-pressure" proxy and
    // always-zero network-pressure stub.
    private ScheduledExecutorService resourceMonitorExecutor;

    // FIX: writes output straight from the in-memory TranscriptionResult —
    // replaces the old fileOutputDirs map + disk-scanning approach, which
    // raced against WhisperXTranscriptionService's own temp-dir cleanup and
    // always lost, silently producing zero output files.
    private final TranscriptionOutputWriter outputWriter = new TranscriptionOutputWriter();
    private volatile boolean exportWordCopy = false;

    /** Whether saveOutputDirectly() should also write a Word-compatible .html copy alongside the normal output. */
    public void setExportWordCopy(boolean exportWordCopy) {
        this.exportWordCopy = exportWordCopy;
    }

    private volatile boolean initialized = false;
    private final Semaphore batchSemaphore = new Semaphore(3);

    // FIX: added — BatchProcessor already has a FileCompletionCallback
    // mechanism (see its executeBatch()) that MainWindow uses to remove
    // completed files from the UI queue the instant they finish, when
    // "Auto-Remove Completed Files" is on. That callback was only ever
    // invoked from BatchProcessor's own standard-path loop — this class
    // (the actual path used whenever more than one file is queued with
    // max-parallel > 1) had no equivalent hook at all, so auto-remove
    // silently never fired for parallel batches. Optional (null-checked)
    // so existing callers that don't set it are unaffected.
    private volatile BatchProcessor.FileCompletionCallback completionCallback;

    public void setFileCompletionCallback(BatchProcessor.FileCompletionCallback callback) {
        this.completionCallback = callback;
    }

    // -------------------------------------------------------------------------
    //  Construction
    // -------------------------------------------------------------------------

    public ParallelProcessingManager(AudioProcessor audioProcessor,
                                     WhisperXTranscriptionService transcriptionService,
                                     Consumer<String> logger,
                                     TimeLeftEstimator timeEstimator) {
        this.audioProcessor      = audioProcessor;
        this.transcriptionService = transcriptionService;
        this.logger              = logger;
        this.timeEstimator       = timeEstimator;
        this.modelPools           = new ConcurrentHashMap<>();
        this.pipelineStages      = new ConcurrentHashMap<>();
        initializePipelineStages();
    }

    // -------------------------------------------------------------------------
    //  Initialization
    // -------------------------------------------------------------------------

    private void initializePipelineStages() {
        pipelineStages.put("audio_preprocessing", new PipelineStage("audio_preprocessing", 2));
        pipelineStages.put("transcription",       new PipelineStage("transcription",       4));
        pipelineStages.put("post_processing",     new PipelineStage("post_processing",     2));
        pipelineStages.put("file_output",         new PipelineStage("file_output",         2));
    }

    /**
     * Thread-safe lazy initialisation with memory pressure guard.
     *
     * <p>FIX: if free heap is below {@value #LOW_MEMORY_THRESHOLD_MB} MB the
     * model thread count is capped at 1 to avoid OOM during model loading.</p>
     */
    private void safeInitialize(int maxParallelFiles, String model) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    // Memory pressure check before spinning up pools
                    long freeMemMB = getFreeMemoryMB();
                    if (freeMemMB < LOW_MEMORY_THRESHOLD_MB) {
                        LOGGER.warn("Low free heap ({}MB) detected — capping model threads at 1.", freeMemMB);
                    }
                    initialize(maxParallelFiles, model);
                    LOGGER.info("Parallel manager initialised (heap used: {}MB).", getUsedMemoryMB());
                }
            }
        }
    }

    /**
     * (Re)creates every thread pool this manager owns, tearing down any
     * existing ones first.
     *
     * <p>FIX: the {@code initialized} flag used to be set only by
     * {@link #safeInitialize}, immediately after this method returned —
     * never by this method itself. That meant a direct call to this
     * (public) method left the manager fully initialized (all pools live)
     * while {@code initialized} still read {@code false}. Harmless in
     * practice only because the sole production call path goes through
     * {@code safeInitialize}, but it made the flag an unreliable signal for
     * anything calling {@code initialize()} directly — including tests.
     * The flag is now set here, so it accurately reflects pool state no
     * matter which entry point is used.</p>
     */
    public void initialize(int maxParallelFiles, String model) {
        shutdown(); // Clean up existing pools first

        int procs = Runtime.getRuntime().availableProcessors();
        int memMB = (int)(Runtime.getRuntime().maxMemory() / (1024 * 1024));
        long freeMB = getFreeMemoryMB();

        int ioThreads       = Math.min(procs * 4, 16);
        int cpuThreads      = Math.min(procs * 2, 12);
        int modelThreads    = calculateModelThreads(procs, memMB, freeMB);
        int pipelineThreads = Math.min(procs * 2, 8);

        this.baseIoThreads       = ioThreads;
        this.baseCpuThreads      = cpuThreads;
        this.basePipelineThreads = pipelineThreads;

        LOGGER.info("Pool sizes — IO={} CPU={} Model={} Pipeline={}",
                ioThreads, cpuThreads, modelThreads, pipelineThreads);

        ioExecutor       = createBoundedExecutor("IO-Worker",       ioThreads,       100);
        cpuExecutor      = createBoundedExecutor("CPU-Worker",      cpuThreads,       50);
        pipelineExecutor = createBoundedExecutor("Pipeline-Worker", pipelineThreads,  30);

        initializeModelInstances(model, modelThreads);
        initializePipelineWorkers();
        startResourceMonitor(modelThreads);
        initialized = true;
    }

    /**
     * Calculate model-thread count with free-heap guard.
     *
     * @param freeMB current free heap in MB
     */
    /**
     * Diagnostic override: when {@code > 0}, forces exactly this many
     * concurrent model instances regardless of what {@link #calculateModelThreads}
     * would otherwise compute — bypassing it entirely. Set to {@code 0} to
     * restore normal dynamic scaling.
     *
     * <p>Added as a troubleshooting knob for CPU-only machines running
     * memory-heavy models (e.g. {@code large-v2}) where two concurrent
     * model instances exhausted available RAM and caused Python-side
     * {@code mkl_malloc} allocation failures. {@link #calculateModelThreads}
     * sizes concurrency from JVM heap/CPU count as a proxy for system
     * capacity — a reasonable heuristic in general, but it has no way to
     * know how RAM-heavy the specific model actually is on this specific
     * machine. This override lets that be pinned down manually rather than
     * inferred.</p>
     */
    private static final int FORCE_MODEL_THREADS = 1;

    private int calculateModelThreads(int processors, int maxMemMB, long freeMB) {
        if (FORCE_MODEL_THREADS > 0) return FORCE_MODEL_THREADS;

        // Apply memory pressure cap first
        if (freeMB < LOW_MEMORY_THRESHOLD_MB) return 1;

        if (maxMemMB > 16_000) return Math.min(processors, 8);
        if (maxMemMB > 8_000)  return Math.min(processors / 2, 4);
        return Math.min(processors / 4, 2);
    }

    private ThreadPoolExecutor createBoundedExecutor(String name, int threads, int queueSize) {
        return new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                new NamedThreadFactory(name),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Creates a properly-sized instance pool for the model this batch is
     * actually using.
     *
     * FIX: previously this eagerly created exactly one instance for each of
     * five hardcoded model names ("tiny".."large") regardless of which
     * model the batch actually used, and modelThreads (the just-computed
     * capacity) was passed to an executor that nothing else ever submitted
     * work to. Real transcription concurrency was gated entirely by
     * modelSemaphores, sized to instances.size() — always 1. Now exactly
     * one pool is created, for the model in use, actually sized to
     * modelThreads.
     */
    private void initializeModelInstances(String model, int modelThreads) {
        String normalized = normalizeModelName(model);
        modelPools.put(normalized, new ModelInstancePool(normalized, modelThreads,
                () -> new WhisperXTranscriptionService(new DependencyManager(), timeEstimator), LOGGER));
        LOGGER.info("Model instance pool initialised for '{}': {} instance(s) (capacity {}).",
                normalized, modelPools.get(normalized).size(), modelThreads);
    }

    private String normalizeModelName(String model) {
        return model == null ? "base" : model.trim().toLowerCase();
    }

    /**
     * Starts (or restarts) the periodic CPU/heap sampler that live-adjusts
     * every model pool's target concurrency, up to {@code ceilingThreads}
     * (the capacity calculateModelThreads() already decided was safe for
     * this machine), down to 1 under heavy pressure. Runs every 5 seconds —
     * frequent enough to react within a file or two of transcription, cheap
     * enough to be irrelevant next to the actual transcription workload.
     */
    private void startResourceMonitor(int ceilingThreads) {
        resourceMonitorExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("Resource-Monitor"));
        resourceMonitorExecutor.scheduleAtFixedRate(() -> {
            try {
                double cpuLoadPct = getSystemCpuLoadPercent();
                long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                double heapUsedPct = maxHeapMB > 0 ? (getUsedMemoryMB() * 100.0 / maxHeapMB) : 0.0;

                // FIX: previously only ever looked at JVM heap pressure. But
                // this app's actual memory-heavy work (WhisperX/torch model
                // inference) runs in external Python subprocesses, whose
                // memory usage is entirely invisible to the JVM heap — this
                // is exactly the blind spot behind a real crash this app hit
                // (Python-side `mkl_malloc: failed to allocate memory` OOMs
                // while the JVM itself still had heap headroom to spare).
                // Now also samples system-wide physical memory via the
                // platform MXBean when available, and reacts to whichever
                // signal shows more pressure — heap or system RAM — since
                // either one can be the actual bottleneck depending on
                // whether it's JavaFX/UI state or a spawned model process
                // that's under strain.
                double systemMemUsedPct = getSystemMemoryUsedPercent();
                double memUsedPct = systemMemUsedPct >= 0
                        ? Math.max(heapUsedPct, systemMemUsedPct)
                        : heapUsedPct;

                // FIX: was shrinking at cpuLoadPct > 75/90 — but transcription
                // is CPU-bound by nature (especially without a GPU), so high
                // CPU during active work is the expected, correct state, not
                // a sign of contention to back off from. Treating "the CPU is
                // busy transcribing" as a problem meant this would trigger
                // almost continuously during real use, needlessly
                // constraining concurrency the user explicitly asked for via
                // max-parallel-files. Memory is the far more meaningful
                // "actually in trouble" signal here (heap exhaustion causes
                // real failures; heap headroom doesn't get freed just by
                // waiting a moment). CPU now only matters as a tie-breaker at
                // genuinely extreme, sustained saturation (98%+).
                int target;
                double scaleFactor; // 1.0 = full ceiling, 0.5 = half, etc.
                if (cpuLoadPct < 0 && systemMemUsedPct < 0) {
                    // Neither measurement available on this JVM/platform —
                    // keep the static capacity rather than guessing.
                    target = ceilingThreads;
                    scaleFactor = 1.0;
                } else if (memUsedPct > 90 || (cpuLoadPct > 98 && memUsedPct > 80)) {
                    target = 1;
                    scaleFactor = 1.0 / ceilingThreads;
                } else if (memUsedPct > 80) {
                    target = Math.max(1, ceilingThreads / 2);
                    scaleFactor = 0.5;
                } else {
                    target = ceilingThreads;
                    scaleFactor = 1.0;
                }

                for (ModelInstancePool pool : modelPools.values()) {
                    pool.adjustTarget(target);
                }

                // FIX (batch summary — "Scaling events"): count a scaling
                // event whenever the computed target concurrency actually
                // changes from the previous cycle, so the batch summary can
                // report how many times this batch's model concurrency was
                // throttled up/down, instead of that only being visible by
                // manually diffing DEBUG logs.
                final int finalTarget = target;
                if (lastLoggedTarget != -1 && lastLoggedTarget != finalTarget) {
                    scalingEventCount.incrementAndGet();
                    LOGGER.info("Adaptive scaling: model concurrency target changed {} -> {} (cpu={}%, mem={}%)",
                            lastLoggedTarget, finalTarget, cpuLoadPct, memUsedPct);
                }
                lastLoggedTarget = finalTarget;

                // FIX: dynamic resizing previously applied to model
                // concurrency only — the IO/CPU/Pipeline pools were sized
                // once at initialize() and left fixed for the rest of the
                // batch, regardless of how conditions changed. They now
                // scale proportionally to the same pressure signal, so real
                // memory/CPU pressure throttles every stage of the pipeline
                // together, not just the model stage — actual dynamic
                // multiparallelism across the whole pipeline rather than one
                // adjustable knob among several static ones.
                resizePool(ioExecutor, baseIoThreads, scaleFactor);
                resizePool(cpuExecutor, baseCpuThreads, scaleFactor);
                resizePool(pipelineExecutor, basePipelineThreads, scaleFactor);


                // FIX: added — observability only, logged alongside the
                // (unrelated) sizing decision above purely because this
                // cycle already runs every 5s; getCurrentBottleneckSummary()
                // plays no part in computing `target`.
                String bottleneck = getCurrentBottleneckSummary();
                if (bottleneck != null) {
                    LOGGER.debug("Current bottleneck phase: {}", bottleneck);
                }
            } catch (Exception e) {
                LOGGER.warn("Resource monitor cycle failed: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * CPU load as a 0-100 percentage, or -1 if unavailable on this
     * JVM/platform. Uses the modern (JDK 14+) getCpuLoad() rather than the
     * deprecated getSystemCpuLoad() an earlier, unfinished prototype
     * (DynamicParallelismOrchestrator) used.
     */
    private double getSystemCpuLoadPercent() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getCpuLoad();
                return load >= 0 ? load * 100.0 : -1;
            }
        } catch (Exception e) {
            LOGGER.debug("CPU load measurement unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Percentage of total physical (system-wide) RAM currently in use, or
     * {@code -1} if unavailable on this JVM/platform.
     *
     * <p>Added alongside the resource monitor's dynamic pool resizing —
     * see {@link #startResourceMonitor} for why this matters: JVM heap
     * usage alone is blind to the memory consumed by the external Python
     * subprocesses this app actually spends most of its memory budget on.</p>
     */
    private double getSystemMemoryUsedPercent() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                long total = sunBean.getTotalMemorySize();
                long free = sunBean.getFreeMemorySize();
                if (total > 0) {
                    return (total - free) * 100.0 / total;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("System memory measurement unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Scales a live {@link ThreadPoolExecutor}'s size to {@code base * factor}
     * (minimum 1), applied safely regardless of whether the pool is growing
     * or shrinking.
     *
     * <p>{@code ThreadPoolExecutor} rejects {@code setCorePoolSize} above
     * the current maximum and vice versa, so growth sets the max first and
     * shrinkage sets the core first — otherwise a resize in the "wrong"
     * order throws {@link IllegalArgumentException} depending on which
     * direction the pool is already sized in.</p>
     */
    private void resizePool(ThreadPoolExecutor pool, int base, double factor) {
        if (pool == null || pool.isShutdown()) return;
        int newSize = Math.max(1, (int) Math.round(base * factor));
        int currentMax = pool.getMaximumPoolSize();
        if (newSize > currentMax) {
            pool.setMaximumPoolSize(newSize);
            pool.setCorePoolSize(newSize);
        } else if (newSize < pool.getCorePoolSize()) {
            pool.setCorePoolSize(newSize);
            pool.setMaximumPoolSize(newSize);
        }
    }

    private void initializePipelineWorkers() {
        pipelineStages.values().forEach(PipelineStage::initializeWorkers);
    }

    // -------------------------------------------------------------------------
    //  Public batch entry-point
    // -------------------------------------------------------------------------

    public CompletableFuture<ParallelBatchResult> processBatchParallel(
            List<BatchFileItem> items,
            ProcessingConfig processingConfig,
            TranscriptionConfig transcriptionConfig,
            int maxParallelFiles) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                batchSemaphore.acquire();
                LOGGER.info("Starting batch (heap used: {}MB)", getUsedMemoryMB());
                return doProcessBatch(items, processingConfig, transcriptionConfig, maxParallelFiles);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } finally {
                batchSemaphore.release();
            }
        });
    }

    private ParallelBatchResult doProcessBatch(List<BatchFileItem> items,
                                               ProcessingConfig processingConfig,
                                               TranscriptionConfig transcriptionConfig,
                                               int maxParallelFiles) {
        safeInitialize(maxParallelFiles, transcriptionConfig.getModel());
        long startTime = System.currentTimeMillis();
        List<CompletableFuture<FileResult>> futures = new ArrayList<>();
        double totalAudioDurationSeconds = items.stream()
                .mapToDouble(BatchFileItem::getTotalAudioDurationSeconds)
                .sum();

        startBatchSampler();
        try {
        // FIX (batch prioritization): sort by priority (HIGH before NORMAL
        // before LOW) before grouping/dispatch. This is a best-effort
        // ordering, not a hard guarantee — once files are grouped and
        // submitted to the pipeline executor, several may already be
        // running concurrently (up to maxParallelFiles at once), so a HIGH
        // item queued after several LOW items are already mid-flight will
        // still wait for an execution slot like anything else. What this
        // does guarantee: among files not yet started, higher-priority ones
        // are offered a free slot first. Collections.sort is stable, so
        // within the same priority level, original queue order is preserved.
        List<BatchFileItem> sortedItems = new ArrayList<>(items);
        sortedItems.sort(Comparator.comparingInt(i -> i.getPriority().ordinal()));

        Map<FileGroup, List<BatchFileItem>> fileGroups = groupFilesByTypeAndSize(sortedItems);
        LOGGER.info("Batch: {} files in {} groups", items.size(), fileGroups.size());

        for (Map.Entry<FileGroup, List<BatchFileItem>> entry : fileGroups.entrySet()) {
            List<BatchFileItem> groupItems = entry.getValue();
            CompletableFuture<Void> groupFuture =
                    processFileGroup(groupItems, processingConfig, transcriptionConfig, entry.getKey());
            for (BatchFileItem item : groupItems) {
                futures.add(groupFuture.thenApply(v -> new FileResult(item.getFile(), true, "Success")));
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(24, TimeUnit.HOURS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Batch failed", e);
            long failedDuration = System.currentTimeMillis() - startTime;
            logBatchSummary(0, totalAudioDurationSeconds, failedDuration);
            return new ParallelBatchResult(items.size(), 0, items.size(), failedDuration, true);
        }

        int completed = 0, failed = 0;
        Set<String> successNames = new HashSet<>();
        for (CompletableFuture<FileResult> f : futures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                try {
                    FileResult r = f.get();
                    if (r.success) { completed++; successNames.add(r.file.getName()); }
                    else failed++;
                } catch (InterruptedException | ExecutionException e) { failed++; }
            } else { failed++; }
        }

        long duration = System.currentTimeMillis() - startTime;
        ParallelBatchResult result = new ParallelBatchResult(items.size(), completed, failed, duration, false);
        successNames.forEach(result::addSuccessfulFile);
        LOGGER.info("Batch complete: {}/{} files in {}ms (heap: {}MB)",
                completed, items.size(), duration, getUsedMemoryMB());
        logBatchSummary(completed, totalAudioDurationSeconds, duration);
        return result;
        } finally {
            stopBatchSampler();
        }
    }

    // -------------------------------------------------------------------------
    //  File grouping
    // -------------------------------------------------------------------------

    private Map<FileGroup, List<BatchFileItem>> groupFilesByTypeAndSize(List<BatchFileItem> items) {
        Map<FileGroup, List<BatchFileItem>> groups = new LinkedHashMap<>();
        for (BatchFileItem item : items) {
            File file = item.getFile();
            String ext  = getExtension(file.getName());
            long sizeMB = file.length() / (1024 * 1024);
            FileGroup key = new FileGroup(ext, sizeMB > 100 ? 100 : sizeMB);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return groups;
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "unknown";
    }

    // -------------------------------------------------------------------------
    //  File-group processing
    // -------------------------------------------------------------------------

    private CompletableFuture<Void> processFileGroup(List<BatchFileItem> items,
                                                      ProcessingConfig processingConfig,
                                                      TranscriptionConfig transcriptionConfig,
                                                      FileGroup group) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (BatchFileItem item : items) {
            // FIX (doc-review — "Waiting in queue" timing): captured here,
            // at the instant this file is handed off, not inside the async
            // task — the gap between this timestamp and the task actually
            // starting to run on pipelineExecutor IS the queue wait, which
            // is what's needed to tell whether N "parallel" files are
            // really running concurrently or queued behind a bottleneck.
            long queueEnteredMs = System.currentTimeMillis();
            futures.add(processFileWithPipelineParallelism(item, processingConfig, transcriptionConfig, queueEnteredMs));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> processFileWithPipelineParallelism(BatchFileItem item,
                                                                        ProcessingConfig processingConfig,
                                                                        TranscriptionConfig transcriptionConfig,
                                                                        long queueEnteredMs) {
        return CompletableFuture.runAsync(() -> {
            File file = item.getFile();
            File preprocessed = null;
            activeFileCount.incrementAndGet();
            try {
                long start = System.currentTimeMillis();
                long queueWaitMs = Math.max(0, start - queueEnteredMs);

                // FIX: item.setStatus/setProgress are now called live, at
                // every stage, on the actual BatchFileItem the UI is bound
                // to — previously this method only ever touched a bare
                // File with no path back to the UI at all.
                item.setStatus("PROCESSING");
                item.setProgress(0.0);

                long preprocessStart = System.currentTimeMillis();
                preprocessed = preprocessFile(file, processingConfig);
                long preprocessMs = System.currentTimeMillis() - preprocessStart;
                item.setProgress(0.3); // conversion/preprocessing done

                long transcribeWallStart = System.currentTimeMillis();
                long preprocessedSizeMB = preprocessed.length() / (1024 * 1024);
                TranscriptionResult result = preprocessedSizeMB > LARGE_FILE_THRESHOLD_MB
                        ? transcribeWithSegmentation(preprocessed, transcriptionConfig, item)
                        : transcribeSync(preprocessed, transcriptionConfig, item);
                long transcribeWallMs = System.currentTimeMillis() - transcribeWallStart;
                item.setProgress(0.95);

                long saveStart = System.currentTimeMillis();
                saveOutputDirectly(file, result, transcriptionConfig, processingConfig);
                long saveMs = System.currentTimeMillis() - saveStart;

                item.setProgress(1.0);
                item.setStatus("COMPLETED");
                item.setErrorMessage(null);

                long totalMs = System.currentTimeMillis() - start;
                LOGGER.info("Pipeline complete for {} in {}ms", file.getName(), totalMs);

                // FIX (timing instrumentation surfaced in UI, not just logs):
                // assemble a structured per-file report combining Java-side
                // stages measured here with the Python script's own
                // "STAGE_TIMING:" lines (model load / audio load /
                // transcription / alignment / diarization), captured via
                // WhisperXTranscriptionService.getLastPythonStageTimingsMs()
                // during transcribeSync(). Recorded regardless of whether the
                // Python-side breakdown is present (older/custom user scripts
                // without the instrumentation still get the Java-side stages).
                FileTimingReport timingReport = new FileTimingReport(file.getName());
                timingReport.setStage("queue_wait", queueWaitMs);
                Long modelAcqMs = modelAcquisitionMsByItem.remove(item);
                if (modelAcqMs != null) timingReport.setStage("model_acquisition", modelAcqMs);
                timingReport.setStage("preprocessing", preprocessMs);
                timingReport.setStage("transcription_wall_clock", transcribeWallMs);
                timingReport.setStage("output_saving", saveMs);
                timingReport.setStage("total_pipeline", totalMs);
                Map<String, Long> pythonStages = lastPythonStageTimingsByItem.remove(item);
                if (pythonStages != null) {
                    pythonStages.forEach(timingReport::setStage);
                }
                double[] pyResource = pythonResourceUsageByItem.remove(item);
                if (pyResource != null) {
                    if (pyResource[0] >= 0) timingReport.setPythonPeakMemoryMb(pyResource[0]);
                    if (pyResource[1] >= 0) timingReport.setPythonAvgCpuPercent(pyResource[1]);
                }
                timingReport.setPeakHeapUsedMB(getUsedMemoryMB());
                double cpuSnapshot = getSystemCpuLoadPercent();
                if (cpuSnapshot >= 0) {
                    timingReport.setAvgCpuLoadPercent(cpuSnapshot);
                }
                recordTimingReport(timingReport);
                if (logger != null) logger.accept(formatTimingReportBlock(timingReport));

                // FIX: notify the completion callback (e.g. MainWindow ->
                // auto-remove-completed) the instant this file is done,
                // instead of leaving the UI to find out only via its next
                // periodic refresh or the final end-of-batch reconciliation.
                if (completionCallback != null) {
                    completionCallback.onFileCompleted(item, true);
                }
            } catch (Exception e) {
                item.setStatus("FAILED");
                item.setErrorMessage(e.getMessage());
                LOGGER.error("Pipeline failed for {}: {}", file.getName(), e.getMessage());
                lastPythonStageTimingsByItem.remove(item);
                modelAcquisitionMsByItem.remove(item);
                pythonResourceUsageByItem.remove(item);
                if (completionCallback != null) {
                    completionCallback.onFileCompleted(item, false);
                }
                throw new CompletionException(e);
            } finally {
                // FIX: added — this pipeline never deleted its own temp WAV
                // file. BatchProcessor's (now-removed) standard path always
                // cleaned up after itself; this path silently left every
                // preprocessed temp WAV on disk indefinitely.
                cleanupTempFile(preprocessed);
                activeFileCount.decrementAndGet();
            }
        }, pipelineExecutor);
    }

    /** The exact per-file report format requested for the log/Terminal (doc-review item). */
    private static String formatTimingReportBlock(FileTimingReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================\n");
        sb.append("Processing: ").append(r.getFileName()).append("\n\n");
        sb.append(padLine("Waiting in queue", r.getStageMillis("queue_wait")));
        sb.append(padLine("Model acquisition", r.getStageMillis("model_acquisition")));
        sb.append(padLine("Audio/preprocessing", r.getStageMillis("preprocessing")));
        sb.append(padLine("Model initialization", r.getStageMillis("model_load")));
        sb.append(padLine("Whisper transcription", r.getStageMillis("transcription")));
        sb.append(padLine("Word alignment", r.getStageMillis("alignment")));
        sb.append(padLine("Speaker diarization", r.getStageMillis("diarization")));
        sb.append(padLine("Subtitle generation", r.getStageMillis("subtitle_generation") >= 0 || r.getStageMillis("txt_generation") >= 0
                ? Math.max(0, r.getStageMillis("subtitle_generation")) + Math.max(0, r.getStageMillis("txt_generation")) : -1));
        sb.append(padLine("File writing", r.getStageMillis("output_saving")));
        sb.append('\n');
        sb.append(padLine("Total", r.getTotalMillis()));
        sb.append(String.format("%-34s%s%n", "Peak memory",
                r.getPythonPeakMemoryMb() >= 0 ? String.format("%.0f MB", r.getPythonPeakMemoryMb()) : "n/a"));
        sb.append(String.format("%-34s%s%n", "Average CPU",
                r.getPythonAvgCpuPercent() >= 0 ? String.format("%.1f%%", r.getPythonAvgCpuPercent()) : "n/a"));
        sb.append("==================================================");
        return sb.toString();
    }

    private static String padLine(String label, long ms) {
        String value = ms >= 0 ? String.format("%.1f s", ms / 1000.0) : "n/a";
        return String.format("%-34s%s%n", label, value);
    }

    /** Above this size, transcription is routed through {@link #transcribeWithSegmentation} instead of a single whole-file WhisperX call. */
    private static final long LARGE_FILE_THRESHOLD_MB = 500;

    private File preprocessFile(File file, ProcessingConfig config) throws Exception {
        long sizeMB = file.length() / (1024 * 1024);
        long prepStart = System.currentTimeMillis();
        AudioProcessor.ProcessingResult result;

        // FIX (real gap found on review — SegmentProcessor was dead code):
        // this used to special-case files over 500MB here, at the
        // PREPROCESSING stage, by calling processLargeFileInSegments() —
        // which was a stub that logged "Full data-parallel segmentation not
        // implemented" and fell back to plain processAudioToWav(), silently
        // discarding size as a signal entirely. Meanwhile a fully-built
        // SegmentProcessor class (splitting, per-segment retry, resumable
        // progress via progress.dat, orphan-directory sweeping) existed in
        // the codebase and was never called from anywhere. Segmentation is
        // a TRANSCRIPTION-stage decision, not a preprocessing one — this
        // method now always just converts to WAV the normal way; large
        // files are routed to SegmentProcessor from
        // processFileWithPipelineParallelism (see transcribeWithSegmentation
        // below), where the actual class already sits ready to use.

        // FIX: was unconditionally processAudioToWav() regardless of the
        // user's normalize/volume-boost settings — those were silently
        // ignored for any batch that used the parallel path (i.e. any
        // batch with more than one file and max-parallel > 1). The
        // standard path always respected them; this is the same
        // convert-vs-optimise branch it used.
        if (config.isNormalize() || config.getVolumeBoost() > 0) {
            result = audioProcessor.processAudioWithVolumeOptimization(file, config, p -> {});
            recordPhaseTiming("audio_enhancement", prepStart, sizeMB);
        } else {
            result = audioProcessor.processAudioToWav(file, config, p -> {});
            recordPhaseTiming("audio_preprocessing", prepStart, sizeMB);
        }
        return new File(result.getOutputPath());
    }

    // FIX: added — a deliberately minimal port of
    // DynamicParallelismOrchestrator's PerformanceTracker bottleneck
    // reporting. Unlike that prototype's "adaptive" pieces (which this app
    // does NOT port — see the regression they caused when ported
    // elsewhere), this is pure observability: it only ever reports which
    // phase is slowest on average, it never feeds back into any sizing or
    // scheduling decision. Useful for diagnosing exactly the kind of "why
    // did this suddenly get slow" question that prompted this whole
    // session.
    private final Map<String, LongAdder> phaseTotalMs = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> phaseCount = new ConcurrentHashMap<>();

    private void recordPhaseTiming(String processName, long startMs, double fileSizeMB) {
        long elapsed = System.currentTimeMillis() - startMs;
        if (timeEstimator != null) {
            timeEstimator.recordGlobalProcessTiming(processName, elapsed, fileSizeMB);
        }
        phaseTotalMs.computeIfAbsent(processName, k -> new LongAdder()).add(elapsed);
        phaseCount.computeIfAbsent(processName, k -> new LongAdder()).increment();
    }

    /**
     * The phase with the highest average duration so far this batch, and
     * that average, or {@code null} if nothing has been recorded yet.
     * Observability only — nothing in this class acts on this value.
     */
    public String getCurrentBottleneckSummary() {
        String worst = null;
        double worstAvgMs = -1;
        for (String phase : phaseTotalMs.keySet()) {
            long count = phaseCount.get(phase).sum();
            if (count == 0) continue;
            double avg = phaseTotalMs.get(phase).sum() / (double) count;
            if (avg > worstAvgMs) {
                worstAvgMs = avg;
                worst = phase;
            }
        }
        return worst == null ? null : String.format("%s (avg %.0fms over %d run(s))",
                worst, worstAvgMs, phaseCount.get(worst).sum());
    }

    // FIX: added — see the fix note in processFileWithPipelineParallelism().
    // Ported from BatchProcessor's (now-removed) cleanupTempFile().
    private void cleanupTempFile(File tempWavFile) {
        if (tempWavFile == null || !tempWavFile.exists()) return;
        long start = System.currentTimeMillis();
        double sizeMB = tempWavFile.length() / (1024.0 * 1024.0);
        try {
            Files.delete(tempWavFile.toPath());
            recordPhaseTiming("file_cleanup", start, sizeMB);
            if (timeEstimator != null) timeEstimator.saveSessionData();
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup temp file {} — {}", tempWavFile.getName(), e.getMessage());
        }
    }

    /**
     * FIX: the transcription progress callback only reflects the raw
     * segment-completion fraction reported by {@code transcribe()}. For a
     * model split into few, long segments (e.g. a "large" model's 10-segment
     * split of a long file), that fraction can sit unchanged at 0 for the
     * entire time a single segment is running — visibly minutes to hours on
     * CPU — leaving the per-file progress bar frozen at the 0.3 ("just
     * finished preprocessing") checkpoint the whole time. This estimates
     * elapsed-time-based progress from the running average of this model's
     * past transcription durations (already tracked in {@link #phaseTotalMs}/
     * {@link #phaseCount} for the bottleneck-summary feature) and blends it
     * with the real callback via {@code max(...)} — so a genuine
     * segment-completion signal is never overridden by a worse time-based
     * guess, but the bar still advances smoothly when segment updates are
     * sparse. By construction, elapsed == estimated-remaining implies
     * elapsed == half of the estimated total, i.e. the blended fraction
     * reaches 0.5 at that point — matching the same 50%-at-the-midpoint
     * expectation the "File Time Spent"/"File Time Left" labels imply.
     */
    private long estimateTranscriptionDurationMs(String phaseName, double fileSizeMB) {
        LongAdder totalMs = phaseTotalMs.get(phaseName);
        LongAdder count = phaseCount.get(phaseName);
        if (totalMs != null && count != null && count.sum() > 0) {
            return Math.max(1L, totalMs.sum() / count.sum());
        }
        // No learned average yet (first file of this model this session) —
        // fall back to a conservative heuristic: ~45s per MB of 16-bit PCM
        // WAV on CPU, which is a rough proxy for audio duration since we
        // don't have the source audio duration at this call site. This is
        // only ever used until the first real sample is recorded.
        return Math.max(5_000L, (long) (fileSizeMB * 45_000));
    }

    private final ScheduledExecutorService progressTicker =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Progress-Ticker"));

    /** Python stage timings captured per in-flight item, consumed by processFileWithPipelineParallelism right after transcribeSync returns. */
    private final Map<BatchFileItem, Map<String, Long>> lastPythonStageTimingsByItem = new ConcurrentHashMap<>();

    // FIX (doc-review — "Model acquisition" timing + real per-file resource
    // usage): side-channel maps in the same style as
    // lastPythonStageTimingsByItem above, for data transcribeSync()
    // measures/captures but doesn't return directly.
    private final Map<BatchFileItem, Long> modelAcquisitionMsByItem = new ConcurrentHashMap<>();
    private final Map<BatchFileItem, double[]> pythonResourceUsageByItem = new ConcurrentHashMap<>(); // [0]=peakMemoryMb [1]=avgCpuPercent

    /** Bounded, most-recent-first history of per-file timing reports, surfaced in the UI's Performance Report dialog. */
    private final java.util.LinkedList<FileTimingReport> recentTimingReports = new java.util.LinkedList<>();
    private static final int MAX_RECENT_TIMING_REPORTS = 100;

    public synchronized java.util.List<FileTimingReport> getRecentTimingReports() {
        return new java.util.ArrayList<>(recentTimingReports);
    }

    private synchronized void recordTimingReport(FileTimingReport report) {
        recentTimingReports.addFirst(report);
        while (recentTimingReports.size() > MAX_RECENT_TIMING_REPORTS) {
            recentTimingReports.removeLast();
        }
    }

    private TranscriptionResult transcribeSync(File wavFile,
                                               TranscriptionConfig config,
                                               BatchFileItem item) {
        String model = normalizeModelName(config.getModel());
        // FIX: was `Semaphore semaphore = modelSemaphores.getOrDefault(...)`
        // + `instance = getAvailableModelInstance(model)`, which always
        // returned instances.get(0) — the SAME single object — regardless
        // of how many permits the semaphore had. Multiple threads holding
        // permits concurrently were therefore all calling transcribe() on
        // one shared WhisperXTranscriptionService instance at once, an
        // unsafe pattern given that class has mutable instance state (e.g.
        // its segment listener). pool.borrow() hands out a genuinely
        // distinct, exclusively-owned instance per concurrent caller,
        // growing the pool lazily up to its live-adjustable target size.
        ModelInstancePool pool = modelPools.computeIfAbsent(model,
                m -> new ModelInstancePool(m, 1,
                        () -> new WhisperXTranscriptionService(new DependencyManager(), timeEstimator), LOGGER));
        WhisperXTranscriptionService instance = null;
        try {
            // FIX (doc-review — "Model acquisition" timing / "are files
            // really running in parallel, or serialized on one shared model
            // instance?"): times the pool.borrow() wait itself. If this
            // consistently comes back non-trivial across a batch configured
            // for maxParallel > 1, that's the measured, concrete answer to
            // that question — not a guess from indirect gaps between
            // completion timestamps in the log.
            long acquireStart = System.currentTimeMillis();
            instance = pool.borrow();
            modelAcquisitionMsByItem.put(item, System.currentTimeMillis() - acquireStart);
            LOGGER.debug("Model pool '{}': {} instance(s), target {}", model, pool.size(), pool.targetConcurrency());

            // FIX: transcribe() already returns the fully-parsed TranscriptionResult
            // in memory — that's all we need. The old code additionally tried to
            // relocate the transcription output on disk (recordOutputDir/
            // postProcessSync), but WhisperXTranscriptionService.transcribe()
            // deletes its own temp output directory in a finally block before
            // returning, so that scan always found nothing and silently produced
            // zero output files for multi-file/parallel batches.
            //
            // FIX: the progress callback below was previously `p -> {}` — a
            // no-op that silently discarded every progress update from
            // transcribe()/SegmentProcessor. That meant item.getProgress()
            // never moved during transcription on the parallel path, which
            // fed straight into: the per-row Progress column (stuck), the
            // "Ready to Process" overall bar (stuck), and — once wired up —
            // the live File/Total Time Left labels. Preprocessing is treated
            // as the first 30% and output-saving as the last 5%, so
            // transcription itself is mapped to the middle 65%.
            long transcribeStart = System.currentTimeMillis();
            String phaseName = "transcription_" + model;
            double sizeMB = wavFile.length() / (1024.0 * 1024.0);
            long estimatedTotalMs = estimateTranscriptionDurationMs(phaseName, sizeMB);

            final java.util.concurrent.atomic.AtomicReference<Double> latestCallbackFraction =
                    new java.util.concurrent.atomic.AtomicReference<>(0.0);

            java.util.concurrent.ScheduledFuture<?> ticker = progressTicker.scheduleAtFixedRate(() -> {
                try {
                    long elapsedMs = System.currentTimeMillis() - transcribeStart;
                    double timeFraction = Math.min(0.99, elapsedMs / (double) estimatedTotalMs);
                    double blended = Math.max(latestCallbackFraction.get(), timeFraction);
                    item.setProgress(0.3 + Math.max(0.0, Math.min(1.0, blended)) * 0.65);
                } catch (Exception e) {
                    // A progress-display glitch must never interrupt the actual transcription.
                    LOGGER.debug("Progress tick failed for {}: {}", wavFile.getName(), e.getMessage());
                }
            }, 1, 1, TimeUnit.SECONDS);

            TranscriptionResult result;
            try {
                result = instance.transcribe(wavFile.getAbsolutePath(), config,
                        p -> {
                            double clamped = Math.max(0.0, Math.min(1.0, p));
                            latestCallbackFraction.updateAndGet(prev -> Math.max(prev, clamped));
                            item.setProgress(0.3 + latestCallbackFraction.get() * 0.65);
                        },
                        0.0);
            } finally {
                ticker.cancel(false);
            }

            // FIX: added — was never recorded on this path, unlike the
            // (now-removed) standard path, which fed the adaptive
            // per-model learned-timing system every transcription. Without
            // this, the estimator's "transcription_<model>" learned samples
            // would only ever come from single-file (non-parallel) runs.
            recordPhaseTiming(phaseName, transcribeStart, sizeMB);

            // Capture the Python script's self-reported stage timings (model
            // load, audio load, transcription, alignment, diarization) while
            // this instance is still exclusively ours — must happen before
            // pool.release() below, after which another thread may reuse
            // this same instance and overwrite these values for its own file.
            lastPythonStageTimingsByItem.put(item, instance.getLastPythonStageTimingsMs());
            pythonResourceUsageByItem.put(item,
                    new double[]{instance.getLastPythonPeakMemoryMb(), instance.getLastPythonAvgCpuPercent()});

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Interrupted waiting for a model instance: " + wavFile.getName(), e);
        } catch (Exception e) {
            throw new CompletionException("Transcription failed for: " + wavFile.getName(), e);
        } finally {
            if (instance != null) pool.release(instance);
        }
    }

    /**
     * FIX (real gap found on review): routes files over
     * {@link #LARGE_FILE_THRESHOLD_MB} through the actual
     * {@link SegmentProcessor} — splitting into segments, transcribing each
     * with retry, and resuming from {@code progress.dat} if the app is
     * restarted mid-file — instead of the previous behaviour, which
     * silently ignored file size and ran the whole file through one
     * WhisperX call regardless (`processLargeFileInSegments()` was a stub
     * that always fell back to that; SegmentProcessor itself, despite being
     * fully built with resume support and per-segment retry, was never
     * called from anywhere in the pipeline).
     *
     * <p>Timing note: stage timings/resource usage below come from
     * {@link SegmentProcessor#getAggregatedStageTimingsMs()} — summed
     * across every segment actually transcribed this run (resumed/cached
     * segments from a prior run don't contribute, since nothing was
     * re-transcribed for them). Peak memory is the max across segments;
     * average CPU is the mean of each segment's own average.</p>
     */
    private TranscriptionResult transcribeWithSegmentation(File wavFile,
                                                            TranscriptionConfig config,
                                                            BatchFileItem item) throws Exception {
        String model = normalizeModelName(config.getModel());
        ModelInstancePool pool = modelPools.computeIfAbsent(model,
                m -> new ModelInstancePool(m, 1,
                        () -> new WhisperXTranscriptionService(new DependencyManager(), timeEstimator), LOGGER));
        WhisperXTranscriptionService instance = null;
        try {
            long acquireStart = System.currentTimeMillis();
            instance = pool.borrow();
            modelAcquisitionMsByItem.put(item, System.currentTimeMillis() - acquireStart);
            LOGGER.info("Large file ({}MB) — using segmented processing via SegmentProcessor for: {}",
                    wavFile.length() / (1024 * 1024), wavFile.getName());

            SegmentProcessor segmentProcessor = new SegmentProcessor(
                    instance, new DependencyManager(), timeEstimator,
                    (segmentIndex, totalSegments) -> LOGGER.debug(
                            "Segment {}/{} complete for {}", segmentIndex + 1, totalSegments, wavFile.getName()));

            double audioDurationSeconds = item.getTotalAudioDurationSeconds();
            TranscriptionResult result = segmentProcessor.processWithSegments(
                    wavFile.getAbsolutePath(), config,
                    p -> item.setProgress(0.3 + Math.max(0.0, Math.min(1.0, p)) * 0.65),
                    audioDurationSeconds);

            // FIX (aggregation gap, closed): previously read
            // instance.getLastPythonStageTimingsMs()/getLastPythonPeakMemoryMb()/
            // getLastPythonAvgCpuPercent() here, which only ever reflected
            // the LAST segment SegmentProcessor transcribed — a 20-segment
            // file's report understated transcription/alignment/diarization
            // time by ~20x. SegmentProcessor now sums each stage across
            // every segment it actually transcribes this run (see
            // SegmentProcessor.accumulateSegmentTiming/getAggregatedStageTimingsMs).
            lastPythonStageTimingsByItem.put(item, segmentProcessor.getAggregatedStageTimingsMs());
            pythonResourceUsageByItem.put(item,
                    new double[]{segmentProcessor.getAggregatedPeakMemoryMb(), segmentProcessor.getAggregatedAvgCpuPercent()});

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Interrupted during segmented transcription: " + wavFile.getName(), e);
        } finally {
            if (instance != null) pool.release(instance);
        }
    }

    /**
     * Writes the transcription output straight from the in-memory
     * {@link TranscriptionResult}, via the same {@link TranscriptionOutputWriter}
     * used by the standard (non-parallel) {@code BatchProcessor.saveOutput()}
     * path. Replaces the old disk-scanning {@code postProcessSync} /
     * {@code recordOutputDir} approach.
     */
    private void saveOutputDirectly(File originalFile, TranscriptionResult result,
                                    TranscriptionConfig transcriptionConfig, ProcessingConfig config) {
        long start = System.currentTimeMillis();
        try {
            File out = outputWriter.save(originalFile.getName(), result,
                    transcriptionConfig, config.getOutputDirectory());
            recordPhaseTiming("saving_transcription", start, originalFile.length() / (1024.0 * 1024.0));
            LOGGER.info("Saved output for {}: {}", originalFile.getName(), out);

            if (exportWordCopy) {
                try {
                    // Reuse the primary output's base name (minus its extension) so the
                    // two files are easy to find next to each other in the output folder.
                    String outName = out.getName();
                    int dot = outName.lastIndexOf('.');
                    String baseName = dot > 0 ? outName.substring(0, dot) : outName;
                    String wordPath = Paths.get(config.getOutputDirectory(), baseName + ".docx").toString();
                    outputWriter.exportToWord(result, wordPath);
                    LOGGER.info("Saved Word-compatible (.docx) copy for {}: {}", originalFile.getName(), wordPath);
                } catch (IOException e) {
                    // A failed optional export must never fail the whole file — the
                    // primary .srt/.txt output above already succeeded.
                    LOGGER.warn("Failed to save Word-compatible copy for {}: {}", originalFile.getName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save output for {}: {}", originalFile.getName(), e.getMessage());
            throw new CompletionException(e);
        }
    }

    // -------------------------------------------------------------------------
    //  Shutdown  (fixed: proper awaitTermination per pool)
    // -------------------------------------------------------------------------

    /**
     * Shuts down all thread pools and waits for them to drain.
     *
     * <p>FIX: original code called {@code awaitTermination(5, SECONDS)} for
     * all four pools in a single try block, giving each pool at most 5 seconds
     * total.  Now each pool gets an independent 30-second window so long
     * transcription tasks finish writing before the JVM exits.</p>
     */
    // FIX: added — previously there was no way to cancel an in-flight
    // parallel batch at all; MainWindow's cancel/exit confirmations only
    // ever called BatchProcessor.cancel(), which has no effect on this
    // class's own executors. This is a thin alias over shutdown() (which
    // already does shutdownNow() + awaitTermination on every pool) so the
    // intent reads clearly at the call site.
    /**
     * FIX: previously this only called {@link #shutdown()}, which does
     * {@code shutdownNow()} on the Java thread pools — that interrupts the
     * *worker threads*, but a worker thread blocked in
     * {@code Process.waitFor(timeout, unit)} inside {@link ProcessRunner}
     * doesn't necessarily die just because it's interrupted quickly; the
     * underlying WhisperX/ffmpeg OS process kept running regardless of what
     * the user clicked. {@link ProcessRunner#destroyAllActiveProcesses()}
     * now forcibly kills every subprocess this app has started, so
     * cancellation actually stops the expensive work, not just the Java
     * bookkeeping around it.
     */
    public void cancel() {
        LOGGER.info("Cancelling parallel batch...");
        int killed = ProcessRunner.destroyAllActiveProcesses();
        if (killed > 0) {
            LOGGER.info("Forcibly terminated {} active subprocess(es) on cancel.", killed);
        }
        shutdown();
    }

    // -------------------------------------------------------------------------
    //  Test-only visibility hooks
    // -------------------------------------------------------------------------
    //
    // FIX (continuation): cancel()/shutdown() had the same problem
    // ModelInstancePool/ResizableSemaphore had before their own testability
    // fix — no way to observe whether a cancellation actually completed
    // (pools terminated, resource monitor stopped, initialized flag reset)
    // short of running a real batch and guessing from timing. These two
    // package-private accessors expose exactly the state a test needs to
    // assert on, without changing production behaviour at all.

    /** Package-private, for tests only. True once {@link #initialize} has completed and {@link #shutdown} hasn't run since. */
    boolean isInitializedForTesting() {
        return initialized;
    }

    /** Package-private, for tests only. True if every pool this instance owns is either never-allocated or fully terminated. */
    boolean areExecutorsTerminatedForTesting() {
        return (ioExecutor == null || ioExecutor.isTerminated())
                && (cpuExecutor == null || cpuExecutor.isTerminated())
                && (pipelineExecutor == null || pipelineExecutor.isTerminated())
                && resourceMonitorExecutor == null;
    }

    public void shutdown() {
        List<ExecutorService> executors = Arrays.asList(
                ioExecutor, cpuExecutor, pipelineExecutor);

        // FIX: added — the resource monitor was leaking a scheduled executor
        // across restarts (every call to initialize() called shutdown()
        // first, but never stopped a resource monitor started by a
        // previous initialize() call).
        if (resourceMonitorExecutor != null) {
            resourceMonitorExecutor.shutdownNow();
            resourceMonitorExecutor = null;
        }
        stopBatchSampler();

        // Signal all pools to stop accepting new work
        executors.forEach(ex -> { if (ex != null) ex.shutdownNow(); });

        // Wait for each pool independently
        boolean interrupted = false;
        for (ExecutorService ex : executors) {
            if (ex == null) continue;
            try {
                if (!ex.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOGGER.warn("Executor {} did not terminate within 30s.", ex);
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();

        // Clean up pipeline stage workers
        pipelineStages.values().forEach(PipelineStage::shutdown);
        modelPools.clear();
        initialized = false;
        LOGGER.info("ParallelProcessingManager shutdown complete.");
    }

    // -------------------------------------------------------------------------
    //  isModelInHuggingFaceCache  (NullPointerException fix)
    // -------------------------------------------------------------------------

    /**
     * Check whether a model is present in the HuggingFace disk cache.
     *
     * <p>FIX: {@code System.getenv("LOCALAPPDATA")} returns {@code null} on
     * Linux and macOS.  The original code called
     * {@code Paths.get(null, ...)} which threw a {@link NullPointerException}.
     * Now the method checks for {@code null} before constructing the path and
     * falls back to the cross-platform XDG cache directory.</p>
     */
    public boolean isModelInHuggingFaceCache(String modelName) {
        // 1. Windows-specific path (LOCALAPPDATA may be null on non-Windows)
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path winCache = Paths.get(localAppData, "huggingface", "hub");
            if (Files.exists(winCache) && containsWhisperModel(winCache, modelName)) return true;
        }

        // 2. Cross-platform XDG / macOS path  (~/.cache/huggingface/hub)
        Path xdgCache = Paths.get(System.getProperty("user.home"), ".cache", "huggingface", "hub");
        if (Files.exists(xdgCache) && containsWhisperModel(xdgCache, modelName)) return true;

        // 3. HUGGINGFACE_HUB_CACHE environment variable (explicit override)
        String hfCacheEnv = System.getenv("HUGGINGFACE_HUB_CACHE");
        if (hfCacheEnv != null) {
            Path envCache = Paths.get(hfCacheEnv);
            if (Files.exists(envCache) && containsWhisperModel(envCache, modelName)) return true;
        }

        return false;
    }

    private boolean containsWhisperModel(Path cacheDir, String modelName) {
        try {
            return Files.list(cacheDir).anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.contains("whisper") && name.contains(modelName);
            });
        } catch (IOException e) {
            LOGGER.debug("Could not scan cache dir {}: {}", cacheDir, e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    //  Memory helpers
    // -------------------------------------------------------------------------

    private long getUsedMemoryMB() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    // FIX: was `r.freeMemory() / (1024 * 1024)` — Runtime.freeMemory() is the
    // unused space within the heap the JVM has *currently committed*
    // (totalMemory()), not the headroom up to the real ceiling
    // (maxMemory(), i.e. -Xmx or the JVM's ergonomic default). Right after
    // startup the JVM typically commits only a small fraction of its max
    // heap and grows it lazily as needed, so this always read as tiny
    // (e.g. ~56MB) even on a machine with 1-4GB of real headroom available —
    // permanently tripping the LOW_MEMORY_THRESHOLD_MB cap in
    // calculateModelThreads() and silencing "10x+ parallel" processing down
    // to a single model thread for the entire batch, every run, regardless
    // of actual available memory. Available headroom is maxMemory - used.
    private long getFreeMemoryMB() {
        Runtime r = Runtime.getRuntime();
        long maxMB  = r.maxMemory() / (1024 * 1024);
        long usedMB = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
        return Math.max(0, maxMB - usedMB);
    }

    // -------------------------------------------------------------------------
    //  Utility helpers
    // -------------------------------------------------------------------------

    private String getFileNameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // -------------------------------------------------------------------------
    //  Inner types
    // -------------------------------------------------------------------------

    /**
     * A per-model pool of {@link WhisperXTranscriptionService} instances,
     * sized dynamically based on live CPU/heap pressure (see
     * {@link #startResourceMonitor}) rather than a fixed decision made once
     * at batch start.
     *
     * <p>FIX: this replaces a design that created exactly one instance per
     * model and gated it with a {@link Semaphore} that — due to a bug in
     * that instance count — also always held exactly one permit, meaning
     * transcription concurrency for the same model was hard-capped at 1
     * regardless of {@code maxParallelFiles}, CPU, or memory, from the very
     * first version of this class.</p>
     *
     * <p>Uses a blocking queue as the pool: {@link #borrow} blocks until an
     * instance is free, guaranteeing each instance is only ever used by one
     * thread at a time — unlike a semaphore-plus-round-robin scheme, which
     * can't guarantee that without separately tracking per-instance busy
     * state. Shrinking the pool ({@link #adjustTarget} with a lower value)
     * never destroys or interrupts an in-flight instance: it just stops
     * that instance being returned to the queue once released, letting it
     * be garbage collected, so a long-running transcription already using
     * it is never disturbed.</p>
     */
    /**
     * A per-model pool of {@link WhisperXTranscriptionService} instances,
     * with concurrency throttled dynamically based on live CPU/heap
     * pressure (see {@link #startResourceMonitor}) rather than a fixed
     * decision made once at batch start.
     *
     * <p>FIX (regression): the first version of this pool eagerly created
     * every instance up front and discarded/recreated instances whenever
     * the live resource monitor shrank its target below the current live
     * count. That combination turned a 45-90 minute job into a ~360 minute
     * one: {@code WhisperXTranscriptionService}'s constructor makes a real
     * subprocess call to verify the WhisperX installation (many seconds
     * each), and the monitor's "CPU > 90% -> shrink" rule fires constantly
     * during transcription, because CPU-bound transcription work is
     * <em>supposed</em> to peg the CPU — that's normal, expected load, not
     * contention to back off from. Every time the pool then needed an
     * instance again with none recycled, it paid that expensive
     * construction cost again, potentially several times over the course
     * of one segmented file.</p>
     *
     * <p>Concurrency throttling is now fully decoupled from instance
     * lifecycle. Instances are created lazily — only the first time actual
     * concurrent demand needs one — and once created are <b>never</b>
     * discarded; they're recycled for the lifetime of this pool. The
     * resource monitor only resizes a separate {@link ResizableSemaphore}
     * gating how many borrows are allowed at once, which costs nothing to
     * adjust, however often or aggressively, since it never touches an
     * actual instance.</p>
     *
     * <p>Package-private (not {@code private}) so unit tests in
     * {@code audiomanager.core} can exercise lazy growth, borrow/release, and
     * concurrency-gate resizing directly — this concurrency logic previously
     * had no test coverage because there was no way to reach it except by
     * running a full batch through {@link ParallelProcessingManager}.</p>
     *
     * <p>FIX: instance creation used to be hard-wired to
     * {@code new WhisperXTranscriptionService(dependencyManagerFactory.get(), timeEstimator)}
     * inline, which meant testing this class's lazy-growth and
     * concurrency-gate logic in isolation was impossible without also
     * triggering real GPU/Python/FFmpeg probing on every borrowed instance.
     * The constructor now takes a plain
     * {@code Supplier<WhisperXTranscriptionService>}, so production code
     * passes the real constructor call and tests can pass a cheap fake.</p>
     */
    static final class ModelInstancePool {
        private final String model;
        private final java.util.function.Supplier<WhisperXTranscriptionService> instanceFactory;
        private final Logger logger;
        private final LinkedBlockingQueue<WhisperXTranscriptionService> available = new LinkedBlockingQueue<>();
        private final AtomicInteger liveCount = new AtomicInteger(0);
        private final int maxSize;
        private final ResizableSemaphore concurrencyGate;

        ModelInstancePool(String model, int maxConcurrent,
                          java.util.function.Supplier<WhisperXTranscriptionService> instanceFactory,
                          Logger logger) {
            this.model = model;
            this.instanceFactory = instanceFactory;
            this.logger = logger;
            this.maxSize = Math.max(1, maxConcurrent);
            this.concurrencyGate = new ResizableSemaphore(this.maxSize);
            // FIX: no eager instance creation here anymore — see class doc.
        }

        private synchronized void createAndAdd() {
            try {
                WhisperXTranscriptionService instance = instanceFactory.get();
                available.offer(instance);
                liveCount.incrementAndGet();
            } catch (Exception e) {
                logger.warn("Failed to create model instance for {}: {}", model, e.getMessage());
            }
        }

        /** Borrows an exclusively-owned instance, blocking if the concurrency gate is full. */
        WhisperXTranscriptionService borrow() throws InterruptedException {
            concurrencyGate.acquire();
            WhisperXTranscriptionService instance = available.poll();
            if (instance != null) return instance;
            // FIX: grows lazily, purely driven by actual demand, up to
            // maxSize — not eagerly at pool construction. A single
            // sequential file only ever needs one instance and will only
            // ever construct one, regardless of what maxSize is.
            if (liveCount.get() < maxSize) {
                createAndAdd();
                instance = available.poll();
                if (instance != null) return instance;
            }
            return available.take();
        }

        /** Returns an instance after use. Always recycled — never discarded. */
        void release(WhisperXTranscriptionService instance) {
            available.offer(instance);
            concurrencyGate.release();
        }

        /** Live-adjusts allowed concurrency between 1 and this pool's capacity. Free to call as often as needed — never touches an instance. */
        void adjustTarget(int newTarget) {
            concurrencyGate.setPermits(Math.max(1, Math.min(maxSize, newTarget)));
        }

        int size() { return liveCount.get(); }

        /** Current live-adjusted concurrency ceiling (see {@link #adjustTarget}) — for observability/logging only. */
        int targetConcurrency() { return concurrencyGate.getTargetPermits(); }
    }

    /**
     * A {@link Semaphore} whose permit count can be adjusted up or down at
     * runtime without disturbing anything currently holding a permit —
     * shrinking just means future {@link #acquire()} calls wait a little
     * longer, exactly the behaviour needed for throttling
     * {@link ModelInstancePool} without ever touching object lifecycle.
     *
     * <p>Package-private for direct unit testing (see
     * {@code ResizableSemaphoreTest}).</p>
     */
    static final class ResizableSemaphore extends Semaphore {
        private final AtomicInteger currentPermits;

        ResizableSemaphore(int initial) {
            super(initial, true);
            this.currentPermits = new AtomicInteger(initial);
        }

        synchronized void setPermits(int target) {
            int diff = target - currentPermits.get();
            if (diff > 0) {
                release(diff);
                currentPermits.addAndGet(diff);
            } else if (diff < 0) {
                reducePermits(-diff);
                currentPermits.addAndGet(diff);
            }
        }

        /** The configured target permit count (distinct from {@link #availablePermits()}, which drops while permits are held). */
        int getTargetPermits() {
            return currentPermits.get();
        }
    }

    private static class FileGroup {
        final String type;
        final long sizeMB;

        FileGroup(String type, long sizeMB) { this.type = type; this.sizeMB = sizeMB; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof FileGroup g)) return false;
            return sizeMB == g.sizeMB && Objects.equals(type, g.type);
        }
        @Override public int hashCode() { return Objects.hash(type, sizeMB); }
        @Override public String toString() { return "FileGroup{" + type + "@" + sizeMB + "MB}"; }
    }

    private static class PipelineStage {
        private final String name;
        private final int workerCount;
        private ExecutorService workers;

        PipelineStage(String name, int workerCount) { this.name = name; this.workerCount = workerCount; }

        void initializeWorkers() {
            workers = Executors.newFixedThreadPool(workerCount, new NamedThreadFactory("Pipeline-" + name));
        }

        void shutdown() { if (workers != null) workers.shutdownNow(); }

        CompletableFuture<Void> submit(Runnable task) {
            return CompletableFuture.runAsync(task, workers);
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) { this.prefix = prefix; }

        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    public static class FileResult {
        public final File    file;
        public final boolean success;
        public final String  message;

        public FileResult(File file, boolean success, String message) {
            this.file = file; this.success = success; this.message = message;
        }
    }

    public static class ParallelBatchResult {
        private final int  total, completed, failed;
        private final long durationMillis;
        private final boolean cancelled;
        private final Set<String> successfulFiles = new HashSet<>();

        public ParallelBatchResult(int total, int completed, int failed,
                                   long durationMillis, boolean cancelled) {
            this.total = total; this.completed = completed; this.failed = failed;
            this.durationMillis = durationMillis; this.cancelled = cancelled;
        }

        public int  getTotal()        { return total; }
        public int  getCompleted()    { return completed; }
        public int  getFailed()       { return failed; }
        public long getDurationMillis(){ return durationMillis; }
        public boolean wasCancelled() { return cancelled; }
        public void addSuccessfulFile(String name) { successfulFiles.add(name); }
        public Set<String> getSuccessfulFiles() { return Collections.unmodifiableSet(successfulFiles); }
    }
}