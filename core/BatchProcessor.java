/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.*;
import audiomanager.ui.AppState;
import audiomanager.ui.ConfigurationPanel;
import audiomanager.util.TimeLeftEstimator;
import audiomanager.util.PreferenceManager;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Manages batch processing of audio files.
 * 
 * <p>This class now delegates all parallel processing to the simplified
 * ParallelProcessingManager. It handles high-level orchestration:
 * state persistence, statistics, and coordination with the UI via AppState.</p>
 */
public class BatchProcessor implements SegmentProgressListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchProcessor.class);

    // ===== Dependencies =====
    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final TimeLeftEstimator timeEstimator;
    private final PreferenceManager preferenceManager;
    private final Consumer<String> logger;
    private final ParallelProcessingManager parallelManager;
    private final AppState appState = AppState.getInstance();
    private ErrorReporter errorReporter;

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

    public interface FileCompletionCallback {
        void onFileCompleted(BatchFileItem item, boolean wasSuccessful);
    }

    // ========================================================================
    //  Construction
    // ========================================================================

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
                completedFilesCount.incrementAndGet();
                appState.setStatus("File Completed", "✅ " + item.getFileName());
            } else {
                failedFilesCount.incrementAndGet();
                appState.setStatus("File Failed", "❌ " + item.getFileName());
            }
            updateCompletionCounts();
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

        LOGGER.info("BatchProcessor initialized with simplified parallel manager");
    }

    // ========================================================================
    //  Configuration
    // ========================================================================

    public void setConfigurationPanel(ConfigurationPanel configPanel) {
        this.configurationPanel = configPanel;
    }

    public void setAdaptiveScalingEnabled(boolean enabled) {
        parallelManager.setAdaptiveScalingEnabled(enabled);
    }

    public boolean isAdaptiveScalingEnabled() {
        return parallelManager.isAdaptiveScalingEnabled();
    }

    public void setExportWordCopy(boolean enabled) {
        parallelManager.setExportWordCopy(enabled);
    }

    public void setExportPdfCopy(boolean enabled) {
        parallelManager.setExportPdfCopy(enabled);
    }

    public void setAutoRemoveCompleted(boolean autoRemoveCompleted) {
        this.autoRemoveCompleted = autoRemoveCompleted;
    }

    public void setStatisticsCallback(Consumer<BatchStatistics> callback) {
        this.statisticsCallback = callback;
    }

    public void setIsProcessingCallback(Consumer<Boolean> callback) {
        this.isProcessingCallback = callback;
    }

    public void setFileCompletedCallback(Consumer<BatchFileItem> callback) {
        this.fileCompletedCallback = callback;
    }

    // ========================================================================
    //  Batch Processing
    // ========================================================================

    public CompletableFuture<BatchResult> processBatch(ObservableList<BatchFileItem> items,
                                                       ProcessingConfig processingConfig,
                                                       TranscriptionConfig transcriptionConfig,
                                                       int maxParallel) {
        if (isProcessing()) {
            throw new IllegalStateException("Batch already in progress.");
        }

        // Initialize state
        Platform.runLater(() -> isRunning.set(true));
        initializeBatchState(items);
        cancelled = false;
        batchInProgress = true;

        if (isProcessingCallback != null) {
            Platform.runLater(() -> isProcessingCallback.accept(true));
        }

        // Setup time estimator
        if (timeEstimator != null) {
            timeEstimator.startBatch();
            for (BatchFileItem item : items) {
                double mb = item.getFile().length() / (1024.0 * 1024.0);
                List<String> procs = transcriptionConfig.isEnabled()
                        ? TRANSCRIPTION_PROCESSES : AUDIO_ONLY_PROCESSES;
                String model = transcriptionConfig.isEnabled()
                        ? transcriptionConfig.getModel() : "base";
                timeEstimator.addQueuedFile(item.getFileName(), mb, model, procs);
            }
        }

        LOGGER.info("Starting batch: {} files, max parallel: {}", totalFilesInBatch, maxParallel);
        logger.accept("🚀 Starting batch: " + totalFilesInBatch + " files");

        // Reset progress tracking
        appState.resetForNewBatch(totalFilesInBatch);
        appState.setStatus("Processing", "🚀 Starting batch processing...");

        // Delegate to parallel manager
        return parallelManager.processBatchParallel(items, processingConfig, transcriptionConfig, maxParallel)
                .thenApply(parallelResult -> {
                    totalBatchDuration = parallelResult.getDurationMillis();
                    BatchResult result = new BatchResult(
                            parallelResult.getTotal(),
                            parallelResult.getCompleted(),
                            parallelResult.getFailed(),
                            parallelResult.getDurationMillis(),
                            parallelResult.wasCancelled()
                    );
                    logger.accept("🎯 Batch complete: " + parallelResult.getCompleted() + " succeeded, "
                            + parallelResult.getFailed() + " failed, "
                            + parallelResult.getDurationMillis() + "ms");
                    return result;
                })
                .exceptionally(throwable -> {
                    LOGGER.error("Batch processing failed", throwable);
                    logger.accept("❌ Batch processing failed: " + throwable.getMessage());
                    int completed = completedFilesCount.get();
                    return new BatchResult(totalFilesInBatch, completed,
                            totalFilesInBatch - completed, 0, cancelled);
                })
                .whenComplete((result, throwable) -> {
                    batchInProgress = false;
                    Platform.runLater(() -> {
                        isRunning.set(false);
                        appState.setProcessing(false);
                    });
                    if (isProcessingCallback != null) {
                        Platform.runLater(() -> isProcessingCallback.accept(false));
                    }
                    deleteStateFile();
                });
    }

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

    public boolean isProcessing() {
        return batchInProgress;
    }

    public ReadOnlyBooleanProperty isRunningProperty() {
        return isRunning;
    }

    // ========================================================================
    //  Statistics & Progress
    // ========================================================================

    private void initializeBatchState(ObservableList<BatchFileItem> items) {
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
            if (fileCompletedCallback != null) {
                // This would need to know which item completed - use completion callback instead
            }
        });
    }

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

    public ProgressUpdate getCurrentProgress() {
        return new ProgressUpdate(
                totalFilesInBatch,
                completedFilesCount.get(),
                failedFilesCount.get()
        );
    }

    public List<FileTimingReport> getRecentTimingReports() {
        return parallelManager.getRecentTimingReports();
    }

    // ========================================================================
    //  State Persistence
    // ========================================================================

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

    public void deleteStateFile() {
        try {
            Files.deleteIfExists(stateFilePath);
        } catch (IOException e) {
            LOGGER.warn("Could not delete state file", e);
        }
    }

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
    //  SegmentProgressListener
    // ========================================================================

    @Override
    public void onSegmentCompleted(int segmentIndex, int totalSegments) {
        saveBatchState();
    }

    // ========================================================================
    //  Learning Helpers
    // ========================================================================

    public void saveLearnedEstimates() {
        if (timeEstimator != null) timeEstimator.saveSessionData();
    }

    public void clearLearnedEstimates() {
        if (timeEstimator != null) timeEstimator.clearLearnedData();
    }

    public int getLearnedPatternCount() {
        return timeEstimator != null ? timeEstimator.getLearnedPatternCount() : 0;
    }

    public long getTotalFilesProcessed() {
        return timeEstimator != null ? timeEstimator.getTotalFilesProcessed() : 0;
    }

    // ========================================================================
    //  Getters
    // ========================================================================

    private ObservableList<BatchFileItem> getCurrentItems() {
        return currentItems;
    }

    // ========================================================================
    //  Inner Classes
    // ========================================================================

    public static class BatchStatistics {
        private final int totalFiles, completedFiles, failedFiles;
        private final long totalDuration, batchStartTime;

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

    public static class BatchResult {
        private final int total, completed, failed;
        private final long durationMillis;
        private final boolean cancelled;

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
}