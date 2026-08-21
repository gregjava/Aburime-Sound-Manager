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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Mediates between UI components and business logic.
 * 
 * <p>This class reduces MainWindow's responsibility to just UI construction
 * and event handling. All business logic (batch processing, dependency checks,
 * state management) is handled here.</p>
 * 
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Starting and cancelling batch processing</li>
 *   <li>Checking system dependencies</li>
 *   <li>Managing the application state via AppState</li>
 *   <li>Coordinating between UI components and core services</li>
 * </ul>
 */
public class MainController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainController.class);

    // ===== Dependencies =====
    private final AppState appState;
    private final BatchProcessor batchProcessor;
    private final DependencyManager dependencyManager;
    private final TimeLeftEstimator timeEstimator;
    private final PreferenceManager preferenceManager;
    private final Consumer<String> logger;
    private final ErrorReporter errorReporter;

    // ===== UI References (optional, for direct updates) =====
    private ControlPanel controlPanel;
    private FileSelectionPanel fileSelectionPanel;
    private ConfigurationPanel configurationPanel;

    // ===== Callbacks =====
    private Runnable onBatchStartCallback;
    private Runnable onBatchCompleteCallback;
    private Consumer<Throwable> onErrorCallback;

    // ========================================================================
    //  Construction
    // ========================================================================

    public MainController(AppState appState,
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

        // Wire up batch processor callbacks
        setupBatchProcessorCallbacks();

        LOGGER.debug("MainController initialized");
    }

    // ========================================================================
    //  UI Registration
    // ========================================================================

    /**
     * Register UI components for direct updates when needed.
     * This is optional - AppState binding handles most updates automatically.
     */
    public void registerUIComponents(ControlPanel controlPanel,
                                     FileSelectionPanel fileSelectionPanel,
                                     ConfigurationPanel configurationPanel) {
        this.controlPanel = controlPanel;
        this.fileSelectionPanel = fileSelectionPanel;
        this.configurationPanel = configurationPanel;
        LOGGER.debug("UI components registered with MainController");
    }

    public void setOnBatchStart(Runnable callback) {
        this.onBatchStartCallback = callback;
    }

    public void setOnBatchComplete(Runnable callback) {
        this.onBatchCompleteCallback = callback;
    }

    public void setOnError(Consumer<Throwable> callback) {
        this.onErrorCallback = callback;
    }

    // ========================================================================
    //  Batch Processing
    // ========================================================================

    /**
     * Start batch processing with the current queue and configuration.
     */
    public void startBatch(ObservableList<BatchFileItem> batchFiles,
                           ProcessingConfig processingConfig,
                           TranscriptionConfig transcriptionConfig,
                           int maxParallel) {

        if (batchFiles.isEmpty()) {
            appState.setStatus("Error", "❌ Batch queue is empty. Please add files first.");
            logger.accept("❌ Cannot start batch: queue is empty");
            return;
        }

        // Validate dependencies before starting
        if (!validateDependencies()) {
            appState.setStatus("Error", "❌ Dependencies not satisfied. Check FFmpeg and WhisperX.");
            logger.accept("❌ Cannot start batch: dependencies not satisfied");
            return;
        }

        LOGGER.info("Starting batch processing: {} files, max parallel: {}", batchFiles.size(), maxParallel);

        // Reset state
        appState.resetForNewBatch(batchFiles.size());
        appState.setStatus("Processing", "🚀 Starting batch processing...");
        appState.setProcessing(true);
        appState.setCancelling(false);

        if (timeEstimator != null) {
            timeEstimator.reset();
        }

        // Update UI
        if (configurationPanel != null) {
            configurationPanel.setEnabled(false);
        }
        if (fileSelectionPanel != null) {
            fileSelectionPanel.resetBatchStatus();
        }

        // Fire batch start callback
        if (onBatchStartCallback != null) {
            onBatchStartCallback.run();
        }

        // Log the start
        logger.accept("🚀 Starting batch processing...");

        // Live configuration wiring
        if (configurationPanel != null) {
            batchProcessor.setAdaptiveScalingEnabled(configurationPanel.isAdaptiveScalingEnabled());
            batchProcessor.setExportWordCopy(configurationPanel.isExportWordCopyEnabled());
            batchProcessor.setExportPdfCopy(configurationPanel.isExportPdfCopyEnabled());
            batchProcessor.setAutoRemoveCompleted(configurationPanel.isAutoRemoveCompleted());
        }

        // Progress callback
        batchProcessor.setStatisticsCallback(stats -> {
            int completed = stats.getCompletedFiles();
            int failed = stats.getFailedFiles();
            int total = stats.getTotalFiles();
            int pending = Math.max(0, total - completed - failed);

            appState.updateBatchStats(total, completed, failed, pending);
            appState.setOverallProgress((double) (completed + failed) / Math.max(1, total));

            // Log progress periodically
            double progress = (completed + failed) * 100.0 / Math.max(1, total);
            if (progress % 10 < 1 || progress >= 99.9) {
                logger.accept(String.format("📈 Progress: %d/%d files (%.1f%%)",
                    completed + failed, total, progress));
            }
        });

        // Execute the batch asynchronously
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
                if (onErrorCallback != null) {
                    onErrorCallback.accept(ex);
                }
                return null;
            });

        // Initial progress update
        appState.setOverallProgress(0.01);
    }

    /**
     * Cancel the current batch.
     */
    public void cancelBatch() {
        if (!batchProcessor.isProcessing()) {
            logger.accept("⚠️ No batch is currently processing");
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
                if (controlPanel != null) {
                    controlPanel.setProcessingState(false);
                }
                if (onBatchCompleteCallback != null) {
                    onBatchCompleteCallback.run();
                }
                logger.accept("⏹️ Batch cancellation complete");
            });
        });
    }

    /**
     * Check if a batch is currently processing.
     */
    public boolean isProcessing() {
        return batchProcessor.isProcessing();
    }

    // ========================================================================
    //  Dependency Management
    // ========================================================================

    /**
     * Check all system dependencies asynchronously.
     */
    public void checkDependencies() {
        appState.setStatus("Checking Dependencies", "🔍 Verifying system dependencies...");
        if (controlPanel != null) {
            controlPanel.setDependencyCheckInProgress(true);
        }
        logger.accept("🔍 Checking system dependencies...");

        CompletableFuture.supplyAsync(() -> {
            try {
                DependencyManager.DependencyStatus ffmpegStatus = dependencyManager.checkFFmpeg();
                DependencyManager.DependencyStatus ffprobeStatus = null;
                DependencyManager.DependencyStatus whisperStatus = dependencyManager.checkWhisper();
                DependencyManager.DependencyStatus ffmpegVisibleToWhisperX = null;

                // Only check FFprobe if FFmpeg is available
                if (ffmpegStatus.isAvailable()) {
                    ffprobeStatus = safeCheckFFprobe();
                }

                // Only check WhisperX visibility if transcription is enabled
                if (ffmpegStatus.isAvailable() && configurationPanel != null
                        && configurationPanel.isTranscriptionEnabled()) {
                    ffmpegVisibleToWhisperX = dependencyManager.checkFFmpegVisibleToWhisperX();
                }

                java.util.Map<String, Object> results = new java.util.HashMap<>();
                results.put("ffmpeg", ffmpegStatus);
                results.put("ffprobe", ffprobeStatus);
                results.put("whisper", whisperStatus);
                results.put("whisperVisibility", ffmpegVisibleToWhisperX);
                return results;

            } catch (Exception e) {
                LOGGER.error("Dependency check failed", e);
                throw new CompletionException(e);
            }
        }).thenAccept(results -> {
            Platform.runLater(() -> {
                DependencyManager.DependencyStatus ffmpegStatus =
                    (DependencyManager.DependencyStatus) results.get("ffmpeg");
                DependencyManager.DependencyStatus ffprobeStatus =
                    (DependencyManager.DependencyStatus) results.get("ffprobe");
                DependencyManager.DependencyStatus whisperStatus =
                    (DependencyManager.DependencyStatus) results.get("whisper");
                DependencyManager.DependencyStatus ffmpegVisibleToWhisperX =
                    (DependencyManager.DependencyStatus) results.get("whisperVisibility");

                // Update control panel
                if (controlPanel != null) {
                    controlPanel.updateDependencyStatus(ffmpegStatus, whisperStatus);
                }

                // Log results
                logger.accept("✅ " + ffmpegStatus.getMessage());

                if (ffprobeStatus != null) {
                    logger.accept((ffprobeStatus.isAvailable() ? "✅ " : "❌ ") + ffprobeStatus.getMessage());
                }

                // FFmpeg is REQUIRED
                if (!ffmpegStatus.isAvailable()) {
                    appState.setStatus("FFmpeg Missing", "❌ FFmpeg is required for audio processing");
                    if (controlPanel != null) controlPanel.setProcessingEnabled(false);
                    if (ffmpegStatus.hasInstallationHint()) {
                        logger.accept("❌ REQUIRED: " + ffmpegStatus.getInstallationHint());
                    }
                    return;
                }

                // FFprobe is REQUIRED
                if (ffprobeStatus != null && !ffprobeStatus.isAvailable()) {
                    appState.setStatus("FFprobe Missing", "❌ FFprobe is required");
                    if (controlPanel != null) controlPanel.setProcessingEnabled(false);
                    if (ffprobeStatus.hasInstallationHint()) {
                        logger.accept("❌ REQUIRED: " + ffprobeStatus.getInstallationHint());
                    }
                    return;
                }

                // Whisper is optional (only needed for transcription)
                if (!whisperStatus.isAvailable() && configurationPanel != null
                        && configurationPanel.isTranscriptionEnabled()) {
                    appState.setStatus("Whisper Missing", "⚠️ WhisperX not found");
                    logger.accept("⚠️ WARNING: " + whisperStatus.getMessage());
                    if (whisperStatus.hasInstallationHint()) {
                        logger.accept("💡 INFO: " + whisperStatus.getInstallationHint());
                    }
                    if (controlPanel != null) controlPanel.setProcessingEnabled(true);
                } else {
                    logger.accept("✅ " + whisperStatus.getMessage());
                }

                // Advisory check for FFmpeg visibility in WhisperX
                if (ffmpegVisibleToWhisperX != null) {
                    if (ffmpegVisibleToWhisperX.isAvailable()) {
                        logger.accept("✅ " + ffmpegVisibleToWhisperX.getMessage());
                    } else {
                        logger.accept("⚠️ " + ffmpegVisibleToWhisperX.getMessage());
                    }
                }

                appState.setStatus("Ready", "🎉 All dependencies verified");
                if (controlPanel != null) controlPanel.setProcessingEnabled(true);
                logger.accept("🎉 Dependency check complete. System is ready.");
            });
        }).exceptionally(ex -> {
            LOGGER.error("Dependency check failed", ex);
            Platform.runLater(() -> {
                String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                appState.setStatus("Error", "❌ " + message);
                logger.accept("❌ ERROR: Dependency check failed - " + message);
                if (controlPanel != null) controlPanel.setProcessingEnabled(false);
            });
            if (onErrorCallback != null) {
                onErrorCallback.accept(ex);
            }
            return null;
        }).whenComplete((result, ex) -> {
            Platform.runLater(() -> {
                if (controlPanel != null) {
                    controlPanel.setDependencyCheckInProgress(false);
                }
                if (fileSelectionPanel != null) {
                    fileSelectionPanel.updateBatchStatus(appState.getBatchFiles());
                }
            });
        });
    }

    /**
     * Validate dependencies before starting a batch.
     * Returns true if all required dependencies are available.
     */
    private boolean validateDependencies() {
        DependencyManager.DependencyStatus ffmpeg = dependencyManager.checkFFmpeg();
        if (!ffmpeg.isAvailable()) {
            return false;
        }

        DependencyManager.DependencyStatus ffprobe = safeCheckFFprobe();
        if (ffprobe != null && !ffprobe.isAvailable()) {
            return false;
        }

        return true;
    }

    /**
     * Safely check FFprobe without throwing exceptions.
     */
    private DependencyManager.DependencyStatus safeCheckFFprobe() {
        try {
            return dependencyManager.checkFFprobe();
        } catch (Exception e) {
            LOGGER.error("FFprobe check threw an exception", e);
            return new DependencyManager.DependencyStatus(
                "FFprobe",
                false,
                "FFprobe check failed: " + e.getMessage(),
                "An error occurred while checking FFprobe. Please ensure FFprobe is installed."
            );
        }
    }

    // ========================================================================
    //  Batch Completion
    // ========================================================================

    private void finishBatch(BatchProcessor.BatchResult result) {
        // Re-enable UI
        if (configurationPanel != null) {
            configurationPanel.setEnabled(true);
        }
        if (fileSelectionPanel != null) {
            fileSelectionPanel.setProcessingState(false);
        }

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

            // Auto-remove completed files if enabled
            if (configurationPanel != null && configurationPanel.isAutoRemoveCompleted()
                    && !result.wasCancelled()) {
                appState.getBatchFiles().removeIf(item -> "COMPLETED".equals(item.getStatus()));
            }
        } else {
            appState.setStatus("Error", "❌ Batch processing failed");
            appState.updateBatchStats(0, 0, 0, 0);
            appState.setOverallProgress(0);
        }

        if (fileSelectionPanel != null) {
            fileSelectionPanel.updateBatchStatus(appState.getBatchFiles());
        }

        if (onBatchCompleteCallback != null) {
            onBatchCompleteCallback.run();
        }

        logger.accept("📊 Batch processing finished.");
    }

    // ========================================================================
    //  Setup
    // ========================================================================

    private void setupBatchProcessorCallbacks() {
        // BatchProcessor callbacks are already set up via the constructor
        // and the statistics callback in startBatch()
    }

    // ========================================================================
    //  Convenience Methods
    // ========================================================================

    public AppState getAppState() {
        return appState;
    }

    public BatchProcessor getBatchProcessor() {
        return batchProcessor;
    }

    public TimeLeftEstimator getTimeEstimator() {
        return timeEstimator;
    }

    public PreferenceManager getPreferenceManager() {
        return preferenceManager;
    }
}