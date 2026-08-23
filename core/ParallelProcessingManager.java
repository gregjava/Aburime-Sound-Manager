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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Simplified parallel processing manager with GPU support.
 * 
 * <p>Key simplifications vs. original:</p>
 * <ul>
 *   <li>Fixed thread pool with staggered submission instead of dynamic resizing</li>
 *   <li>BlockingQueue-based model pool instead of custom semaphore + instance tracking</li>
 *   <li>No resource monitor thread - memory pressure is handled via admission staggering</li>
 *   <li>Cleaner cancellation semantics</li>
 *   <li>GPU acceleration support via GpuConfig</li>
 * </ul>
 */
public class ParallelProcessingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelProcessingManager.class);

    // ===== Configuration =====
    private static final int MODEL_POOL_CAP = 4;
    private static final long ADMISSION_STAGGER_MS = 3000;
    private static final double MEMORY_PRESSURE_THRESHOLD = 80.0;
    private static final long MAX_ADMISSION_WAIT_MS = 30000;
    private static final long FILE_TIMEOUT_HOURS = 24;

    // ===== Dependencies =====
    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final Consumer<String> logger;
    private final TimeLeftEstimator timeEstimator;
    private final TranscriptionOutputWriter outputWriter;
    private final GpuConfig gpuConfig = GpuConfig.getInstance();

    // ===== Threading =====
    private final ExecutorService workerPool;
    private final BlockingQueue<WhisperXTranscriptionService> modelPool;
    private final int maxConcurrentFiles;
    private volatile boolean cancelled = false;

    // ===== Status =====
    private final AtomicInteger activeFileCount = new AtomicInteger(0);
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

    public ParallelProcessingManager(AudioProcessor audioProcessor,
                                     WhisperXTranscriptionService transcriptionService,
                                     Consumer<String> logger,
                                     TimeLeftEstimator timeEstimator) {
        this(audioProcessor, transcriptionService, logger, timeEstimator, 
             Runtime.getRuntime().availableProcessors());
    }

    public ParallelProcessingManager(AudioProcessor audioProcessor,
                                     WhisperXTranscriptionService transcriptionService,
                                     Consumer<String> logger,
                                     TimeLeftEstimator timeEstimator,
                                     int maxConcurrentFiles) {
        this.audioProcessor = audioProcessor;
        this.transcriptionService = transcriptionService;
        this.logger = logger;
        this.timeEstimator = timeEstimator;
        this.maxConcurrentFiles = Math.max(1, maxConcurrentFiles);
        this.outputWriter = new TranscriptionOutputWriter();

        // Initialize GPU detection
        gpuConfig.detectGpu();
        if (gpuConfig.isGpuAvailable()) {
            LOGGER.info("✅ GPU available: {}", gpuConfig.getGpuSummary());
        } else {
            LOGGER.info("ℹ️ No GPU detected — running on CPU mode");
        }

        // Create worker pool with fixed size
        this.workerPool = Executors.newFixedThreadPool(
            this.maxConcurrentFiles,
            new NamedThreadFactory("Parallel-Worker")
        );

        // Pre-fill model pool
        int poolSize = Math.min(this.maxConcurrentFiles, MODEL_POOL_CAP);
        this.modelPool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            try {
                modelPool.offer(createModelInstance());
            } catch (Exception e) {
                LOGGER.warn("Failed to pre-create model instance {}: {}", i, e.getMessage());
            }
        }

        LOGGER.info("ParallelProcessingManager initialized: {} workers, {} model instances, GPU: {}",
            this.maxConcurrentFiles, modelPool.size(), 
            gpuConfig.isGpuAvailable() ? "available" : "not available");
    }

    // ========================================================================
    //  Public API
    // ========================================================================

    public void setFileCompletionCallback(BatchProcessor.FileCompletionCallback callback) {
        this.completionCallback = callback;
    }

    public void setExportWordCopy(boolean enabled) {
        this.exportWordCopy = enabled;
    }

    public void setExportPdfCopy(boolean enabled) {
        this.exportPdfCopy = enabled;
    }

    public void setErrorReporter(ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }

    public void setAdaptiveScalingEnabled(boolean enabled) {
        // Adaptive scaling is now handled by the fixed thread pool + staggered admission
        // This is kept for API compatibility but does nothing in the simplified model
        LOGGER.debug("Adaptive scaling enabled: {} (fixed pool model)", enabled);
    }

    public boolean isAdaptiveScalingEnabled() {
        // In the simplified model, adaptive scaling is always "enabled" via staggered admission
        return true;
    }

    /**
     * Process a batch of files in parallel.
     * 
     * @param items the files to process
     * @param processingConfig audio processing configuration
     * @param transcriptionConfig transcription configuration
     * @param maxParallel max concurrent files (capped by constructor value)
     * @return a future containing the batch result
     */
    public CompletableFuture<ParallelBatchResult> processBatchParallel(
            List<BatchFileItem> items,
            ProcessingConfig processingConfig,
            TranscriptionConfig transcriptionConfig,
            int maxParallel) {

        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(
                new ParallelBatchResult(0, 0, 0, 0, false));
        }

        int effectiveParallel = Math.min(maxParallel, maxConcurrentFiles);
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

            // Submit files with staggered admission to avoid memory thundering herd
            for (int i = 0; i < sorted.size(); i++) {
                if (cancelled) break;

                // Stagger admission to let memory pressure be visible
                if (i > 0 && i % effectiveParallel == 0) {
                    awaitAdmissionSlot(i);
                }

                BatchFileItem item = sorted.get(i);
                long queueEnteredMs = System.currentTimeMillis();
                futures.add(processFile(
                    item,
                    processingConfig,
                    transcriptionConfig,
                    queueEnteredMs,
                    startTime
                ));
            }

            // Wait for all files to complete (or timeout)
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

        }, workerPool);
    }

    /**
     * Cancel the current batch.
     */
    public void cancel() {
        cancelled = true;
        LOGGER.info("Cancelling parallel batch...");
        
        // Kill any active subprocesses
        int killed = ProcessRunner.destroyAllActiveProcesses();
        if (killed > 0) {
            LOGGER.info("Forcibly terminated {} active subprocess(es)", killed);
        }

        // Shutdown worker pool
        workerPool.shutdownNow();
        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Worker pool did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shutdown all resources.
     */
    public void shutdown() {
        cancel();
        LOGGER.info("ParallelProcessingManager shutdown complete");
    }

    public List<FileTimingReport> getRecentTimingReports() {
        return new ArrayList<>(recentTimingReports);
    }

    // ========================================================================
    //  File Processing
    // ========================================================================

    private CompletableFuture<FileResult> processFile(
            BatchFileItem item,
            ProcessingConfig processingConfig,
            TranscriptionConfig transcriptionConfig,
            long queueEnteredMs,
            long batchStartEpochMs) {

        return CompletableFuture.supplyAsync(() -> {
            File file = item.getFile();
            activeFileCount.incrementAndGet();
            File tempWav = null;
            WhisperXTranscriptionService model = null;

            try {
                long queueWaitMs = System.currentTimeMillis() - queueEnteredMs;
                LOGGER.debug("Processing {} (queue wait: {}ms, GPU: {})", 
                    file.getName(), queueWaitMs,
                    gpuConfig.shouldUseGpu() ? "enabled" : "disabled");

                // Update item status
                item.setStatus("PROCESSING");
                setItemProgress(item, 0.0);

                // === Step 1: Preprocess audio ===
                long preprocessStart = System.currentTimeMillis();
                tempWav = preprocessFile(file, processingConfig);
                long preprocessMs = System.currentTimeMillis() - preprocessStart;
                setItemProgress(item, 0.3);

                // === Step 2: Borrow model instance ===
                long modelAcquireStart = System.currentTimeMillis();
                model = borrowModel();
                long modelAcquireMs = System.currentTimeMillis() - modelAcquireStart;
                long modelAcquiredEpoch = System.currentTimeMillis();

                // === Step 3: Transcribe ===
                long transcribeStart = System.currentTimeMillis();
                TranscriptionResult result = transcribeFile(tempWav, transcriptionConfig, item);
                long transcribeMs = System.currentTimeMillis() - transcribeStart;
                setItemProgress(item, 0.95);

                // Capture Python-side timing data
                capturePythonTimings(item, model);

                // === Step 4: Save output ===
                long saveStart = System.currentTimeMillis();
                saveOutput(file, result, transcriptionConfig, processingConfig);
                long saveMs = System.currentTimeMillis() - saveStart;

                // === Step 5: Complete ===
                setItemProgress(item, 1.0);
                item.setStatus("COMPLETED");
                item.setErrorMessage(null);

                long totalMs = System.currentTimeMillis() - queueEnteredMs;
                long completedEpoch = System.currentTimeMillis();

                // Record timing report with GPU info
                recordTimingReport(file, queueWaitMs, preprocessMs, modelAcquireMs,
                    transcribeMs, saveMs, totalMs, batchStartEpochMs,
                    queueEnteredMs, preprocessStart, modelAcquiredEpoch,
                    transcribeStart, saveStart, completedEpoch,
                    transcriptionConfig, item);

                // Notify completion
                if (completionCallback != null) {
                    completionCallback.onFileCompleted(item, true);
                }

                return new FileResult(file, true, "Success");

            } catch (Exception e) {
                LOGGER.error("Failed to process {}: {}", file.getName(), e.getMessage());
                item.setStatus("FAILED");
                item.setErrorMessage(e.getMessage());
                setItemProgress(item, 0.0);

                // Record failure report with GPU info
                recordFailureReport(file, e, batchStartEpochMs, transcriptionConfig);

                if (completionCallback != null) {
                    completionCallback.onFileCompleted(item, false);
                }

                return new FileResult(file, false, e.getMessage());

            } finally {
                // Clean up
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

    // ========================================================================
    //  Processing Steps
    // ========================================================================

    private File preprocessFile(File file, ProcessingConfig config) throws Exception {
        AudioProcessor.ProcessingResult result;
        if (config.isNormalize() || config.getVolumeBoost() > 0) {
            result = audioProcessor.processAudioWithVolumeOptimization(file, config, p -> {});
        } else {
            result = audioProcessor.processAudioToWav(file, config, p -> {});
        }
        return new File(result.getOutputPath());
    }

    private WhisperXTranscriptionService borrowModel() throws InterruptedException {
        WhisperXTranscriptionService model = modelPool.poll(30, TimeUnit.SECONDS);
        if (model == null) {
            throw new InterruptedException("No model instance available");
        }
        return model;
    }

    private void releaseModel(WhisperXTranscriptionService model) {
        modelPool.offer(model);
    }

    private TranscriptionResult transcribeFile(File wavFile,
                                               TranscriptionConfig config,
                                               BatchFileItem item) throws Exception {
        // Check GPU status before transcription
        boolean gpuEnabled = gpuConfig.shouldUseGpu();
        LOGGER.debug("Transcribing {} with GPU: {}", wavFile.getName(), gpuEnabled ? "enabled" : "disabled");
        
        return transcriptionService.transcribe(
            wavFile.getAbsolutePath(),
            config,
            p -> setItemProgress(item, 0.3 + p * 0.65),
            0.0
        );
    }

    private void saveOutput(File originalFile,
                            TranscriptionResult result,
                            TranscriptionConfig config,
                            ProcessingConfig processingConfig) throws IOException {
        
        File out = outputWriter.save(
            originalFile.getName(),
            result,
            config,
            processingConfig.getOutputDirectory()
        );

        // Ensure output actually exists
        if (!out.exists() || out.length() == 0) {
            throw new IOException("Output file is missing or empty: " + out);
        }

        LOGGER.info("Saved output for {}: {}", originalFile.getName(), out);

        // Optional exports
        if (exportWordCopy) {
            exportWordCopy(originalFile, result, processingConfig);
        }
        if (exportPdfCopy) {
            exportPdfCopy(originalFile, result, processingConfig);
        }
    }

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

    // ========================================================================
    //  Timing & Reporting
    // ========================================================================

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

        // Python-side stages
        Map<String, Long> pythonTimings = pythonStageTimings.remove(item);
        if (pythonTimings != null) {
            pythonTimings.forEach(report::setStage);
        }

        // Resource usage
        double[] resources = pythonResourceUsage.remove(item);
        if (resources != null) {
            if (resources[0] >= 0) report.setPythonPeakMemoryMb(resources[0]);
            if (resources[1] >= 0) report.setPythonAvgCpuPercent(resources[1]);
        }

        // Wall-clock timeline
        report.setBatchStartEpochMs(batchStartEpochMs);
        report.setStageEpoch("queue_entered", queueEnteredMs);
        report.setStageEpoch("preprocess_start", preprocessStart);
        report.setStageEpoch("model_acquired", modelAcquiredEpoch);
        report.setStageEpoch("transcribe_start", transcribeStart);
        report.setStageEpoch("save_start", saveStart);
        report.setStageEpoch("completed", completedEpoch);

        // GPU Info
        boolean gpuUsed = gpuConfig.shouldUseGpu();
        report.setGpuInfo(gpuConfig, gpuUsed);
        
        // Log GPU status for this file
        if (gpuUsed) {
            LOGGER.debug("File {} processed with GPU: {}", file.getName(), gpuConfig.getGpuName());
        }

        // JVM-side resource snapshot
        Runtime rt = Runtime.getRuntime();
        report.setPeakHeapUsedMB((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));

        recordTimingReport(report);
    }

    private void recordFailureReport(File file, Exception e, long batchStartEpochMs,
                                      TranscriptionConfig config) {
        FileTimingReport report = new FileTimingReport(file.getName());
        report.setProcessingMode(config.isSkipSegmentation() ? "BASELINE" : "ADAPTIVE");
        report.setFailure(e);
        report.setBatchStartEpochMs(batchStartEpochMs);
        report.setStageEpoch("failed_at", System.currentTimeMillis());
        
        // Include GPU info even for failures
        boolean gpuUsed = gpuConfig.shouldUseGpu();
        report.setGpuInfo(gpuConfig, gpuUsed);
        
        recordTimingReport(report);
    }

    private synchronized void recordTimingReport(FileTimingReport report) {
        recentTimingReports.add(0, report);
        while (recentTimingReports.size() > MAX_REPORTS) {
            recentTimingReports.remove(recentTimingReports.size() - 1);
        }
    }

    // ========================================================================
    //  Resource Management
    // ========================================================================

    private void awaitAdmissionSlot(int index) {
        if (index == 0) return;

        try {
            Thread.sleep(ADMISSION_STAGGER_MS);

            long waited = 0;
            while (waited < MAX_ADMISSION_WAIT_MS) {
                double memPct = getSystemMemoryUsedPercent();
                if (memPct < 0 || memPct < MEMORY_PRESSURE_THRESHOLD) {
                    return;
                }
                LOGGER.info("Delaying admission - memory at {}% (threshold {}%)",
                    String.format("%.0f", memPct), (int) MEMORY_PRESSURE_THRESHOLD);
                Thread.sleep(2000);
                waited += 2000;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

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
        } catch (Exception ignored) { }
        return -1;
    }

    private void cleanupTempFile(File tempWav) {
        if (tempWav == null || !tempWav.exists()) return;
        try {
            Files.delete(tempWav.toPath());
            LOGGER.debug("Cleaned up temp file: {}", tempWav.getName());
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup temp file {}: {}", tempWav.getName(), e.getMessage());
        }
    }

    private void setItemProgress(BatchFileItem item, double value) {
        item.setProgress(Math.min(1.0, Math.max(0.0, value)));
        item.setIndividualProgress(item.getProgress());
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private WhisperXTranscriptionService createModelInstance() {
        // Ensure GPU detection is initialized
        gpuConfig.detectGpu();
        
        WhisperXTranscriptionService instance = new WhisperXTranscriptionService(
            new DependencyManager(),
            timeEstimator,
            null,
            errorReporter
        );
        
        // Set GPU preference based on config
        instance.setGpuEnabled(gpuConfig.shouldUseGpu());
        
        return instance;
    }

    private String getBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ========================================================================
    //  Inner Classes
    // ========================================================================

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