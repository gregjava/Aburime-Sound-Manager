/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.model.BatchFileItem;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralized, observable application state.
 *
 * <p>This is the single source of truth for all UI state in the application.
 * UI components bind directly to properties in this class. Business logic
 * (BatchProcessor, ParallelProcessingManager, MainController, etc.) updates
 * this state, and the UI automatically reflects those changes.</p>
 *
 * <p>This eliminates the callback spaghetti that previously existed where
 * UI state was updated from multiple sources (MainWindow, BatchProcessor,
 * ControlPanel, FileSelectionPanel) leading to race conditions and
 * inconsistent states.</p>
 *
 * <p><b>Thread-safety:</b> All property updates are performed on the
 * JavaFX Application Thread via {@code Platform.runLater()} to ensure
 * thread-safe UI binding.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see BatchFileItem
 */
public class AppState {

    // ===== Singleton =====
    private static final AppState INSTANCE = new AppState();

    /**
     * Returns the singleton instance of AppState.
     *
     * @return the AppState instance
     */
    public static AppState getInstance() {
        return INSTANCE;
    }

    private AppState() {
        // Private constructor for singleton
    }

    // ===== Batch Queue =====
    private final ObservableList<BatchFileItem> batchFiles = FXCollections.observableArrayList();

    /**
     * Returns the observable list of batch files.
     *
     * @return the batch file list
     */
    public ObservableList<BatchFileItem> getBatchFiles() {
        return batchFiles;
    }

    // ===== Processing State =====
    private final BooleanProperty isProcessing = new SimpleBooleanProperty(false);
    private final BooleanProperty isCancelling = new SimpleBooleanProperty(false);
    private final BooleanProperty dependencyCheckInProgress = new SimpleBooleanProperty(false);
    private final StringProperty processingStatus = new SimpleStringProperty("Ready");
    private final StringProperty detailedStatus = new SimpleStringProperty("");
    private final StringProperty resourceStatus = new SimpleStringProperty("");
    private final StringProperty totalDuration = new SimpleStringProperty("0s");

    /** Returns the isProcessing property for binding. */
    public BooleanProperty isProcessingProperty() { return isProcessing; }

    /** Returns the isCancelling property for binding. */
    public BooleanProperty isCancellingProperty() { return isCancelling; }

    /** Returns the processingStatus property for binding. */
    public StringProperty processingStatusProperty() { return processingStatus; }

    /** Returns the detailedStatus property for binding. */
    public StringProperty detailedStatusProperty() { return detailedStatus; }

    /** Returns the resourceStatus property for binding. */
    public StringProperty resourceStatusProperty() { return resourceStatus; }

    /** Returns the totalDuration property for binding. */
    public StringProperty totalDurationProperty() { return totalDuration; }

    /**
     * Returns the total duration string.
     *
     * @return the total duration
     */
    public String getTotalDuration() {
        return totalDuration.get();
    }

    /**
     * Sets the total duration string.
     *
     * @param duration the duration string (e.g., "2m 30s")
     */
    public void setTotalDuration(String duration) {
        Platform.runLater(() -> totalDuration.set(duration));
    }

    /** Returns the dependencyCheckInProgress property for binding. */
    public BooleanProperty dependencyCheckInProgressProperty() {
        return dependencyCheckInProgress;
    }

    /**
     * Returns whether a dependency check is in progress.
     *
     * @return {@code true} if a dependency check is in progress
     */
    public boolean isDependencyCheckInProgress() {
        return dependencyCheckInProgress.get();
    }

    /**
     * Sets whether a dependency check is in progress.
     *
     * @param inProgress {@code true} if a dependency check is in progress
     */
    public void setDependencyCheckInProgress(boolean inProgress) {
        Platform.runLater(() -> dependencyCheckInProgress.set(inProgress));
    }

    /**
     * Returns whether a batch is processing.
     *
     * @return {@code true} if a batch is processing
     */
    public boolean isProcessing() { return isProcessing.get(); }

    /**
     * Returns whether a batch is cancelling.
     *
     * @return {@code true} if a batch is cancelling
     */
    public boolean isCancelling() { return isCancelling.get(); }

    /**
     * Sets the processing state.
     *
     * @param processing {@code true} if a batch is processing
     */
    public void setProcessing(boolean processing) {
        Platform.runLater(() -> {
            isProcessing.set(processing);
            if (!processing) {
                isCancelling.set(false);
            }
        });
    }

    /**
     * Sets the cancelling state.
     *
     * @param cancelling {@code true} if a batch is cancelling
     */
    public void setCancelling(boolean cancelling) {
        Platform.runLater(() -> isCancelling.set(cancelling));
    }

