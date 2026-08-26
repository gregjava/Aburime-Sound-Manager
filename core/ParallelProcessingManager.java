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
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Enhanced parallel processing manager with dynamic thread pool optimization.
 *
 * <p>This class orchestrates the parallel processing of multiple audio files,
 * providing features such as:
 * <ul>
 *   <li><b>Dynamic thread pool sizing:</b> Automatically adjusts based on
 *       system resources (CPU cores, memory, GPU availability)</li>
 *   <li><b>Memory-aware admission control:</b> Staggers file admission to
 *       prevent memory thundering herd</li>
 *   <li><b>GPU-aware concurrency management:</b> Limits parallelism based
 *       on GPU memory capacity</li>
 *   <li><b>Performance monitoring and auto-tuning:</b> Continuously adjusts
 *       thread pool size based on runtime conditions</li>
 *   <li><b>Model instance pooling:</b> Reuses transcription service instances
 *       to reduce startup overhead</li>
 *   <li><b>Timing and performance reporting:</b> Captures detailed timing
 *       and resource usage data for each file</li>
 *   <li><b>Post-transcription translation:</b> Translates transcripts when
 *       configured via BatchProcessor</li>
 * </ul>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see BatchProcessor
 * @see FileTimingReport
 * @see GpuConfig
 */
