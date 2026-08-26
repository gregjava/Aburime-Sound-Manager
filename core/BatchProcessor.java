/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.*;
import audiomanager.ui.AppState;
import audiomanager.ui.ConfigurationPanel;
import audiomanager.util.PreferenceManager;
import audiomanager.util.SoundManager;
import audiomanager.util.TimeLeftEstimator;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Enhanced batch processor with performance optimizations for audio transcription.
 *
 * <p>This class orchestrates the processing of multiple audio files in batch mode,
 * providing features such as:
 * <ul>
 *   <li>Dynamic batch size adjustment based on file sizes</li>
 *   <li>Intelligent file ordering for optimal throughput</li>
 *   <li>Progress tracking with ETA calculations</li>
 *   <li>Performance metrics and reporting</li>
 *   <li>Resource-aware processing</li>
 *   <li>Retry logic for failed files</li>
 *   <li>State persistence for crash recovery</li>
 *   <li>GPU acceleration support</li>
 *   <li><b>Post-transcription translation</b> with configurable target language</li>
 * </ul>
 *
 * <p>The batch processor uses a {@link ParallelProcessingManager} to handle
 * concurrent file processing, with automatic thread pool sizing based on
 * system resources.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see ParallelProcessingManager
 * @see BatchFileItem
 * @see BatchResult
 * @see BatchStatistics
 */