    /**
     * Sets the status messages.
     *
     * @param status the main status message
     * @param detail the detailed status message
     */
    public void setStatus(String status, String detail) {
        Platform.runLater(() -> {
            processingStatus.set(status != null ? status : "");
            detailedStatus.set(detail != null ? detail : "");
        });
    }

    /**
     * Sets the resource status message.
     *
     * @param status the resource status message
     */
    public void setResourceStatus(String status) {
        Platform.runLater(() -> resourceStatus.set(status != null ? status : ""));
    }

    // ===== Progress =====
    private final DoubleProperty overallProgress = new SimpleDoubleProperty(0.0);
    private final IntegerProperty totalFiles = new SimpleIntegerProperty(0);
    private final IntegerProperty completedFiles = new SimpleIntegerProperty(0);
    private final IntegerProperty failedFiles = new SimpleIntegerProperty(0);
    private final IntegerProperty pendingFiles = new SimpleIntegerProperty(0);

    /** Returns the overallProgress property for binding. */
    public DoubleProperty overallProgressProperty() { return overallProgress; }

    /** Returns the totalFiles property for binding. */
    public IntegerProperty totalFilesProperty() { return totalFiles; }

    /** Returns the completedFiles property for binding. */
    public IntegerProperty completedFilesProperty() { return completedFiles; }

    /** Returns the failedFiles property for binding. */
    public IntegerProperty failedFilesProperty() { return failedFiles; }

    /** Returns the pendingFiles property for binding. */
    public IntegerProperty pendingFilesProperty() { return pendingFiles; }

    /**
     * Returns the overall progress (0.0 to 1.0).
     *
     * @return the overall progress
     */
    public double getOverallProgress() { return overallProgress.get(); }

    /**
     * Returns the total number of files.
     *
     * @return the total file count
     */
    public int getTotalFiles() { return totalFiles.get(); }

    /**
     * Returns the number of completed files.
     *
     * @return the completed file count
     */
    public int getCompletedFiles() { return completedFiles.get(); }

    /**
     * Returns the number of failed files.
     *
     * @return the failed file count
     */
    public int getFailedFiles() { return failedFiles.get(); }

    /**
     * Returns the number of pending files.
     *
     * @return the pending file count
     */
    public int getPendingFiles() { return pendingFiles.get(); }

    /**
     * Sets the overall progress.
     *
     * @param progress the progress value (0.0 to 1.0)
     */
    public void setOverallProgress(double progress) {
        Platform.runLater(() ->
            overallProgress.set(Math.min(1.0, Math.max(0.0, progress)))
        );
    }

    /**
     * Updates batch statistics.
     *
     * @param total the total number of files
     * @param completed the number of completed files
     * @param failed the number of failed files
     * @param pending the number of pending files
     */
    public void updateBatchStats(int total, int completed, int failed, int pending) {
        Platform.runLater(() -> {
            totalFiles.set(total);
            completedFiles.set(completed);
            failedFiles.set(failed);
            pendingFiles.set(pending);

            // Calculate total duration from batch files
            double totalSeconds = 0.0;
            for (BatchFileItem item : batchFiles) {
                totalSeconds += item.getTotalAudioDurationSeconds();
            }
            totalDuration.set(formatDuration((long) totalSeconds));

            if (total > 0) {
                double progress = (double) (completed + failed) / total;
                overallProgress.set(Math.min(1.0, progress));
            }
        });
    }

    /**
     * Resets batch statistics.
     */
    public void resetBatchStats() {
        Platform.runLater(() -> {
            totalFiles.set(0);
            completedFiles.set(0);
            failedFiles.set(0);
            pendingFiles.set(0);
            overallProgress.set(0.0);
        });
    }

    // ===== Time Estimates =====
    private final LongProperty fileTimeSpentMs = new SimpleLongProperty(0);
    private final LongProperty fileTimeLeftMs = new SimpleLongProperty(0);
    private final LongProperty totalTimeSpentMs = new SimpleLongProperty(0);
    private final LongProperty totalTimeLeftMs = new SimpleLongProperty(0);

    /** Returns the fileTimeSpentMs property for binding. */
    public LongProperty fileTimeSpentMsProperty() { return fileTimeSpentMs; }

    /** Returns the fileTimeLeftMs property for binding. */
    public LongProperty fileTimeLeftMsProperty() { return fileTimeLeftMs; }

    /** Returns the totalTimeSpentMs property for binding. */
    public LongProperty totalTimeSpentMsProperty() { return totalTimeSpentMs; }

