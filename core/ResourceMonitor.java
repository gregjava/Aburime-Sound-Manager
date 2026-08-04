/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import org.slf4j.Logger;

import java.util.DoubleSummaryStatistics;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * System resource probing and per-batch resource observability, extracted
 * out of {@link ParallelProcessingManager} (which had grown past 1,600
 * lines covering several distinct concerns at once — pipeline orchestration,
 * model pooling, AND resource monitoring all in one file).
 *
 * <p>Owns three related but separable things:</p>
 * <ol>
 *   <li><b>Point-in-time probes</b> — {@link #getSystemCpuLoadPercent()},
 *       {@link #getSystemMemoryUsedPercent()}, {@link #getUsedMemoryMB()} —
 *       used both by the adaptive concurrency cycle (still in
 *       {@code ParallelProcessingManager}, since only it has direct access
 *       to the model pools it resizes) and by this class's own sampler.</li>
 *   <li><b>The 2-second batch sampler</b> — {@link #startSampling}/
 *       {@link #stopSampling} — a purely observational background thread,
 *       separate from the adaptive cycle's 5-second control loop. Accumulates
 *       mean/peak CPU and RAM for {@link #buildBatchSummary}.</li>
 *   <li><b>Scaling-event tracking</b> — {@link #recordScalingEvent} — a
 *       simple counter the adaptive cycle calls into whenever it actually
 *       changes the model-pool concurrency target, so the batch summary can
 *       report how many times a batch was throttled without the caller
 *       needing to track that itself.</li>
 * </ol>
 *
 * <p>One instance is meant to live for the lifetime of a
 * {@code ParallelProcessingManager}; call {@link #resetForNewBatch()} at the
 * start of each batch so stats don't bleed across runs.</p>
 */
public class ResourceMonitor {

    private final Logger logger;

    private ScheduledExecutorService samplerExecutor;
    private DoubleSummaryStatistics cpuStats = new DoubleSummaryStatistics();
    private DoubleSummaryStatistics memStats = new DoubleSummaryStatistics();
    private volatile long peakHeapMB = 0;
    private final AtomicInteger scalingEventCount = new AtomicInteger(0);

    public ResourceMonitor(Logger logger) {
        this.logger = logger;
    }

    // -------------------------------------------------------------------------
    //  Point-in-time probes
    // -------------------------------------------------------------------------

    /** System-wide CPU load as a percentage, or -1 if unavailable on this JVM/platform. */
    public double getSystemCpuLoadPercent() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getCpuLoad();
                return load >= 0 ? load * 100.0 : -1;
            }
        } catch (Exception e) {
            logger.debug("CPU load measurement unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Percentage of total physical (system-wide) RAM currently in use, or
     * -1 if unavailable on this JVM/platform.
     *
     * <p>Distinct from JVM heap usage: this app's actual memory-heavy work
     * (WhisperX/torch model inference) runs in external Python subprocesses,
     * whose memory usage is entirely invisible to the JVM heap — see
     * {@code ParallelProcessingManager.startResourceMonitor} for why the
     * adaptive concurrency cycle checks both signals.</p>
     */
    public double getSystemMemoryUsedPercent() {
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
            logger.debug("System memory measurement unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Currently-used JVM heap, in MB.
     *
     * <p>Uses {@code totalMemory() - freeMemory()}, i.e. used space within
     * what the JVM has actually committed — not {@code maxMemory()} (the
     * real ceiling, e.g. {@code -Xmx}). A prior version of this measurement
     * used {@code freeMemory()} alone as if it were available headroom,
     * which reads as near-zero right after startup (before the heap has
     * grown to reflect real usage) and permanently tripped low-memory
     * fallback logic regardless of actual available headroom.</p>
     */
    public long getUsedMemoryMB() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    // -------------------------------------------------------------------------
    //  Batch-level sampling (2s, observation-only)
    // -------------------------------------------------------------------------

    /** Clears accumulated stats — call once at the start of each batch. */
    public void resetForNewBatch() {
        cpuStats = new DoubleSummaryStatistics();
        memStats = new DoubleSummaryStatistics();
        peakHeapMB = 0;
        scalingEventCount.set(0);
    }

    /**
     * Starts a 2-second background sampler that logs a compact resource
     * snapshot and accumulates stats for {@link #buildBatchSummary}. Safe
     * to call once per batch; call {@link #stopSampling()} when the batch
     * ends (success, failure, or cancellation — a {@code finally} block).
     *
     * @param activeFileCountSupplier reports how many files are actively
     *        being processed right now, for the log line only
     * @param uiLogger also-append each sample line here (e.g. the app's
     *        Terminal panel), or {@code null} to only log via SLF4J
     */
    public void startSampling(IntSupplier activeFileCountSupplier, Consumer<String> uiLogger) {
        samplerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Batch-Resource-Sampler");
            t.setDaemon(true);
            return t;
        });
        samplerExecutor.scheduleAtFixedRate(() -> {
            try {
                double cpuPct = getSystemCpuLoadPercent();
                double memPct = getSystemMemoryUsedPercent();
                long heapMB = getUsedMemoryMB();
                if (cpuPct >= 0) cpuStats.accept(cpuPct);
                if (memPct >= 0) memStats.accept(memPct);
                peakHeapMB = Math.max(peakHeapMB, heapMB);

                String line = String.format("RESOURCE_SAMPLE: cpu=%s%% mem=%s%% heap=%dMB activeFiles=%d",
                        cpuPct >= 0 ? String.format("%.0f", cpuPct) : "n/a",
                        memPct >= 0 ? String.format("%.0f", memPct) : "n/a",
                        heapMB, activeFileCountSupplier.getAsInt());
                logger.info(line);
            } catch (Exception e) {
                logger.debug("Resource sample failed: {}", e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public void stopSampling() {
        if (samplerExecutor != null) {
            samplerExecutor.shutdownNow();
            samplerExecutor = null;
        }
    }

    // -------------------------------------------------------------------------
    //  Scaling-event tracking
    // -------------------------------------------------------------------------

    /**
     * Call from the adaptive concurrency cycle whenever it changes the
     * live model-pool concurrency target. Logs the transition and
     * increments the counter shown in {@link #buildBatchSummary}. Pass -1
     * for {@code previousTarget} on the very first cycle of a batch (no
     * transition to report yet) — this method is a no-op in that case.
     */
    public void recordScalingEvent(int previousTarget, int newTarget, double cpuLoadPct, double memUsedPct) {
        if (previousTarget == -1 || previousTarget == newTarget) return;
        scalingEventCount.incrementAndGet();
        logger.info("Adaptive scaling: model concurrency target changed {} -> {} (cpu={}%, mem={}%)",
                previousTarget, newTarget, cpuLoadPct, memUsedPct);
    }

    // -------------------------------------------------------------------------
    //  Batch summary
    // -------------------------------------------------------------------------

    /**
     * Builds the end-of-batch summary block (files, audio duration, elapsed
     * time, throughput, CPU/RAM stats, peak heap, scaling events). The
     * caller decides where it goes (SLF4J, the UI's Terminal panel, or
     * both) — this method only formats it.
     */
    public String buildBatchSummary(int filesProcessed, double totalAudioDurationSeconds, long elapsedMs) {
        double elapsedHours = elapsedMs / 3_600_000.0;
        double audioMinutes = totalAudioDurationSeconds / 60.0;
        double throughputMinPerHour = elapsedHours > 0 ? audioMinutes / elapsedHours : 0;
        double avgCpu = cpuStats.getCount() > 0 ? cpuStats.getAverage() : -1;
        long cpuActiveMs = avgCpu >= 0 ? Math.round(elapsedMs * (avgCpu / 100.0)) : -1;

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Batch Summary ==========\n");
        sb.append(String.format("Files processed:      %d%n", filesProcessed));
        sb.append(String.format("Audio duration:       %s%n", formatDuration((long) (totalAudioDurationSeconds * 1000))));
        sb.append(String.format("Elapsed time:          %s%n", formatDuration(elapsedMs)));
        sb.append(String.format("Average throughput:    %.1f min audio / hour%n", throughputMinPerHour));
        sb.append(String.format("Average CPU:           %s%n", avgCpu >= 0 ? String.format("%.1f%%", avgCpu) : "n/a"));
        sb.append(String.format("Peak CPU:              %s%n",
                cpuStats.getCount() > 0 ? String.format("%.1f%%", cpuStats.getMax()) : "n/a"));
        sb.append(String.format("Average RAM:           %s%n",
                memStats.getCount() > 0 ? String.format("%.1f%%", memStats.getAverage()) : "n/a"));
        sb.append(String.format("Peak RAM:              %s%n",
                memStats.getCount() > 0 ? String.format("%.1f%%", memStats.getMax()) : "n/a"));
        sb.append(String.format("Peak Java heap:        %d MB%n", peakHeapMB));
        if (cpuActiveMs >= 0) {
            sb.append(String.format("CPU active / idle:     %s / %s%n",
                    formatDuration(cpuActiveMs), formatDuration(Math.max(0, elapsedMs - cpuActiveMs))));
        }
        sb.append(String.format("Scaling events:        %d (model-pool concurrency target changed this batch)%n",
                scalingEventCount.get()));
        sb.append("====================================");
        return sb.toString();
    }

    public static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }
}