public class ParallelProcessingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelProcessingManager.class);

    // ===== Configuration Constants =====
    private static final int MIN_THREADS = 1;
    private static final int MAX_THREADS = 16;
    private static final long THREAD_KEEP_ALIVE = 60L;
    private static final int QUEUE_SIZE = 100;
    // Max number of WhisperX model instances (and therefore concurrent
    // transcriptions) allowed at once, independent of workerPool's thread
    // count. Each loaded large-v2 CPU instance can use several GB of RAM;
    // raise this only if your machine has RAM to spare for that many
    // concurrent instances on top of the base app/JVM footprint. A value
    // that's too high relative to available RAM can crash the JVM with a
    // native out-of-memory error rather than fail individual files gracefully.
    private static final int MODEL_POOL_CAP = 2;
    private static final long ADMISSION_STAGGER_MS = 3000;
    private static final double MEMORY_PRESSURE_THRESHOLD = 80.0;
    private static final long MAX_ADMISSION_WAIT_MS = 30000;
    private static final long FILE_TIMEOUT_HOURS = 24;
    private static final long MONITOR_INTERVAL_MS = 5000;

    // ===== Dependencies =====
    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final Consumer<String> logger;
    private final TimeLeftEstimator timeEstimator;
    private final TranscriptionOutputWriter outputWriter;
    private final GpuConfig gpuConfig = GpuConfig.getInstance();

    // ===== NEW: Reference to BatchProcessor for translation =====
    private BatchProcessor batchProcessor;

    // ===== Threading =====
    private ThreadPoolExecutor workerPool;
    // Dedicated executor for the batch coordinator task (see processBatchParallel).
    // MUST stay separate from workerPool: the coordinator submits per-file tasks to
    // workerPool and then blocks waiting on them. If the coordinator itself ran on
    // workerPool, it could occupy the only available thread and deadlock the batch
    // (this happened when corePoolSize was 1 and the queue never filled enough to
    // grow the pool - the coordinator held the sole thread while waiting on file
    // tasks that had no thread left to run on).
    private final ExecutorService coordinatorExecutor;
    private final ModelInstancePool modelInstancePool;
    private final ScheduledExecutorService monitorService;
    private final int availableProcessors;
    private volatile boolean cancelled = false;
    private volatile boolean running = true;

    // ===== Status =====
    private final AtomicInteger activeFileCount = new AtomicInteger(0);
    private final AtomicInteger totalTasksProcessed = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final Map<BatchFileItem, Map<String, Long>> pythonStageTimings = new ConcurrentHashMap<>();
    private final Map<BatchFileItem, double[]> pythonResourceUsage = new ConcurrentHashMap<>();
    private final List<FileTimingReport> recentTimingReports = new CopyOnWriteArrayList<>();
    private static final int MAX_REPORTS = 100;

    // ===== Export flags =====
    private volatile boolean exportWordCopy = false;
    private volatile boolean exportPdfCopy = false;

    // ===== Error handling =====
    private ErrorReporter errorReporter;
    private BatchProcessor.FileCompletionCallback completionCallback;

    // ========================================================================
    //  Construction
    // ========================================================================

    /**
     * Constructs a new ParallelProcessingManager with default parallelism.
     *
     * @param audioProcessor the audio processor for file conversion
     * @param transcriptionService the transcription service
     * @param logger a consumer for log messages
     * @param timeEstimator the time estimator for progress tracking
     */
    public ParallelProcessingManager(AudioProcessor audioProcessor,
                                     WhisperXTranscriptionService transcriptionService,
                                     Consumer<String> logger,
                                     TimeLeftEstimator timeEstimator) {
        this(audioProcessor, transcriptionService, logger, timeEstimator,
             Runtime.getRuntime().availableProcessors());
    }

    /**
     * Constructs a new ParallelProcessingManager with a specified maximum.
     *
     * @param audioProcessor the audio processor for file conversion
     * @param transcriptionService the transcription service
     * @param logger a consumer for log messages
     * @param timeEstimator the time estimator for progress tracking
     * @param maxConcurrentFiles the maximum number of concurrent files
     */
    public ParallelProcessingManager(AudioProcessor audioProcessor,
                                     WhisperXTranscriptionService transcriptionService,
                                     Consumer<String> logger,
                                     TimeLeftEstimator timeEstimator,
                                     int maxConcurrentFiles) {
        this.audioProcessor = audioProcessor;
        this.transcriptionService = transcriptionService;
        this.logger = logger;
        this.timeEstimator = timeEstimator;
        this.availableProcessors = Runtime.getRuntime().availableProcessors();

        // Initialize GPU detection
        gpuConfig.detectGpu();
        if (gpuConfig.isGpuAvailable()) {
            LOGGER.info("✅ GPU available: {}", gpuConfig.getGpuSummary());
        } else {
            LOGGER.info("ℹ️ No GPU detected — running on CPU mode");
        }

        // Initialize output writer
        this.outputWriter = new TranscriptionOutputWriter();

        // Create dynamic thread pool
        int optimalThreads = calculateOptimalThreadCount(maxConcurrentFiles);
        this.workerPool = createThreadPool(optimalThreads);

        // Coordinator runs on its own single-thread executor so it never competes
        // with the per-file worker threads it submits work to and blocks on.
        this.coordinatorExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("Batch-Coordinator"));

        LOGGER.info("Dynamic thread pool initialized: core={}, max={}, queue={}",
                    optimalThreads, optimalThreads, QUEUE_SIZE);

        // Model pool: lazily creates WhisperX instances on demand, up to
        // min(optimalThreads, MODEL_POOL_CAP). Unlike a plain fixed-size queue,
        // borrow() blocks callers when the pool is at capacity instead of timing
        // out and failing the file - this correctly throttles concurrent
        // transcriptions to what the model pool can support, even when
        // workerPool's thread concurrency is higher.
        int modelPoolSize = Math.min(optimalThreads, MODEL_POOL_CAP);
        this.modelInstancePool = new ModelInstancePool(
            "whisperx", modelPoolSize, this::createModelInstance, LOGGER);

        // Start monitor service for dynamic adjustment
        this.monitorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ParallelProcessor-Monitor");
            t.setDaemon(true);
            return t;
        });
        this.monitorService.scheduleAtFixedRate(
            this::adjustThreadPool,
            MONITOR_INTERVAL_MS,
            MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        LOGGER.info("ParallelProcessingManager initialized: {} cores, dynamic pool, GPU: {}",
            availableProcessors, gpuConfig.isGpuAvailable() ? "available" : "not available");
    }

    // ========================================================================
    //  Thread Pool Management
    // ========================================================================

    /**
     * Creates the thread pool with dynamic sizing.
     *
     * @param maxThreads the maximum number of threads
     * @return the configured ThreadPoolExecutor
     */
    private ThreadPoolExecutor createThreadPool(int maxThreads) {
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
        ThreadFactory threadFactory = new NamedThreadFactory("Parallel-Worker");

        // corePoolSize is set equal to maxThreads (not MIN_THREADS) because a
        // standard ThreadPoolExecutor only spins up threads beyond corePoolSize
        // once the work queue is completely full. With QUEUE_SIZE=100, a normal
        // batch would never fill the queue, so a corePoolSize of 1 meant the pool
        // effectively never used more than one thread regardless of maxThreads.
        // adjustThreadPool() still shrinks/grows corePoolSize at runtime based on
        // load, so this only changes the starting point, not the dynamic behavior.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            maxThreads,
            maxThreads,
            THREAD_KEEP_ALIVE,
            TimeUnit.SECONDS,
            workQueue,
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // Since corePoolSize now starts equal to maxThreads, core threads must be
        // allowed to time out (otherwise idle workers are never reclaimed).
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Calculates the optimal thread count based on system resources.
     *
     * <p>This method considers:
     * <ul>
     *   <li>CPU cores (leaves one core for the system)</li>
     *   <li>GPU memory (limits parallelism based on available VRAM)</li>
     *   <li>System memory (limits based on available RAM)</li>
     *   <li>User-specified maximum</li>
     * </ul>
     *
     * @param userMax the user-specified maximum
     * @return the optimal thread count
     */
    private int calculateOptimalThreadCount(int userMax) {
        // Base on CPU cores (leave one core for system)
        int cpuBased = Math.max(1, availableProcessors - 1);

        // Adjust for GPU
        int gpuBased = Integer.MAX_VALUE;
        if (gpuConfig.isGpuAvailable() && gpuConfig.shouldUseGpu()) {
            long gpuMemoryMB = gpuConfig.getGpuMemoryMB();
            if (gpuMemoryMB > 0) {
                long usableMemory = Math.max(0, gpuMemoryMB - 1024);
                gpuBased = (int) Math.max(1, usableMemory / 500);
                LOGGER.debug("GPU-based thread limit: {} (memory: {} MB, usable: {} MB)",
                            gpuBased, gpuMemoryMB, usableMemory);
            }
        }

        // Adjust for memory
        long maxMemory = Runtime.getRuntime().maxMemory();
        int memoryBased = (int) (maxMemory / (300 * 1024 * 1024)); // 300MB per job
        memoryBased = Math.max(1, memoryBased);

        // Combine all factors
        int optimal = Math.min(cpuBased, Math.min(gpuBased, memoryBased));
        optimal = Math.min(optimal, userMax);
        optimal = Math.max(MIN_THREADS, Math.min(MAX_THREADS, optimal));

        LOGGER.debug("Thread calculation: CPU={}, GPU={}, Memory={}, User={}, Optimal={}",
                    cpuBased, gpuBased == Integer.MAX_VALUE ? "N/A" : gpuBased,
                    memoryBased, userMax, optimal);

        return optimal;
    }

    /**
     * Dynamically adjusts the thread pool size based on system conditions.
     *
     * <p>This method is called periodically by the monitor service and
     * adjusts thread count based on memory pressure, queue backlog, and
     * under-utilisation.</p>
     */
    private void adjustThreadPool() {
        if (!running || cancelled || workerPool == null) return;

        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            double memoryUsage = (double) usedMemory / maxMemory * 100.0;

            int queueSize = workerPool.getQueue().size();
            int activeCount = workerPool.getActiveCount();
            int currentMax = workerPool.getMaximumPoolSize();
            int currentCore = workerPool.getCorePoolSize();

            boolean memoryPressure = memoryUsage > MEMORY_PRESSURE_THRESHOLD;
            boolean queueBacklog = queueSize > 50 && activeCount >= currentCore;
            boolean underutilized = activeCount < currentCore / 2 && queueSize < 10;

            int newMax = currentMax;
            int newCore = currentCore;

            if (memoryPressure) {
                newMax = Math.max(MIN_THREADS, currentMax - 1);
                newCore = Math.min(newCore, newMax);
                LOGGER.debug("Memory pressure ({:.1f}%), reducing threads", memoryUsage);
            } else if (queueBacklog && currentMax < MAX_THREADS) {
                newMax = Math.min(MAX_THREADS, currentMax + 1);
                newCore = Math.min(newCore + 1, newMax);
                LOGGER.debug("Queue backlog ({} tasks), increasing threads", queueSize);
            } else if (underutilized && currentCore > MIN_THREADS) {
                newCore = Math.max(MIN_THREADS, currentCore - 1);
                newMax = Math.max(MIN_THREADS, currentMax - 1);
                LOGGER.debug("Underutilized ({} active, {} queue), reducing threads",
                            activeCount, queueSize);
            }

            if (newMax != currentMax) {
                workerPool.setMaximumPoolSize(newMax);
                LOGGER.info("Adjusted max threads: {} → {}", currentMax, newMax);
            }
            if (newCore != currentCore) {
                workerPool.setCorePoolSize(newCore);
                LOGGER.info("Adjusted core threads: {} → {}", currentCore, newCore);
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to adjust thread pool: {}", e.getMessage());
        }
    }

    // ========================================================================
    //  Public API
    // ========================================================================

    /**
     * Sets the callback for file completion events.
     *
     * @param callback the completion callback
     */
    public void setFileCompletionCallback(BatchProcessor.FileCompletionCallback callback) {
        this.completionCallback = callback;
    }

    /**
     * Sets the BatchProcessor reference for translation support.
     *
     * @param batchProcessor the batch processor instance
     */
    public void setBatchProcessor(BatchProcessor batchProcessor) {
        this.batchProcessor = batchProcessor;
        LOGGER.debug("BatchProcessor reference set for translation support");
    }

    /**
     * Sets whether to export Word copies of transcriptions.
     *
     * @param enabled {@code true} to enable Word export
     */
    public void setExportWordCopy(boolean enabled) {
        this.exportWordCopy = enabled;
    }

    /**
     * Sets whether to export PDF copies of transcriptions.
     *
     * @param enabled {@code true} to enable PDF export
     */
    public void setExportPdfCopy(boolean enabled) {
        this.exportPdfCopy = enabled;
    }

    /**
     * Sets the error reporter for diagnostics.
     *
     * @param errorReporter the error reporter
     */
    public void setErrorReporter(ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }

    /**
     * Enables or disables adaptive scaling.
     *
     * @param enabled {@code true} to enable adaptive scaling
     */
    public void setAdaptiveScalingEnabled(boolean enabled) {
        // Adaptive scaling is handled by the dynamic thread pool
        LOGGER.debug("Adaptive scaling enabled: {} (dynamic pool model)", enabled);
    }

    /**
     * Returns whether adaptive scaling is enabled.
     *
     * @return {@code true} if adaptive scaling is enabled
     */
    public boolean isAdaptiveScalingEnabled() {
        return true;
    }

    /**
     * Returns current pool statistics.
     *
     * @return a {@link PoolStats} object
     */
    public PoolStats getPoolStats() {
        if (workerPool == null) {
            return new PoolStats(0, 0, 0, 0, 0);
        }
        return new PoolStats(
            workerPool.getPoolSize(),
            workerPool.getActiveCount(),
            workerPool.getQueue().size(),
            workerPool.getCompletedTaskCount(),
            workerPool.getMaximumPoolSize()
        );
    }

    /**
     * Processes a batch of files in parallel.
     *
     * @param items the list of files to process
     * @param processingConfig the audio processing configuration
     * @param transcriptionConfig the transcription configuration
     * @param maxParallel the maximum number of parallel tasks
     * @return a {@link CompletableFuture} containing the batch result
     */
    public CompletableFuture<ParallelBatchResult> processBatchParallel(
            List<BatchFileItem> items,
            ProcessingConfig processingConfig,
            TranscriptionConfig transcriptionConfig,
            int maxParallel) {

        // Defensive guard: recreate the worker pool if it was somehow torn down
        // (e.g. shutdown() called concurrently with a new batch starting).
        if (workerPool == null || workerPool.isShutdown()) {
            LOGGER.warn("workerPool was null or shutdown - creating emergency pool");
            int optimalThreads = calculateOptimalThreadCount(Math.min(maxParallel, items.size()));
            this.workerPool = createThreadPool(optimalThreads);
        }

        LOGGER.debug("workerPool state: poolSize={}, activeCount={}, queueSize={}, corePoolSize={}, maxPoolSize={}",
            workerPool.getPoolSize(),
            workerPool.getActiveCount(),
            workerPool.getQueue().size(),
            workerPool.getCorePoolSize(),
            workerPool.getMaximumPoolSize());

        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(
                new ParallelBatchResult(0, 0, 0, 0, false));
        }

        int effectiveParallel = calculateOptimalParallelism(maxParallel, items.size());
        LOGGER.info("Starting parallel batch: {} files, {} workers, GPU: {}",
            items.size(), effectiveParallel,
            gpuConfig.shouldUseGpu() ? "enabled" : "disabled");

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            List<CompletableFuture<FileResult>> futures = new ArrayList<>();
            cancelled = false;

            // Sort by priority (HIGH > NORMAL > LOW)
            List<BatchFileItem> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparingInt(i -> i.getPriority().ordinal()));

            // Submit files with staggered admission
            for (int i = 0; i < sorted.size(); i++) {
                LOGGER.debug("Processing file index: {} of {}", i, sorted.size());
                if (cancelled) break;

                if (i > 0 && i % effectiveParallel == 0) {
                    awaitAdmissionSlot(i);
                }

                BatchFileItem item = sorted.get(i);
                long queueEnteredMs = System.currentTimeMillis();
                LOGGER.debug("Submitting file: {}", item.getFileName());

                futures.add(processFile(
                    item,
                    processingConfig,
                    transcriptionConfig,
                    queueEnteredMs,
                    startTime
                ));
            }

            // Wait for all files to complete
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(FILE_TIMEOUT_HOURS, TimeUnit.HOURS);
            } catch (TimeoutException e) {
                LOGGER.error("Batch timed out after {} hours", FILE_TIMEOUT_HOURS);
                cancelled = true;
                workerPool.shutdownNow();
            } catch (Exception e) {
                LOGGER.error("Batch failed", e);
            }

            // Collect results
            int completed = 0, failed = 0;
            for (CompletableFuture<FileResult> f : futures) {
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    try {
                        FileResult r = f.get();
                        if (r.success) completed++;
                        else failed++;
                    } catch (Exception e) {
                        failed++;
                    }
                } else {
                    failed++;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            ParallelBatchResult result = new ParallelBatchResult(
                items.size(), completed, failed, duration, cancelled);

            LOGGER.info("Batch complete: {}/{} files in {}ms (GPU: {})",
                completed, items.size(), duration,
                gpuConfig.shouldUseGpu() ? "enabled" : "disabled");
            return result;

        }, coordinatorExecutor);
    }

    // ========================================================================
    //  Cancellation and Shutdown
    // ========================================================================

    /**
     * Cancels the current batch processing.
     */
    public void cancel() {
        cancelled = true;
        LOGGER.info("Cancelling parallel batch...");

        int killed = ProcessRunner.destroyAllActiveProcesses();
        if (killed > 0) {
            LOGGER.info("Forcibly terminated {} active subprocess(es)", killed);
        }

        if (workerPool != null) {
            workerPool.shutdownNow();
            try {
                if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Worker pool did not terminate within 5s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Shuts down all resources.
     */
    public void shutdown() {
        running = false;

        if (monitorService != null) {
            monitorService.shutdown();
            try {
                monitorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                monitorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        cancel();

        if (workerPool != null && !workerPool.isTerminated()) {
            workerPool.shutdownNow();
        }

        if (coordinatorExecutor != null && !coordinatorExecutor.isTerminated()) {
            coordinatorExecutor.shutdownNow();
        }

        LOGGER.info("ParallelProcessingManager shutdown complete. Total tasks: {}, Total time: {}ms",
            totalTasksProcessed.get(), totalProcessingTime.get());
    }

    /**
     * Returns recent timing reports for processed files.
     *
     * @return a list of {@link FileTimingReport} objects
     */
    public List<FileTimingReport> getRecentTimingReports() {
        return new ArrayList<>(recentTimingReports);
    }

    // ========================================================================
    //  GPU Status
    // ========================================================================

    /**
     * Returns whether GPU acceleration is enabled and available.
     *
     * @return {@code true} if GPU is enabled
     */
    public boolean isGpuEnabled() {
        return gpuConfig.shouldUseGpu();
    }

    /**
     * Returns whether GPU acceleration is available.
     *
     * @return {@code true} if GPU is available
     */
    public boolean isGpuAvailable() {
        return gpuConfig.isGpuAvailable();
    }

    /**
     * Returns the GPU name.
     *
     * @return the GPU name, or "No GPU" if not available
     */
    public String getGpuName() {
        if (gpuConfig.isGpuAvailable()) {
            return gpuConfig.getGpuName();
        }
        return "No GPU";
    }

    // ========================================================================
    //  Private Helpers
    // ========================================================================

    /**
     * Calculates optimal parallelism based on current system state.
     */
    private int calculateOptimalParallelism(int userParallel, int fileCount) {
        int optimal = calculateOptimalThreadCount(userParallel);
        optimal = Math.min(optimal, fileCount);
        optimal = Math.max(1, optimal);
        return optimal;
    }

    /**
     * Waits for an admission slot to prevent memory thundering herd.
     */
    private void awaitAdmissionSlot(int index) {
        if (index == 0) {
            return;
        }

        long waited = 0;
        try {
            // Back off while system memory is under pressure, instead of a flat
            // sleep that ignores actual resource conditions. This is what stops
            // the coordinator from admitting more heavy transcriptions (each a
            // multi-GB WhisperX subprocess) than the machine can actually hold,
            // which previously led to native OOM crashes under real concurrency.
            while (waited < MAX_ADMISSION_WAIT_MS) {
                double memUsed = getSystemMemoryUsedPercent();
                if (memUsed >= 0 && memUsed < MEMORY_PRESSURE_THRESHOLD) {
                    break;
                }
                if (memUsed >= 0) {
                    LOGGER.info("Admission stagger: system memory at {}% (threshold {}%), waiting...",
                        String.format("%.0f", memUsed), MEMORY_PRESSURE_THRESHOLD);
                }
                Thread.sleep(500);
                waited += 500;
            }
            if (waited >= MAX_ADMISSION_WAIT_MS) {
                LOGGER.warn("Admission stagger: proceeded after {}ms despite continued memory pressure", waited);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the system memory used percentage.
     *
     * @return the memory usage as a percentage, or {@code -1} if unavailable
     */
    private double getSystemMemoryUsedPercent() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                long total = sunBean.getTotalMemorySize();
                long free = sunBean.getFreeMemorySize();
                if (total > 0) {
                    return (total - free) * 100.0 / total;
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    /**
     * Creates a new transcription service instance.
     *
     * @return a new WhisperXTranscriptionService instance
     */
    private WhisperXTranscriptionService createModelInstance() {
        gpuConfig.detectGpu();

        WhisperXTranscriptionService instance = new WhisperXTranscriptionService(
            new DependencyManager(),
            timeEstimator,
            null,
            errorReporter
        );

        instance.setGpuEnabled(gpuConfig.shouldUseGpu());

        return instance;
    }

    /**
     * Process a single file asynchronously.
     */
    private CompletableFuture<FileResult> processFile(
            BatchFileItem item,
            ProcessingConfig processingConfig,
            TranscriptionConfig transcriptionConfig,
            long queueEnteredMs,
            long batchStartEpochMs) {

        LOGGER.debug("processFile entered for: {}", item.getFileName());

        return CompletableFuture.supplyAsync(() -> {
            LOGGER.debug("processFile started on worker for: {}", item.getFileName());
            File file = item.getFile();
            activeFileCount.incrementAndGet();
            File tempWav = null;
            WhisperXTranscriptionService model = null;
            TranscriptionResult transcriptionResult = null;

            try {
                long queueWaitMs = System.currentTimeMillis() - queueEnteredMs;
                LOGGER.debug("Processing {} (queue wait: {}ms, GPU: {})",
                    file.getName(), queueWaitMs,
                    gpuConfig.shouldUseGpu() ? "enabled" : "disabled");

                item.setStatus("PROCESSING");
                setItemProgress(item, 0.0);

                // Step 1: Preprocess audio
                long preprocessStart = System.currentTimeMillis();
                tempWav = preprocessFile(file, processingConfig);
                long preprocessMs = System.currentTimeMillis() - preprocessStart;
                setItemProgress(item, 0.3);

                // Step 2: Borrow model instance
                long modelAcquireStart = System.currentTimeMillis();
                model = borrowModel();
                long modelAcquireMs = System.currentTimeMillis() - modelAcquireStart;
                long modelAcquiredEpoch = System.currentTimeMillis();

                // Step 3: Transcribe
                long transcribeStart = System.currentTimeMillis();
                transcriptionResult = transcribeFile(tempWav, transcriptionConfig, item);
                long transcribeMs = System.currentTimeMillis() - transcribeStart;
                setItemProgress(item, 0.85);

                // ===== NEW: Apply translation if enabled =====
                TranscriptionResult finalResult = transcriptionResult;
                if (batchProcessor != null && batchProcessor.isTranslationAvailable() 
                        && transcriptionConfig.isTranslationEnabled()) {
                    LOGGER.info("🌐 Applying translation for {} to: {}", 
                        file.getName(), transcriptionConfig.getTranslationTargetLanguage());
                    
                    long translateStart = System.currentTimeMillis();
                    finalResult = batchProcessor.translateResult(transcriptionResult, transcriptionConfig);
                    long translateMs = System.currentTimeMillis() - translateStart;
                    
                    if (finalResult != transcriptionResult) {
                        LOGGER.info("🌐 Translation completed for {} in {}ms", 
                            file.getName(), translateMs);
                        setItemProgress(item, 0.90);
                    } else {
                        LOGGER.info("🌐 Translation skipped for {} (no changes)", file.getName());
                    }
                }

                capturePythonTimings(item, model);

                // Step 4: Save output (uses finalResult)
                long saveStart = System.currentTimeMillis();
                saveOutput(file, finalResult, transcriptionConfig, processingConfig);
                long saveMs = System.currentTimeMillis() - saveStart;

                // Step 5: Complete
                setItemProgress(item, 1.0);
                item.setStatus("COMPLETED");
                item.setErrorMessage(null);

                long totalMs = System.currentTimeMillis() - queueEnteredMs;
                long completedEpoch = System.currentTimeMillis();

                recordTimingReport(file, queueWaitMs, preprocessMs, modelAcquireMs,
                    transcribeMs, saveMs, totalMs, batchStartEpochMs,
                    queueEnteredMs, preprocessStart, modelAcquiredEpoch,
                    transcribeStart, saveStart, completedEpoch,
                    transcriptionConfig, item);

                totalTasksProcessed.incrementAndGet();
                totalProcessingTime.addAndGet(totalMs);

                if (completionCallback != null) {
                    completionCallback.onFileCompleted(item, true);
                }

                return new FileResult(file, true, "Success");

            } catch (Exception e) {
                LOGGER.error("Failed to process {}: {}", file.getName(), e.getMessage());
                item.setStatus("FAILED");
                item.setErrorMessage(e.getMessage());
                setItemProgress(item, 0.0);

                recordFailureReport(file, e, batchStartEpochMs, transcriptionConfig);

                if (completionCallback != null) {
                    completionCallback.onFileCompleted(item, false);
                }

                return new FileResult(file, false, e.getMessage());

            } finally {
                cleanupTempFile(tempWav);
                if (model != null) {
                    releaseModel(model);
                }
                activeFileCount.decrementAndGet();
                if (timeEstimator != null) {
                    timeEstimator.ensureFileTrackingCleared(file.getName());
                }
            }
        }, workerPool);
    }

    /**
     * Preprocesses a file by converting it to WAV.
     */
    private File preprocessFile(File file, ProcessingConfig config) throws Exception {
        AudioProcessor.ProcessingResult result;
        if (config.isNormalize() || config.getVolumeBoost() > 0) {
            result = audioProcessor.processAudioWithVolumeOptimization(file, config, p -> {});
        } else {
            result = audioProcessor.processAudioToWav(file, config, p -> {});
        }
        return new File(result.getOutputPath());
    }

    /**
     * Borrows a model instance from the pool.
     */
    private WhisperXTranscriptionService borrowModel() throws InterruptedException {
        WhisperXTranscriptionService model = modelInstancePool.borrow();
        model.setGpuEnabled(gpuConfig.shouldUseGpu());
        return model;
    }

    /**
     * Releases a model instance back to the pool.
     */
    private void releaseModel(WhisperXTranscriptionService model) {
        modelInstancePool.release(model);
    }

    /**
     * Transcribes a file using the borrowed model.
     */
    private TranscriptionResult transcribeFile(File wavFile,
                                               TranscriptionConfig config,
                                               BatchFileItem item) throws Exception {
        boolean gpuEnabled = gpuConfig.shouldUseGpu();
        LOGGER.debug("Transcribing {} with GPU: {}", wavFile.getName(), gpuEnabled ? "enabled" : "disabled");

        return transcriptionService.transcribe(
            wavFile.getAbsolutePath(),
            config,
            p -> setItemProgress(item, 0.3 + p * 0.65),
            0.0
        );
    }

    /**
     * Saves the transcription output.
     *
     * <p>This method now uses the finalResult which may have been translated.</p>
     *
     * @param originalFile the original audio file
     * @param result the transcription result (may be translated)
     * @param config the transcription configuration
     * @param processingConfig the processing configuration
     * @throws IOException if the file cannot be written
     */
    private void saveOutput(File originalFile,
                            TranscriptionResult result,
                            TranscriptionConfig config,
                            ProcessingConfig processingConfig) throws IOException {

        // ===== Note: result is already translated if translation was enabled =====
        // The translation is applied in processFile() before calling saveOutput()

        File out = outputWriter.save(
            originalFile.getName(),
            result,
            config,
            processingConfig.getOutputDirectory()
        );

        if (!out.exists() || out.length() == 0) {
            throw new IOException("Output file is missing or empty: " + out);
        }

        LOGGER.info("Saved output for {}: {}", originalFile.getName(), out);

        if (exportWordCopy) {
            exportWordCopy(originalFile, result, processingConfig);
        }
        if (exportPdfCopy) {
            exportPdfCopy(originalFile, result, processingConfig);
        }
    }

    /**
     * Captures Python-side timing data from the model.
     */
    private void capturePythonTimings(BatchFileItem item, WhisperXTranscriptionService model) {
        Map<String, Long> timings = model.getLastPythonStageTimingsMs();
        if (!timings.isEmpty()) {
            pythonStageTimings.put(item, timings);
        }
        double peakMem = model.getLastPythonPeakMemoryMb();
        double avgCpu = model.getLastPythonAvgCpuPercent();
        if (peakMem >= 0 || avgCpu >= 0) {
            pythonResourceUsage.put(item, new double[]{peakMem, avgCpu});
        }
    }

    /**
     * Records a timing report for a completed file.
     */
    private void recordTimingReport(File file, long queueWaitMs, long preprocessMs,
                                     long modelAcquireMs, long transcribeMs,
                                     long saveMs, long totalMs, long batchStartEpochMs,
                                     long queueEnteredMs, long preprocessStart,
                                     long modelAcquiredEpoch, long transcribeStart,
                                     long saveStart, long completedEpoch,
                                     TranscriptionConfig config, BatchFileItem item) {

        FileTimingReport report = new FileTimingReport(file.getName());
        report.setProcessingMode(config.isSkipSegmentation() ? "BASELINE" : "ADAPTIVE");
        report.setStage("queue_wait", queueWaitMs);
        report.setStage("preprocessing", preprocessMs);
        report.setStage("model_acquisition", modelAcquireMs);
        report.setStage("transcription_wall_clock", transcribeMs);
        report.setStage("output_saving", saveMs);
        report.setStage("total_pipeline", totalMs);

        // ===== NEW: Add translation timing if available =====
        // The translation timing is captured within the processFile method
        // but not explicitly tracked here. We could add a separate stage
        // if we wanted to track it.

        Map<String, Long> pythonTimings = pythonStageTimings.remove(item);
        if (pythonTimings != null) {
            pythonTimings.forEach(report::setStage);
        }

        double[] resources = pythonResourceUsage.remove(item);
        if (resources != null) {
            if (resources[0] >= 0) report.setPythonPeakMemoryMb(resources[0]);
            if (resources[1] >= 0) report.setPythonAvgCpuPercent(resources[1]);
        }

        report.setBatchStartEpochMs(batchStartEpochMs);
        report.setStageEpoch("queue_entered", queueEnteredMs);
        report.setStageEpoch("preprocess_start", preprocessStart);
        report.setStageEpoch("model_acquired", modelAcquiredEpoch);
        report.setStageEpoch("transcribe_start", transcribeStart);
        report.setStageEpoch("save_start", saveStart);
        report.setStageEpoch("completed", completedEpoch);

        boolean gpuUsed = gpuConfig.shouldUseGpu();
        report.setGpuInfo(gpuConfig, gpuUsed);

        Runtime rt = Runtime.getRuntime();
        report.setPeakHeapUsedMB((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));

        recordTimingReport(report);
    }

    /**
     * Records a failure report for a failed file.
     */
    private void recordFailureReport(File file, Exception e, long batchStartEpochMs,
                                      TranscriptionConfig config) {
        FileTimingReport report = new FileTimingReport(file.getName());
        report.setProcessingMode(config.isSkipSegmentation() ? "BASELINE" : "ADAPTIVE");
        report.setFailure(e);
        report.setBatchStartEpochMs(batchStartEpochMs);
        report.setStageEpoch("failed_at", System.currentTimeMillis());

        boolean gpuUsed = gpuConfig.shouldUseGpu();
        report.setGpuInfo(gpuConfig, gpuUsed);

        recordTimingReport(report);
    }

    /**
     * Records a timing report to the history.
     */
    private synchronized void recordTimingReport(FileTimingReport report) {
        recentTimingReports.add(0, report);
        while (recentTimingReports.size() > MAX_REPORTS) {
            recentTimingReports.remove(recentTimingReports.size() - 1);
        }
    }

    /**
     * Exports a Word copy of the transcription.
     */
    private void exportWordCopy(File originalFile, TranscriptionResult result,
                                ProcessingConfig config) {
        try {
            String baseName = getBaseName(originalFile.getName());
            String wordPath = Paths.get(config.getOutputDirectory(), baseName + ".docx").toString();
            outputWriter.exportToWord(result, wordPath);
            LOGGER.info("Saved Word copy: {}", wordPath);
        } catch (IOException e) {
            LOGGER.warn("Failed to save Word copy for {}: {}", originalFile.getName(), e.getMessage());
        }
    }

    /**
     * Exports a PDF copy of the transcription.
     */
    private void exportPdfCopy(File originalFile, TranscriptionResult result,
                               ProcessingConfig config) {
        try {
            String baseName = getBaseName(originalFile.getName());
            String pdfPath = Paths.get(config.getOutputDirectory(), baseName + ".pdf").toString();
            File pdfFile = outputWriter.exportToPDF(result, pdfPath);
            if (pdfFile.exists() && pdfFile.length() > 0) {
                LOGGER.info("Saved PDF copy: {}", pdfFile);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save PDF copy for {}: {}", originalFile.getName(), e.getMessage());
        }
    }

    /**
     * Cleans up a temporary file.
     */
    private void cleanupTempFile(File tempWav) {
        if (tempWav == null || !tempWav.exists()) return;
        try {
            Files.delete(tempWav.toPath());
            LOGGER.debug("Cleaned up temp file: {}", tempWav.getName());
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup temp file {}: {}", tempWav.getName(), e.getMessage());
        }
    }

    /**
     * Sets the progress of a batch item.
     */
    private void setItemProgress(BatchFileItem item, double value) {
        item.setProgress(Math.min(1.0, Math.max(0.0, value)));
        item.setIndividualProgress(item.getProgress());
    }

    /**
     * Returns the base name of a file (without extension).
     */
    private String getBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Retry a failed file.
     */
    public BatchFileItem retryFile(BatchFileItem item,
                                   ProcessingConfig processingConfig,
                                   TranscriptionConfig transcriptionConfig) throws Exception {
        if (!"FAILED".equals(item.getStatus())) {
            return item;
        }

        LOGGER.info("Retrying failed file: {}", item.getFileName());

        item.setStatus("PENDING");
        item.setProgress(0.0);
        item.setErrorMessage(null);

        CompletableFuture<FileResult> future = processFile(
            item,
            processingConfig,
            transcriptionConfig,
            System.currentTimeMillis(),
            System.currentTimeMillis()
        );

        try {
            FileResult result = future.get(FILE_TIMEOUT_HOURS, TimeUnit.HOURS);
            if (result.success) {
                LOGGER.info("Retry successful for: {}", item.getFileName());
                return item;
            } else {
                throw new Exception("Retry failed: " + result.message);
            }
        } catch (TimeoutException e) {
            throw new Exception("Retry timed out after " + FILE_TIMEOUT_HOURS + " hours");
        }
    }

    // ========================================================================
    //  Inner Classes
    // ========================================================================

    /**
     * Internal file result wrapper.
     */
    private static class FileResult {
        final File file;
        final boolean success;
        final String message;

        FileResult(File file, boolean success, String message) {
            this.file = file;
            this.success = success;
            this.message = message;
        }
    }

    /**
     * Parallel batch result with statistics.
     */
    public static class ParallelBatchResult {
        private final int total;
        private final int completed;
        private final int failed;
        private final long durationMillis;
        private final boolean cancelled;

        public ParallelBatchResult(int total, int completed, int failed,
                                   long durationMillis, boolean cancelled) {
            this.total = total;
            this.completed = completed;
            this.failed = failed;
            this.durationMillis = durationMillis;
            this.cancelled = cancelled;
        }

        public int getTotal() { return total; }
        public int getCompleted() { return completed; }
        public int getFailed() { return failed; }
        public int getPending() { return total - completed - failed; }
        public long getDurationMillis() { return durationMillis; }
        public boolean wasCancelled() { return cancelled; }
    }

    /**
     * Pool statistics.
     */
    public static class PoolStats {
        public final int poolSize;
        public final int activeCount;
        public final int queueSize;
        public final long completedTasks;
        public final int maxPoolSize;

        public PoolStats(int poolSize, int activeCount, int queueSize, long completedTasks, int maxPoolSize) {
            this.poolSize = poolSize;
            this.activeCount = activeCount;
            this.queueSize = queueSize;
            this.completedTasks = completedTasks;
            this.maxPoolSize = maxPoolSize;
        }

        public double getUtilization() {
            return maxPoolSize > 0 ? (double) activeCount / maxPoolSize : 0;
        }
    }

    /**
     * Named thread factory for worker threads.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}