    /** Returns the totalTimeLeftMs property for binding. */
    public LongProperty totalTimeLeftMsProperty() { return totalTimeLeftMs; }

    /**
     * Returns the time spent on the current file in milliseconds.
     *
     * @return the time spent
     */
    public long getFileTimeSpentMs() { return fileTimeSpentMs.get(); }

    /**
     * Returns the estimated time remaining for the current file.
     *
     * @return the estimated time in milliseconds
     */
    public long getFileTimeLeftMs() { return fileTimeLeftMs.get(); }

    /**
     * Returns the total time spent in milliseconds.
     *
     * @return the total time spent
     */
    public long getTotalTimeSpentMs() { return totalTimeSpentMs.get(); }

    /**
     * Returns the estimated total time remaining.
     *
     * @return the estimated time in milliseconds
     */
    public long getTotalTimeLeftMs() { return totalTimeLeftMs.get(); }

    /**
     * Updates time estimates.
     *
     * @param fileSpent the time spent on the current file
     * @param fileLeft the estimated time remaining for the current file
     * @param totalSpent the total time spent
     * @param totalLeft the estimated total time remaining
     */
    public void updateTimeEstimates(long fileSpent, long fileLeft, long totalSpent, long totalLeft) {
        Platform.runLater(() -> {
            fileTimeSpentMs.set(fileSpent);
            fileTimeLeftMs.set(Math.max(0, fileLeft));
            totalTimeSpentMs.set(totalSpent);
            totalTimeLeftMs.set(Math.max(0, totalLeft));
        });
    }

    // ===== UI Settings =====
    private final DoubleProperty fontSize = new SimpleDoubleProperty(12.0);
    private final BooleanProperty darkMode = new SimpleBooleanProperty(false);

    /** Returns the fontSize property for binding. */
    public DoubleProperty fontSizeProperty() { return fontSize; }

    /** Returns the darkMode property for binding. */
    public BooleanProperty darkModeProperty() { return darkMode; }

    /**
     * Returns the font size.
     *
     * @return the font size
     */
    public double getFontSize() { return fontSize.get(); }

    /**
     * Returns whether dark mode is enabled.
     *
     * @return {@code true} if dark mode is enabled
     */
    public boolean isDarkMode() { return darkMode.get(); }

    /**
     * Sets the font size.
     *
     * @param size the font size (clamped to 8-20)
     */
    public void setFontSize(double size) {
        Platform.runLater(() -> fontSize.set(Math.max(8, Math.min(20, size))));
    }

    /**
     * Sets whether dark mode is enabled.
     *
     * @param dark {@code true} to enable dark mode
     */
    public void setDarkMode(boolean dark) {
        Platform.runLater(() -> darkMode.set(dark));
    }

    // ===== Individual File Progress (for UI updates) =====
    private final AtomicReference<Runnable> progressUpdateCallback = new AtomicReference<>();

    /**
     * Sets a callback that will be invoked when individual file progress
     * needs to be refreshed.
     *
     * <p>Used by ControlPanel to update individual progress rows.</p>
     *
     * @param callback the callback to invoke
     */
    public void setProgressUpdateCallback(Runnable callback) {
        progressUpdateCallback.set(callback);
    }

    /**
     * Notifies that file progress has been updated.
     */
    public void notifyProgressUpdate() {
        Runnable callback = progressUpdateCallback.get();
        if (callback != null) {
            Platform.runLater(callback);
        }
    }

    // ===== Batch Reset =====

    /**
     * Resets application state for a new batch.
     *
     * @param total the total number of files in the new batch
     */
    public void resetForNewBatch(int total) {
        Platform.runLater(() -> {
            totalFiles.set(total);
            completedFiles.set(0);
            failedFiles.set(0);
            pendingFiles.set(total);
            overallProgress.set(0.0);
            fileTimeSpentMs.set(0);
            fileTimeLeftMs.set(0);
            totalTimeSpentMs.set(0);
            totalTimeLeftMs.set(0);
            setStatus("Processing", "Starting batch...");
        });
    }

    /**
     * Resets all application state to default values.
     */
    public void reset() {
        Platform.runLater(() -> {
            batchFiles.clear();
            resetBatchStats();
            setStatus("Ready", "");
            setProcessing(false);
            setCancelling(false);
            setResourceStatus("");
        });
    }

    // ===== Helper Methods =====

    /**
     * Formats duration in seconds to a readable string.
     *
     * @param totalSeconds the total duration in seconds
     * @return a formatted duration string (e.g., "2m 30s", "1h 15m 30s")
     */
    private String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0s";
        }

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}