public class BatchProcessor implements SegmentProgressListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchProcessor.class);

    // ===== Configuration Constants =====
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 1000;
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 5000;
    private static final double MEMORY_THRESHOLD = 0.85;
    private static final int LARGE_FILE_THRESHOLD_MB = 100;

    // ===== Dependencies =====
    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final TimeLeftEstimator timeEstimator;
    private final PreferenceManager preferenceManager;
    private final Consumer<String> logger;
    private final ParallelProcessingManager parallelManager;
    private final AppState appState = AppState.getInstance();
    private ErrorReporter errorReporter;

    // ===== NEW: Translation Service =====
    private TranslationService translationService;

    // ===== Performance Tracking =====
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final AtomicLong totalFileSizeBytes = new AtomicLong(0);
    private final Map<String, Long> stageTimings = new ConcurrentHashMap<>();
    private final List<BatchPerformanceRecord> performanceHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_PERFORMANCE_HISTORY = 100;

    // ===== Configuration =====
    private ConfigurationPanel configurationPanel;
    private volatile boolean cancelled = false;
    private ObservableList<BatchFileItem> currentItems;
    private boolean autoRemoveCompleted = false;

    // ===== Batch State =====
    private final AtomicInteger completedFilesCount = new AtomicInteger(0);
    private final AtomicInteger failedFilesCount = new AtomicInteger(0);
    private volatile int totalFilesInBatch = 0;
    private volatile long batchStartTime = 0;
    private volatile long totalBatchDuration = 0;
    private volatile boolean batchInProgress = false;
    private volatile Instant batchStartInstant = null;
    private volatile double totalBatchProgress = 0.0;

    // ===== Callbacks =====
    private Consumer<BatchStatistics> statisticsCallback;
    private Consumer<BatchFileItem> fileCompletedCallback;
    private Consumer<Boolean> isProcessingCallback;
    private FileCompletionCallback completionCallback;

    // ===== Persistence =====
    private static final String STATE_FILE_NAME = "batch_state.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Path stateFilePath;
    private final SimpleBooleanProperty isRunning = new SimpleBooleanProperty(false);

    // ===== Process Name Constants =====
    private static final List<String> TRANSCRIPTION_PROCESSES = List.of(
            "audio_enhancement", "audio_preprocessing",
            "transcription_base", "saving_transcription", "file_cleanup");
    private static final List<String> AUDIO_ONLY_PROCESSES = List.of(
            "audio_enhancement", "file_cleanup");

    // ========================================================================
    //  Interfaces
    // ========================================================================

    /**
     * Callback interface for file completion events.
     */
    public interface FileCompletionCallback {
        /**
         * Called when a file has finished processing.
         *
         * @param item the batch file item that was processed
         * @param wasSuccessful {@code true} if the file was processed successfully
         */
        void onFileCompleted(BatchFileItem item, boolean wasSuccessful);
    }

    // ========================================================================
    //  Construction
    // ========================================================================

    /**
     * Constructs a new BatchProcessor with the specified dependencies.
     *
     * @param audioProcessor the audio processor for file conversion
     * @param transcriptionService the transcription service
     * @param timeEstimator the time estimator for progress tracking
     * @param preferenceManager the preference manager for user settings
     * @param logger a consumer for log messages
     * @param completionCallback callback for file completion events
     * @param items the list of batch items to process
     * @param errorReporter the error reporter for diagnostics
     */
    public BatchProcessor(AudioProcessor audioProcessor,
                          WhisperXTranscriptionService transcriptionService,
                          TimeLeftEstimator timeEstimator,
                          PreferenceManager preferenceManager,
                          Consumer<String> logger,
                          FileCompletionCallback completionCallback,
                          ObservableList<BatchFileItem> items,
                          ErrorReporter errorReporter) {
        this.audioProcessor = audioProcessor;
        this.transcriptionService = transcriptionService;
        this.timeEstimator = timeEstimator;
        this.preferenceManager = preferenceManager;
        this.logger = logger;
        this.completionCallback = completionCallback;
        this.currentItems = items;
        this.errorReporter = errorReporter;

        // Initialize parallel manager with simplified design
        this.parallelManager = new ParallelProcessingManager(
                audioProcessor,
                transcriptionService,
                logger,
                timeEstimator
        );
        this.parallelManager.setErrorReporter(errorReporter);

        // Wire completion callback from parallel manager to our callback chain
        this.parallelManager.setFileCompletionCallback((item, success) -> {
            if (success) {
                SoundManager.playComplete();
                completedFilesCount.incrementAndGet();
                appState.setStatus("File Completed", "✅ " + item.getFileName());
            } else {
                SoundManager.playError();
                failedFilesCount.incrementAndGet();
                appState.setStatus("File Failed", "❌ " + item.getFileName());
            }
            updateCompletionCounts();
            updateProgress();
            saveBatchState();
            if (completionCallback != null) {
                completionCallback.onFileCompleted(item, success);
            }
        });

        // Setup state persistence
        Path appDataDir = Paths.get(System.getProperty("user.home"), ".audiomanager");
        try {
            Files.createDirectories(appDataDir);
            stateFilePath = appDataDir.resolve(STATE_FILE_NAME);
        } catch (IOException e) {
            LOGGER.error("Failed to create state directory", e);
        }

        // Setup progress update timer
        setupProgressUpdater();

        LOGGER.info("BatchProcessor initialized with enhanced parallel manager");
    }

    // ========================================================================
    //  Configuration
    // ========================================================================

    /**
     * Sets the configuration panel for accessing user settings.
     *
     * @param configPanel the configuration panel
     */
    public void setConfigurationPanel(ConfigurationPanel configPanel) {
        this.configurationPanel = configPanel;
    }

    /**
     * Enables or disables adaptive scaling for parallel processing.
     *
     * @param enabled {@code true} to enable adaptive scaling
     */
    public void setAdaptiveScalingEnabled(boolean enabled) {
        parallelManager.setAdaptiveScalingEnabled(enabled);
    }

    /**
     * Returns whether adaptive scaling is enabled.
     *
     * @return {@code true} if adaptive scaling is enabled
     */
    public boolean isAdaptiveScalingEnabled() {
        return parallelManager.isAdaptiveScalingEnabled();
    }

    /**
     * Sets whether to export Word copies of transcriptions.
     *
     * @param enabled {@code true} to enable Word export
     */
    public void setExportWordCopy(boolean enabled) {
        parallelManager.setExportWordCopy(enabled);
    }

    /**
     * Sets whether to export PDF copies of transcriptions.
     *
     * @param enabled {@code true} to enable PDF export
     */
    public void setExportPdfCopy(boolean enabled) {
        parallelManager.setExportPdfCopy(enabled);
    }

    /**
     * Sets whether to automatically remove completed files from the queue.
     *
     * @param autoRemoveCompleted {@code true} to auto-remove completed files
     */
    public void setAutoRemoveCompleted(boolean autoRemoveCompleted) {
        this.autoRemoveCompleted = autoRemoveCompleted;
    }

    /**
     * Sets the callback for batch statistics updates.
     *
     * @param callback the statistics callback
     */
    public void setStatisticsCallback(Consumer<BatchStatistics> callback) {
        this.statisticsCallback = callback;
    }

    /**
     * Sets the callback for processing status changes.
     *
     * @param callback the processing status callback
     */
    public void setIsProcessingCallback(Consumer<Boolean> callback) {
        this.isProcessingCallback = callback;
    }

    /**
     * Sets the callback for file completion events.
     *
     * @param callback the file completion callback
     */
    public void setFileCompletedCallback(Consumer<BatchFileItem> callback) {
        this.fileCompletedCallback = callback;
    }

    // ===== NEW: Translation Service Configuration =====

    /**
     * Sets the translation service for post-transcription translation.
     *
     * <p>When configured, transcripts will be translated to the target language
     * specified in {@link TranscriptionConfig} after transcription completes.</p>
     *
     * @param translationService the translation service to use, or {@code null} to disable
     */
    public void setTranslationService(TranslationService translationService) {
        this.translationService = translationService;
        if (translationService != null) {
            LOGGER.info("🌐 Translation service configured and enabled");
        } else {
            LOGGER.info("🌐 Translation service disabled");
        }
    }

    /**
     * Returns whether translation is available.
     *
     * @return {@code true} if a translation service is configured
     */
    public boolean isTranslationAvailable() {
        return translationService != null;
    }

    // ========================================================================
    //  Batch Processing (Enhanced)
    // ========================================================================

    /**
     * Processes a batch of files asynchronously.
     *
     * <p>This method:
     * <ol>
     *   <li>Optimises file ordering for better throughput</li>
     *   <li>Initialises batch state and progress tracking</li>
     *   <li>Calculates optimal parallelism based on system resources</li>
     *   <li>Delegates processing to the parallel manager</li>
     *   <li>Records performance metrics</li>
     * </ol>
     *
     * @param items the list of files to process
     * @param processingConfig the audio processing configuration
     * @param transcriptionConfig the transcription configuration
     * @param maxParallel the maximum number of parallel tasks
     * @return a {@link CompletableFuture} containing the batch result
     * @throws IllegalStateException if a batch is already in progress
     */
    public CompletableFuture<BatchResult> processBatch(ObservableList<BatchFileItem> items,
                                                       ProcessingConfig processingConfig,
                                                       TranscriptionConfig transcriptionConfig,
                                                       int maxParallel) {
        if (isProcessing()) {
            throw new IllegalStateException("Batch already in progress.");
        }

        // Initialize state
        Platform.runLater(() -> isRunning.set(true));
        
        // Optimize file ordering for better throughput
        List<BatchFileItem> optimizedItems = optimizeFileOrdering(items);
        
        // Initialize batch state
        initializeBatchState(optimizedItems);
        cancelled = false;
        batchInProgress = true;
        batchStartInstant = Instant.now();

        if (isProcessingCallback != null) {
            Platform.runLater(() -> isProcessingCallback.accept(true));
        }

        // Calculate total file size for progress tracking
        totalFileSizeBytes.set(0);
        for (BatchFileItem item : optimizedItems) {
            totalFileSizeBytes.addAndGet(item.getFile().length());
        }
        logger.accept("📊 Total batch size: " + formatFileSize(totalFileSizeBytes.get()));

        // Setup time estimator
        if (timeEstimator != null) {
            timeEstimator.startBatch();
            for (BatchFileItem item : optimizedItems) {
                double mb = item.getFile().length() / (1024.0 * 1024.0);
                List<String> procs = transcriptionConfig.isEnabled()
                        ? TRANSCRIPTION_PROCESSES : AUDIO_ONLY_PROCESSES;
                String model = transcriptionConfig.isEnabled()
                        ? transcriptionConfig.getModel() : "base";
                timeEstimator.addQueuedFile(item.getFileName(), mb, model, procs);
            }
        }

        // Calculate optimal parallelism
        int optimalParallel = calculateOptimalParallelism(maxParallel, optimizedItems.size());
        logger.accept("⚡ Using " + optimalParallel + " parallel workers (GPU: " + 
                     (parallelManager.isGpuEnabled() ? "enabled" : "disabled") + ")");

        LOGGER.info("Starting batch: {} files, {} workers, total size: {}", 
            totalFilesInBatch, optimalParallel, formatFileSize(totalFileSizeBytes.get()));

        // Reset progress tracking
        appState.resetForNewBatch(totalFilesInBatch);
        appState.setStatus("Processing", "🚀 Starting batch processing...");

        // Delegate to parallel manager with optimized parameters
        return parallelManager.processBatchParallel(optimizedItems, processingConfig, transcriptionConfig, optimalParallel)
                .thenApply(parallelResult -> {
                    totalBatchDuration = parallelResult.getDurationMillis();
                    totalProcessingTime.addAndGet(totalBatchDuration);
                    
                    // Record performance metrics
                    recordPerformance(optimizedItems, processingConfig, transcriptionConfig, parallelResult);
                    
                    BatchResult result = new BatchResult(
                            parallelResult.getTotal(),
                            parallelResult.getCompleted(),
                            parallelResult.getFailed(),
                            parallelResult.getDurationMillis(),
                            parallelResult.wasCancelled()
                    );
                    
                    // Log performance summary
                    logPerformanceSummary(result);
                    
                    return result;
                })
                .exceptionally(throwable -> {
                    LOGGER.error("Batch processing failed", throwable);
                    logger.accept("❌ Batch processing failed: " + throwable.getMessage());
                    if (errorReporter != null) {
                        errorReporter.reportError(throwable, "Batch processing failed");
                    }
                    int completed = completedFilesCount.get();
                    return new BatchResult(totalFilesInBatch, completed,
                            totalFilesInBatch - completed, 0, cancelled);
                })
                .whenComplete((result, throwable) -> {
                    if (result != null && result.isSuccessful()) {
                        SoundManager.playBatchDone();
                    } else if (result != null && result.getFailed() > 0) {
                        SoundManager.playError();
                    }
                    batchInProgress = false;
                    Platform.runLater(() -> {
                        isRunning.set(false);
                        appState.setProcessing(false);
                    });
                    if (isProcessingCallback != null) {
                        Platform.runLater(() -> isProcessingCallback.accept(false));
                    }
                    deleteStateFile();
                    
                    // Auto-remove completed files if enabled
                    if (autoRemoveCompleted && result != null && !result.wasCancelled()) {
                        removeCompletedFiles();
                    }
                });
    }

    // ========================================================================
    //  File Ordering Optimization
    // ========================================================================

    /**
     * Optimises file ordering for better throughput.
     *
     * <p>This method sorts files by size (smallest first) to get quick wins,
     * then interleaves small and large files to keep workers busy.</p>
     *
     * @param items the list of batch items to order
     * @return an ordered list of batch items
     */
    private List<BatchFileItem> optimizeFileOrdering(ObservableList<BatchFileItem> items) {
        List<BatchFileItem> sorted = new ArrayList<>(items);
        
        // Sort by file size (smallest first) for better throughput
        sorted.sort((a, b) -> Long.compare(a.getFile().length(), b.getFile().length()));
        
        // Interleave small and large files to keep workers busy
        List<BatchFileItem> largeFiles = new ArrayList<>();
        List<BatchFileItem> smallFiles = new ArrayList<>();
        
        long threshold = LARGE_FILE_THRESHOLD_MB * 1024 * 1024;
        for (BatchFileItem item : sorted) {
            if (item.getFile().length() > threshold) {
                largeFiles.add(item);
            } else {
                smallFiles.add(item);
            }
        }
        
        // Interleave: small, large, small, large...
        List<BatchFileItem> interleaved = new ArrayList<>();
        int maxSize = Math.max(smallFiles.size(), largeFiles.size());
        for (int i = 0; i < maxSize; i++) {
            if (i < smallFiles.size()) interleaved.add(smallFiles.get(i));
            if (i < largeFiles.size()) interleaved.add(largeFiles.get(i));
        }
        
        LOGGER.debug("Optimized file order: {} files ({} small, {} large)", 
            interleaved.size(), smallFiles.size(), largeFiles.size());
        
        return interleaved;
    }

    // ========================================================================
    //  Progress Tracking
    // ========================================================================

    /**
     * Sets up the progress updater scheduled task.
     */
    private void setupProgressUpdater() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BatchProgress-Updater");
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(() -> {
            if (batchInProgress) {
                updateProgress();
                updateETA();
            }
        }, PROGRESS_UPDATE_INTERVAL_MS, PROGRESS_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Updates the current batch progress.
     */
    private void updateProgress() {
        int completed = completedFilesCount.get();
        int failed = failedFilesCount.get();
        int total = totalFilesInBatch;
        int pending = Math.max(0, total - completed - failed);
        
        double progress = total > 0 ? (double) (completed + failed) / total : 0;
        totalBatchProgress = Math.min(1.0, progress);
        
        // Update AppState
        appState.updateBatchStats(total, completed, failed, pending);
        appState.setOverallProgress(totalBatchProgress);
        
        // Update time estimates
        if (timeEstimator != null) {
            appState.updateTimeEstimates(
                timeEstimator.getCurrentFileTimeSpent(),
                timeEstimator.getLiveCurrentFileTimeLeftMs(),
                timeEstimator.getTotalTimeSpent(),
                timeEstimator.getLiveTotalTimeLeftMs()
            );
        }
        
        // Call statistics callback
        if (statisticsCallback != null) {
            BatchStatistics stats = new BatchStatistics(
                total, completed, failed, 
                System.currentTimeMillis() - batchStartTime, 
                batchStartTime
            );
            statisticsCallback.accept(stats);
        }
    }

    /**
     * Updates the ETA for the current batch.
     */
    private void updateETA() {
        if (batchStartInstant == null || totalFilesInBatch == 0) return;
        
        int processed = completedFilesCount.get() + failedFilesCount.get();
        if (processed == 0) return;
        
        long elapsedMs = System.currentTimeMillis() - batchStartTime;
        int remaining = totalFilesInBatch - processed;
        
        if (remaining > 0 && elapsedMs > 0) {
            long estimatedTotalMs = (elapsedMs * totalFilesInBatch) / processed;
            long estimatedRemainingMs = estimatedTotalMs - elapsedMs;
            
            // Update AppState with ETA
            appState.updateTimeEstimates(
                timeEstimator != null ? timeEstimator.getCurrentFileTimeSpent() : 0,
                timeEstimator != null ? timeEstimator.getLiveCurrentFileTimeLeftMs() : 0,
                elapsedMs,
                Math.max(0, estimatedRemainingMs)
            );
        }
    }

    // ========================================================================
    //  Performance Tracking
    // ========================================================================

    /**
     * Records performance metrics for the current batch.
     *
     * @param items the batch items processed
     * @param processingConfig the processing configuration
     * @param transcriptionConfig the transcription configuration
     * @param result the parallel batch result
     */
    private void recordPerformance(List<BatchFileItem> items, 
                                   ProcessingConfig processingConfig,
                                   TranscriptionConfig transcriptionConfig,
                                   ParallelProcessingManager.ParallelBatchResult result) {
        
        BatchPerformanceRecord record = new BatchPerformanceRecord();
        record.timestamp = System.currentTimeMillis();
        record.totalFiles = result.getTotal();
        record.completedFiles = result.getCompleted();
        record.failedFiles = result.getFailed();
        record.durationMs = result.getDurationMillis();
        record.cancelled = result.wasCancelled();
        record.fileSizeBytes = totalFileSizeBytes.get();
        record.model = transcriptionConfig.getModel();
        record.gpuEnabled = parallelManager.isGpuEnabled();
        
        // Calculate throughput
        if (result.getDurationMillis() > 0) {
            record.filesPerSecond = (double) result.getCompleted() / (result.getDurationMillis() / 1000.0);
            record.mbPerSecond = (double) totalFileSizeBytes.get() / (1024 * 1024) / (result.getDurationMillis() / 1000.0);
        }
        
        performanceHistory.add(0, record);
        while (performanceHistory.size() > MAX_PERFORMANCE_HISTORY) {
            performanceHistory.remove(performanceHistory.size() - 1);
        }
        
        LOGGER.debug("Performance record saved: {} files in {}ms ({:.2f} files/sec, {:.2f} MB/sec)",
            record.completedFiles, record.durationMs, record.filesPerSecond, record.mbPerSecond);
    }

    /**
     * Logs a performance summary for the batch.
     *
     * @param result the batch result
     */
    private void logPerformanceSummary(BatchResult result) {
        if (result == null) return;
        
        StringBuilder summary = new StringBuilder();
        summary.append("📊 Batch Performance Summary:\n");
        summary.append("  • Files: ").append(result.getCompleted()).append("/").append(result.getTotal());
        if (result.getFailed() > 0) {
            summary.append(" (").append(result.getFailed()).append(" failed)");
        }
        summary.append("\n");
        summary.append("  • Duration: ").append(formatDuration(result.getDurationMillis())).append("\n");
        
        if (result.getDurationMillis() > 0) {
            double throughput = (double) result.getCompleted() / (result.getDurationMillis() / 1000.0);
            summary.append("  • Throughput: ").append(String.format("%.2f", throughput)).append(" files/sec\n");
            
            double mbps = (double) totalFileSizeBytes.get() / (1024 * 1024) / (result.getDurationMillis() / 1000.0);
            summary.append("  • Data rate: ").append(String.format("%.2f", mbps)).append(" MB/sec\n");
        }
        
        if (result.wasCancelled()) {
            summary.append("  • ⚠️ Cancelled by user\n");
        }
        
        logger.accept(summary.toString());
    }

    // ========================================================================
    //  Retry Logic
    // ========================================================================

    /**
     * Retries a failed file with the given configuration.
     *
     * @param item the failed batch item to retry
     * @param processingConfig the audio processing configuration
     * @param transcriptionConfig the transcription configuration
     * @return a {@link CompletableFuture} containing the result
     */
    public CompletableFuture<BatchFileItem> retryFailedFile(BatchFileItem item,
                                                            ProcessingConfig processingConfig,
                                                            TranscriptionConfig transcriptionConfig) {
        if (!"FAILED".equals(item.getStatus())) {
            return CompletableFuture.completedFuture(item);
        }

        logger.accept("🔄 Retrying failed file: " + item.getFileName());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Reset item state
                item.setStatus("PENDING");
                item.setProgress(0.0);
                item.setErrorMessage(null);

                // Process with retry
                return parallelManager.retryFile(item, processingConfig, transcriptionConfig);
            } catch (Exception e) {
                LOGGER.error("Retry failed for {}: {}", item.getFileName(), e.getMessage());
                item.setStatus("FAILED");
                item.setErrorMessage("Retry failed: " + e.getMessage());
                return item;
            }
        });
    }

    /**
     * Retries all failed files in the current batch.
     *
     * @param processingConfig the audio processing configuration
     * @param transcriptionConfig the transcription configuration
     * @return a {@link CompletableFuture} containing the results of all retries
     */
    public CompletableFuture<List<BatchFileItem>> retryAllFailedFiles(ProcessingConfig processingConfig,
                                                                      TranscriptionConfig transcriptionConfig) {
        if (currentItems == null || currentItems.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        List<BatchFileItem> failedItems = new ArrayList<>();
        for (BatchFileItem item : currentItems) {
            if ("FAILED".equals(item.getStatus())) {
                failedItems.add(item);
            }
        }

        if (failedItems.isEmpty()) {
            logger.accept("✅ No failed files to retry");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        logger.accept("🔄 Retrying " + failedItems.size() + " failed files...");

        List<CompletableFuture<BatchFileItem>> futures = new ArrayList<>();
        for (BatchFileItem item : failedItems) {
            futures.add(retryFailedFile(item, processingConfig, transcriptionConfig));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<BatchFileItem> results = new ArrayList<>();
                for (CompletableFuture<BatchFileItem> f : futures) {
                    try {
                        results.add(f.get());
                    } catch (Exception e) {
                        LOGGER.error("Failed to get retry result: {}", e.getMessage());
                    }
                }
                return results;
            });
    }

    /**
     * Removes completed files from the queue.
     */
    private void removeCompletedFiles() {
        Platform.runLater(() -> {
            if (currentItems != null) {
                currentItems.removeIf(item -> "COMPLETED".equals(item.getStatus()));
                logger.accept("🧹 Removed completed files from queue");
            }
        });
    }

    // ========================================================================
    //  Cancellation
    // ========================================================================

    /**
     * Cancels the current batch processing.
     */
    public void cancel() {
        if (!isProcessing()) return;
        cancelled = true;
        LOGGER.info("Cancellation requested.");
        
        // Cancel parallel processing
        new Thread(parallelManager::cancel, "BatchProcessor-Cancel").start();
        
        if (isProcessingCallback != null) {
            Platform.runLater(() -> isProcessingCallback.accept(false));
        }
        
        // Reset items that are not completed or failed
        Platform.runLater(() -> {
            for (BatchFileItem item : getCurrentItems()) {
                String status = item.getStatus();
                if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                    item.setStatus(ProcessingStatus.PENDING.name());
                }
            }
        });
    }

    // ========================================================================
    //  Statistics & Getters
    // ========================================================================

    /**
     * Returns whether a batch is currently processing.
     *
     * @return {@code true} if a batch is in progress
     */
    public boolean isProcessing() {
        return batchInProgress;
    }

    /**
     * Returns the running property for binding.
     *
     * @return a {@link ReadOnlyBooleanProperty} indicating whether a batch is running
     */
    public ReadOnlyBooleanProperty isRunningProperty() {
        return isRunning;
    }

    /**
     * Initialises the batch state.
     *
     * @param items the batch items to process
     */
    private void initializeBatchState(List<BatchFileItem> items) {
        totalFilesInBatch = items.size();
        completedFilesCount.set(0);
        failedFilesCount.set(0);
        batchStartTime = System.currentTimeMillis();
        totalBatchDuration = 0;

        // Reset items - COMPLETED stays, everything else becomes PENDING
        Platform.runLater(() -> {
            for (BatchFileItem item : items) {
                if (!"COMPLETED".equals(item.getStatus())) {
                    item.setStatus(ProcessingStatus.PENDING.name());
                }
                item.setStartTime(0);
                item.setErrorMessage(null);
            }
        });
        logger.accept("📊 Batch initialized: " + totalFilesInBatch + " files");
    }

    /**
     * Updates the completion counts.
     */
    private void updateCompletionCounts() {
        int completed = completedFilesCount.get();
        int failed = failedFilesCount.get();
        BatchStatistics stats = new BatchStatistics(
                totalFilesInBatch, completed, failed, totalBatchDuration, batchStartTime
        );
        
        // Update AppState
        int pending = Math.max(0, totalFilesInBatch - completed - failed);
        appState.updateBatchStats(totalFilesInBatch, completed, failed, pending);
        appState.setOverallProgress((double) (completed + failed) / Math.max(1, totalFilesInBatch));

        Platform.runLater(() -> {
            if (statisticsCallback != null) {
                statisticsCallback.accept(stats);
            }
        });
    }

    /**
     * Returns the current batch statistics.
     *
     * @return a {@link BatchStatistics} object
     */
    public BatchStatistics getCurrentBatchStatistics() {
        if (!batchInProgress) {
            int done = 0, fail = 0;
            long totalDur = 0;
            for (BatchFileItem item : currentItems) {
                if ("COMPLETED".equals(item.getStatus())) done++;
                else if ("FAILED".equals(item.getStatus())) fail++;
                if (item.getTotalAudioDurationSeconds() > 0) {
                    totalDur += (long)(item.getTotalAudioDurationSeconds() * 1000);
                }
            }
            return new BatchStatistics(currentItems.size(), done, fail, totalDur, 0);
        }
        return new BatchStatistics(
                totalFilesInBatch,
                completedFilesCount.get(),
                failedFilesCount.get(),
                totalBatchDuration,
                batchStartTime
        );
    }

    /**
     * Returns the current progress update.
     *
     * @return a {@link ProgressUpdate} object
     */
    public ProgressUpdate getCurrentProgress() {
        return new ProgressUpdate(
                totalFilesInBatch,
                completedFilesCount.get(),
                failedFilesCount.get()
        );
    }

    /**
     * Returns recent timing reports for completed files.
     *
     * @return a list of {@link FileTimingReport} objects
     */
    public List<FileTimingReport> getRecentTimingReports() {
        return parallelManager.getRecentTimingReports();
    }

    /**
     * Returns the current batch progress as a fraction.
     *
     * @return the batch progress (0.0 to 1.0)
     */
    public double getBatchProgress() {
        return totalBatchProgress;
    }

    /**
     * Returns the estimated remaining time for the batch.
     *
     * @return the estimated remaining time in milliseconds
     */
    public long getEstimatedRemainingMs() {
        if (batchStartInstant == null || totalFilesInBatch == 0) return 0;
        
        int processed = completedFilesCount.get() + failedFilesCount.get();
        if (processed == 0) return 0;
        
        long elapsedMs = System.currentTimeMillis() - batchStartTime;
        int remaining = totalFilesInBatch - processed;
        
        if (remaining > 0 && elapsedMs > 0) {
            long estimatedTotalMs = (elapsedMs * totalFilesInBatch) / processed;
            return Math.max(0, estimatedTotalMs - elapsedMs);
        }
        return 0;
    }

    // ========================================================================
    //  Performance Reports
    // ========================================================================

    /**
     * Returns a performance report for all batches processed.
     *
     * @return a formatted string containing performance metrics
     */
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Batch Processor Performance Report ===\n");
        report.append("Total batches processed: ").append(performanceHistory.size()).append("\n");
        
        if (!performanceHistory.isEmpty()) {
            BatchPerformanceRecord latest = performanceHistory.get(0);
            report.append("Latest batch:\n");
            report.append("  • Files: ").append(latest.completedFiles).append("/").append(latest.totalFiles).append("\n");
            report.append("  • Duration: ").append(formatDuration(latest.durationMs)).append("\n");
            report.append("  • Throughput: ").append(String.format("%.2f", latest.filesPerSecond)).append(" files/sec\n");
            report.append("  • Data rate: ").append(String.format("%.2f", latest.mbPerSecond)).append(" MB/sec\n");
            report.append("  • Model: ").append(latest.model).append("\n");
            report.append("  • GPU: ").append(latest.gpuEnabled ? "enabled" : "disabled").append("\n");
            
            // Calculate averages
            double avgFiles = performanceHistory.stream()
                .mapToDouble(r -> (double) r.completedFiles / r.totalFiles)
                .average().orElse(0);
            double avgThroughput = performanceHistory.stream()
                .mapToDouble(r -> r.filesPerSecond)
                .filter(v -> v > 0)
                .average().orElse(0);
            
            report.append("\nAverages:\n");
            report.append("  • Success rate: ").append(String.format("%.1f%%", avgFiles * 100)).append("\n");
            report.append("  • Avg throughput: ").append(String.format("%.2f", avgThroughput)).append(" files/sec\n");
        }
        
        return report.toString();
    }

    // ========================================================================
    //  State Persistence
    // ========================================================================

    /**
     * Saves the current batch state for crash recovery.
     */
    private void saveBatchState() {
        if (!batchInProgress) {
            deleteStateFile();
            return;
        }
        
        BatchState state = new BatchState();
        state.setBatchId(String.valueOf(batchStartTime));
        state.setBatchStartTime(batchStartTime);

        for (BatchFileItem item : currentItems) {
            BatchState.FileState fs = new BatchState.FileState();
            fs.setFilePath(item.getFile().getAbsolutePath());
            fs.setStatus(item.getStatus());
            fs.setProgress(item.getProgress());
            fs.setErrorMessage(item.getErrorMessage());
            state.getFiles().add(fs);
        }

        // Find processing file index
        int processingIndex = -1;
        for (int i = 0; i < currentItems.size(); i++) {
            if ("PROCESSING".equals(currentItems.get(i).getStatus())) {
                processingIndex = i;
                break;
            }
        }
        state.setCurrentFileIndex(processingIndex);

        try (java.io.Writer writer = Files.newBufferedWriter(stateFilePath)) {
            gson.toJson(state, writer);
            LOGGER.debug("Batch state saved.");
        } catch (IOException e) {
            LOGGER.error("Failed to save batch state", e);
        }
    }

    /**
     * Deletes the saved batch state file.
     */
    public void deleteStateFile() {
        try {
            Files.deleteIfExists(stateFilePath);
        } catch (IOException e) {
            LOGGER.warn("Could not delete state file", e);
        }
    }

    /**
     * Loads a saved batch state for recovery.
     *
     * @return the loaded {@link BatchState}, or {@code null} if none exists
     */
    public BatchState loadBatchState() {
        if (!Files.exists(stateFilePath)) return null;
        try (java.io.Reader reader = Files.newBufferedReader(stateFilePath)) {
            return gson.fromJson(reader, BatchState.class);
        } catch (IOException | com.google.gson.JsonParseException e) {
            LOGGER.error("Failed to load batch state — deleting corrupted file", e);
            try {
                Files.deleteIfExists(stateFilePath);
            } catch (IOException ex) {
                LOGGER.warn("Could not delete corrupted state file", ex);
            }
            return null;
        }
    }

    // ========================================================================
    //  Helper Methods
    // ========================================================================

    /**
     * Calculates the optimal parallelism based on system resources.
     *
     * @param userMax the maximum parallelism requested by the user
     * @param fileCount the number of files to process
     * @return the optimal parallelism
     */
    private int calculateOptimalParallelism(int userMax, int fileCount) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();
        
        // Memory-based limit (500MB per file)
        int memoryBased = (int) (maxMemory / (500 * 1024 * 1024));
        memoryBased = Math.max(1, memoryBased);
        
        // CPU-based limit (leave one core for system)
        int cpuBased = Math.max(1, cpuCores - 1);
        
        // Combine limits
        int optimal = Math.min(userMax, Math.min(cpuBased, memoryBased));
        optimal = Math.min(optimal, fileCount);
        optimal = Math.max(1, optimal);
        
        LOGGER.debug("Parallelism calculation: user={}, cpu={}, memory={}, files={}, optimal={}",
            userMax, cpuBased, memoryBased, fileCount, optimal);
        
        return optimal;
    }

    /**
     * Formats a file size in bytes to a human-readable string.
     *
     * @param bytes the file size in bytes
     * @return a formatted string (e.g., "1.5 MB")
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     *
     * @param millis the duration in milliseconds
     * @return a formatted string (e.g., "1h 2m 30s")
     */
    private String formatDuration(long millis) {
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m " + seconds + "s";
    }

    /**
     * Returns the current list of batch items.
     *
     * @return the observable list of batch items
     */
    private ObservableList<BatchFileItem> getCurrentItems() {
        return currentItems;
    }

    // ========================================================================
    //  SegmentProgressListener
    // ========================================================================

    @Override
    public void onSegmentCompleted(int segmentIndex, int totalSegments) {
        saveBatchState();
        if (timeEstimator != null) {
            // Update segment progress
            double progress = (double) segmentIndex / totalSegments;
            // The time estimator handles this internally
        }
    }

    // ========================================================================
    //  Learning Helpers
    // ========================================================================

    /**
     * Saves learned estimates to disk.
     */
    public void saveLearnedEstimates() {
        if (timeEstimator != null) timeEstimator.saveSessionData();
    }

    /**
     * Clears learned estimates.
     */
    public void clearLearnedEstimates() {
        if (timeEstimator != null) timeEstimator.clearLearnedData();
    }

    /**
     * Returns the number of learned patterns.
     *
     * @return the number of learned patterns
     */
    public int getLearnedPatternCount() {
        return timeEstimator != null ? timeEstimator.getLearnedPatternCount() : 0;
    }

    /**
     * Returns the total number of files processed.
     *
     * @return the total number of files processed
     */
    public long getTotalFilesProcessed() {
        return timeEstimator != null ? timeEstimator.getTotalFilesProcessed() : 0;
    }

    // ========================================================================
    //  Translation Helper (Called from ParallelProcessingManager)
    // ========================================================================

    /**
     * Translates a transcription result using the configured translation service.
     *
     * <p>This method is called by {@link ParallelProcessingManager} after
     * transcription completes. It handles translation errors gracefully -
     * if translation fails, the original transcript is returned.</p>
     *
     * @param result the transcription result to translate
     * @param config the transcription configuration containing translation settings
     * @return the translated transcription result, or the original if translation fails
     */
    public TranscriptionResult translateResult(TranscriptionResult result, 
                                                TranscriptionConfig config) {
        // Check if translation is available and enabled
        if (translationService == null) {
            LOGGER.debug("🌐 Translation skipped: no translation service configured");
            return result;
        }

        if (!config.isTranslationEnabled()) {
            LOGGER.debug("🌐 Translation skipped: translation disabled in config");
            return result;
        }

        String targetLanguage = config.getTranslationTargetLanguage();
        if (targetLanguage == null || targetLanguage.isBlank()) {
            LOGGER.warn("🌐 Translation skipped: no target language specified");
            return result;
        }

        // Check if there's anything to translate
        if (result.getText() == null || result.getText().isBlank()) {
            LOGGER.debug("🌐 Translation skipped: empty transcript");
            return result;
        }

        LOGGER.info("🌐 Translating transcript to: {}", targetLanguage);
        
        try {
            long startTime = System.currentTimeMillis();
            TranscriptionResult translated = translationService.translateSegments(result, targetLanguage);
            long durationMs = System.currentTimeMillis() - startTime;
            
            LOGGER.info("🌐 Translation completed in {}ms: {} segments translated", 
                durationMs, translated.getSegments().size());
            
            return translated;
            
        } catch (audiomanager.exceptions.TranscriptionException e) {
            LOGGER.warn("🌐 Translation failed: {} - using original transcript", e.getMessage());
            return result;
        } catch (Exception e) {
            LOGGER.error("🌐 Unexpected error during translation: {}", e.getMessage(), e);
            return result;
        }
    }

    // ========================================================================
    //  Inner Classes
    // ========================================================================

    /**
     * Batch statistics for reporting and display.
     */
    public static class BatchStatistics {
        private final int totalFiles, completedFiles, failedFiles;
        private final long totalDuration, batchStartTime;

        /**
         * Constructs a new BatchStatistics object.
         *
         * @param total the total number of files
         * @param completed the number of completed files
         * @param failed the number of failed files
         * @param dur the total duration in milliseconds
         * @param start the batch start time
         */
        public BatchStatistics(int total, int completed, int failed, long dur, long start) {
            this.totalFiles = total;
            this.completedFiles = completed;
            this.failedFiles = failed;
            this.totalDuration = dur;
            this.batchStartTime = start;
        }

        public int getTotalFiles() { return totalFiles; }
        public int getCompletedFiles() { return completedFiles; }
        public int getFailedFiles() { return failedFiles; }
        public int getPendingFiles() { return totalFiles - completedFiles - failedFiles; }
        public long getTotalDuration() { return totalDuration; }
        public long getBatchStartTime() { return batchStartTime; }
        public double getSuccessRate() {
            return totalFiles > 0 ? completedFiles * 100.0 / totalFiles : 0;
        }
    }

    /**
     * Batch result containing completion statistics.
     */
    public static class BatchResult {
        private final int total, completed, failed;
        private final long durationMillis;
        private final boolean cancelled;

        /**
         * Constructs a new BatchResult.
         *
         * @param total the total number of files
         * @param completed the number of completed files
         * @param failed the number of failed files
         * @param dur the total duration in milliseconds
         * @param cancelled whether the batch was cancelled
         */
        public BatchResult(int total, int completed, int failed, long dur, boolean cancelled) {
            this.total = total;
            this.completed = completed;
            this.failed = failed;
            this.durationMillis = dur;
            this.cancelled = cancelled;
        }

        public int getTotal() { return total; }
        public int getCompleted() { return completed; }
        public int getFailed() { return failed; }
        public int getPending() { return total - completed - failed; }
        public long getDurationMillis() { return durationMillis; }
        public boolean wasCancelled() { return cancelled; }
        public boolean isSuccessful() { return !cancelled && failed == 0; }
    }

    /**
     * Progress update snapshot.
     */
    public static class ProgressUpdate {
        private final int total, completed, failed;

        public ProgressUpdate(int total, int completed, int failed) {
            this.total = total;
            this.completed = completed;
            this.failed = failed;
        }

        public int getTotal() { return total; }
        public int getCompleted() { return completed; }
        public int getFailed() { return failed; }
        public int getPending() { return total - completed - failed; }
    }

    /**
     * Internal performance record for a batch.
     */
    private static class BatchPerformanceRecord {
        long timestamp;
        int totalFiles;
        int completedFiles;
        int failedFiles;
        long durationMs;
        boolean cancelled;
        long fileSizeBytes;
        String model;
        boolean gpuEnabled;
        double filesPerSecond;
        double mbPerSecond;
    }
}