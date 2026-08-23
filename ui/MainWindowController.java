/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.core.BatchProcessor;
import audiomanager.core.DependencyManager;
import audiomanager.core.ErrorReporter;
import audiomanager.model.BatchFileItem;
import audiomanager.model.ProcessingConfig;
import audiomanager.model.TranscriptionConfig;
import audiomanager.util.PreferenceManager;
import audiomanager.util.TimeLeftEstimator;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Controller for the main window - handles all business logic.
 * Separated from MainWindow to keep UI construction separate.
 */
public class MainWindowController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindowController.class);

    // ===== Dependencies =====
    private final AppState appState;
    private final BatchProcessor batchProcessor;
    private final DependencyManager dependencyManager;
    private final TimeLeftEstimator timeEstimator;
    private final PreferenceManager preferenceManager;
    private final Consumer<String> logger;
    private final ErrorReporter errorReporter;

    // ===== State =====
    private final Set<BatchFileItem> countedCompleted =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BatchFileItem> countedFailed =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int originalBatchSize = 0;

    // ===== Callbacks =====
    private Runnable onProcessingStateChanged;
    private Runnable onBatchCompleted;
    private Consumer<String> onStatusUpdate;

    // ========================================================================
    //  Construction
    // ========================================================================

    public MainWindowController(AppState appState,
                                BatchProcessor batchProcessor,
                                DependencyManager dependencyManager,
                                TimeLeftEstimator timeEstimator,
                                PreferenceManager preferenceManager,
                                Consumer<String> logger,
                                ErrorReporter errorReporter) {
        this.appState = appState;
        this.batchProcessor = batchProcessor;
        this.dependencyManager = dependencyManager;
        this.timeEstimator = timeEstimator;
        this.preferenceManager = preferenceManager;
        this.logger = logger;
        this.errorReporter = errorReporter;
    }

    // ========================================================================
    //  Callback Registration
    // ========================================================================

    public void setOnProcessingStateChanged(Runnable callback) {
        this.onProcessingStateChanged = callback;
    }

    public void setOnBatchCompleted(Runnable callback) {
        this.onBatchCompleted = callback;
    }

    public void setOnStatusUpdate(Consumer<String> callback) {
        this.onStatusUpdate = callback;
    }

    // ========================================================================
    //  Batch Processing
    // ========================================================================

    public void startBatch(ObservableList<BatchFileItem> batchFiles,
                           ProcessingConfig processingConfig,
                           TranscriptionConfig transcriptionConfig,
                           int maxParallel) {

        if (batchFiles.isEmpty()) {
            appState.setStatus("Error", "❌ Batch queue is empty. Please add files first.");
            return;
        }

        // Reset cumulative tracking
        countedCompleted.clear();
        countedFailed.clear();
        originalBatchSize = batchFiles.size();

        // Reset UI state
        appState.resetForNewBatch(originalBatchSize);
        appState.setStatus("Processing", "🚀 Starting batch processing...");
        appState.setProcessing(true);
        appState.setCancelling(false);

        if (timeEstimator != null) {
            timeEstimator.reset();
        }

        notifyStatusUpdate("🚀 Starting batch processing...");
        notifyProcessingStateChanged();

        // Live configuration wiring
        batchProcessor.setAdaptiveScalingEnabled(
                processingConfig.isAutoVolumeOptimization() // placeholder, should come from config panel
        );
        // Note: export flags and auto-remove should be set by the caller

        // Progress callback
        batchProcessor.setStatisticsCallback(stats -> {
            int completed = stats.getCompletedFiles();
            int failed = stats.getFailedFiles();
            int total = stats.getTotalFiles();
            int pending = Math.max(0, total - completed - failed);

            appState.updateBatchStats(total, completed, failed, pending);
            appState.setOverallProgress((double) (completed + failed) / Math.max(1, total));

            double progress = (completed + failed) * 100.0 / Math.max(1, total);
            if (progress % 10 < 1 || progress >= 99.9) {
                logger.accept(String.format("📈 Progress: %d/%d files (%.1f%%)",
                    completed + failed, total, progress));
            }
        });

        // Execute the batch
        batchProcessor.processBatch(batchFiles, processingConfig, transcriptionConfig, maxParallel)
            .thenAccept(result -> {
                Platform.runLater(() -> finishBatch(result));
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    appState.setStatus("Error", "❌ " + message);
                    logger.accept("❌ ERROR: Batch processing failed - " + message);
                    finishBatch(null);
                });
                return null;
            });

        appState.setOverallProgress(0.01);
    }

    public void cancelBatch() {
        if (!batchProcessor.isProcessing()) {
            return;
        }

        appState.setCancelling(true);
        logger.accept("⏹️ Cancelling batch processing...");

        CompletableFuture.runAsync(() -> {
            batchProcessor.cancel();
            Platform.runLater(() -> {
                appState.setCancelling(false);
                appState.setProcessing(false);
                appState.setStatus("Cancelled", "⏹️ Batch cancelled by user");
                notifyProcessingStateChanged();
                if (onBatchCompleted != null) {
                    onBatchCompleted.run();
                }
                logger.accept("⏹️ Batch cancellation complete");
            });
        });
    }

    public boolean isProcessing() {
        return batchProcessor.isProcessing();
    }

    // ========================================================================
    //  File Completion Callback
    // ========================================================================

    public void onFileCompleted(BatchFileItem item, boolean wasSuccessful) {
        Platform.runLater(() -> {
            if (wasSuccessful) {
                countedCompleted.add(item);
                appState.setStatus("File Completed", "✅ " + item.getFileName());
            } else {
                countedFailed.add(item);
                appState.setStatus("File Failed", "❌ " + item.getFileName());
            }

            int completed = countedCompleted.size();
            int failed = countedFailed.size();
            int total = originalBatchSize;
            int pending = Math.max(0, total - completed - failed);
            appState.updateBatchStats(total, completed, failed, pending);
        });
    }

    // ========================================================================
    //  Dependency Management
    // ========================================================================

    public void checkDependencies() {
        appState.setStatus("Checking Dependencies", "🔍 Verifying system dependencies...");
        logger.accept("🔍 Checking system dependencies...");

        CompletableFuture.supplyAsync(() -> {
            try {
                DependencyManager.DependencyStatus ffmpegStatus = dependencyManager.checkFFmpeg();
                DependencyManager.DependencyStatus ffprobeStatus = null;
                DependencyManager.DependencyStatus whisperStatus = dependencyManager.checkWhisper();

                if (ffmpegStatus.isAvailable()) {
                    ffprobeStatus = safeCheckFFprobe();
                }

                java.util.Map<String, Object> results = new java.util.HashMap<>();
                results.put("ffmpeg", ffmpegStatus);
                results.put("ffprobe", ffprobeStatus);
                results.put("whisper", whisperStatus);
                return results;

            } catch (Exception e) {
                LOGGER.error("Dependency check failed", e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }).thenAccept(results -> {
            Platform.runLater(() -> {
                DependencyManager.DependencyStatus ffmpegStatus =
                    (DependencyManager.DependencyStatus) results.get("ffmpeg");
                DependencyManager.DependencyStatus ffprobeStatus =
                    (DependencyManager.DependencyStatus) results.get("ffprobe");
                DependencyManager.DependencyStatus whisperStatus =
                    (DependencyManager.DependencyStatus) results.get("whisper");

                logger.accept("✅ " + ffmpegStatus.getMessage());

                if (ffprobeStatus != null) {
                    logger.accept((ffprobeStatus.isAvailable() ? "✅ " : "❌ ") + ffprobeStatus.getMessage());
                }

                if (!ffmpegStatus.isAvailable()) {
                    appState.setStatus("FFmpeg Missing", "❌ FFmpeg is required for audio processing");
                    if (ffmpegStatus.hasInstallationHint()) {
                        logger.accept("❌ REQUIRED: " + ffmpegStatus.getInstallationHint());
                    }
                    return;
                }

                if (ffprobeStatus != null && !ffprobeStatus.isAvailable()) {
                    appState.setStatus("FFprobe Missing", "❌ FFprobe is required");
                    if (ffprobeStatus.hasInstallationHint()) {
                        logger.accept("❌ REQUIRED: " + ffprobeStatus.getInstallationHint());
                    }
                    return;
                }

                if (!whisperStatus.isAvailable()) {
                    appState.setStatus("Whisper Missing", "⚠️ WhisperX not found");
                    logger.accept("⚠️ WARNING: " + whisperStatus.getMessage());
                    if (whisperStatus.hasInstallationHint()) {
                        logger.accept("💡 INFO: " + whisperStatus.getInstallationHint());
                    }
                } else {
                    logger.accept("✅ " + whisperStatus.getMessage());
                }

                appState.setStatus("Ready", "🎉 All dependencies verified");
                logger.accept("🎉 Dependency check complete. System is ready.");
            });
        }).exceptionally(ex -> {
            LOGGER.error("Dependency check failed", ex);
            Platform.runLater(() -> {
                String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                appState.setStatus("Error", "❌ " + message);
                logger.accept("❌ ERROR: Dependency check failed - " + message);
            });
            return null;
        });
    }

    // ========================================================================
    //  Getters
    // ========================================================================

    public AppState getAppState() {
        return appState;
    }

    public BatchProcessor getBatchProcessor() {
        return batchProcessor;
    }

    public int getOriginalBatchSize() {
        return originalBatchSize;
    }

    public Set<BatchFileItem> getCountedCompleted() {
        return countedCompleted;
    }

    public Set<BatchFileItem> getCountedFailed() {
        return countedFailed;
    }

    // ========================================================================
    //  Private Helpers
    // ========================================================================

    private void finishBatch(BatchProcessor.BatchResult result) {
        appState.setProcessing(false);
        appState.setCancelling(false);

        if (result != null) {
            int completed = result.getCompleted();
            int failed = result.getFailed();
            int total = result.getTotal();
            int pending = Math.max(0, total - completed - failed);

            appState.updateBatchStats(total, completed, failed, pending);
            appState.setOverallProgress(1.0);

            if (result.wasCancelled()) {
                appState.setStatus("Cancelled", "⏹️ Batch cancelled by user");
                logger.accept("⏹️ Batch processing cancelled.");
            } else if (result.isSuccessful()) {
                appState.setStatus("Complete", String.format("✅ All %d files processed!", total));
                logger.accept(String.format("✅ Batch complete: %d files processed.", total));
            } else {
                appState.setStatus("Complete", String.format("⚠️ %d succeeded, %d failed", completed, failed));
                logger.accept(String.format("✅ Batch complete: %d succeeded, %d failed", completed, failed));
            }
        } else {
            appState.setStatus("Error", "❌ Batch processing failed");
            appState.updateBatchStats(0, 0, 0, 0);
            appState.setOverallProgress(0);
        }

        notifyProcessingStateChanged();
        if (onBatchCompleted != null) {
            onBatchCompleted.run();
        }

        logger.accept("📊 Batch processing finished.");
    }

    private DependencyManager.DependencyStatus safeCheckFFprobe() {
        try {
            return dependencyManager.checkFFprobe();
        } catch (Exception e) {
            LOGGER.error("FFprobe check threw an exception", e);
            return new DependencyManager.DependencyStatus(
                "FFprobe",
                false,
                "FFprobe check failed: " + e.getMessage(),
                "Please ensure FFprobe is installed."
            );
        }
    }

    private void notifyProcessingStateChanged() {
        if (onProcessingStateChanged != null) {
            onProcessingStateChanged.run();
        }
    }

    private void notifyStatusUpdate(String status) {
        if (onStatusUpdate != null) {
            onStatusUpdate.accept(status);
        }
    }
}