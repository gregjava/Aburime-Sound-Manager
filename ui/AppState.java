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
 */
public class AppState {
    
    // ===== Singleton =====
    private static final AppState INSTANCE = new AppState();
    
    public static AppState getInstance() {
        return INSTANCE;
    }
    
    private AppState() {
        // Private constructor for singleton
    }
    
    // ===== Batch Queue =====
    private final ObservableList<BatchFileItem> batchFiles = FXCollections.observableArrayList();
    
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
    
    public BooleanProperty isProcessingProperty() { return isProcessing; }
    public BooleanProperty isCancellingProperty() { return isCancelling; }
    public StringProperty processingStatusProperty() { return processingStatus; }
    public StringProperty detailedStatusProperty() { return detailedStatus; }
    public StringProperty resourceStatusProperty() { return resourceStatus; }

    public StringProperty totalDurationProperty() {
        return totalDuration;
    }

    public String getTotalDuration() {
        return totalDuration.get();
    }

    public void setTotalDuration(String duration) {
        Platform.runLater(() -> totalDuration.set(duration));
    }

    public BooleanProperty dependencyCheckInProgressProperty() {
        return dependencyCheckInProgress;
    }

    public boolean isDependencyCheckInProgress() {
        return dependencyCheckInProgress.get();
    }

    public void setDependencyCheckInProgress(boolean inProgress) {
        Platform.runLater(() -> dependencyCheckInProgress.set(inProgress));
    }
    
    public boolean isProcessing() { return isProcessing.get(); }
    public boolean isCancelling() { return isCancelling.get(); }
    
    public void setProcessing(boolean processing) {
        Platform.runLater(() -> {
            isProcessing.set(processing);
            if (!processing) {
                isCancelling.set(false);
            }
        });
    }
    
    public void setCancelling(boolean cancelling) {
        Platform.runLater(() -> isCancelling.set(cancelling));
    }
    
    public void setStatus(String status, String detail) {
        Platform.runLater(() -> {
            processingStatus.set(status != null ? status : "");
            detailedStatus.set(detail != null ? detail : "");
        });
    }
    
    public void setResourceStatus(String status) {
        Platform.runLater(() -> resourceStatus.set(status != null ? status : ""));
    }
    
    // ===== Progress =====
    private final DoubleProperty overallProgress = new SimpleDoubleProperty(0.0);
    private final IntegerProperty totalFiles = new SimpleIntegerProperty(0);
    private final IntegerProperty completedFiles = new SimpleIntegerProperty(0);
    private final IntegerProperty failedFiles = new SimpleIntegerProperty(0);
    private final IntegerProperty pendingFiles = new SimpleIntegerProperty(0);
    
    public DoubleProperty overallProgressProperty() { return overallProgress; }
    public IntegerProperty totalFilesProperty() { return totalFiles; }
    public IntegerProperty completedFilesProperty() { return completedFiles; }
    public IntegerProperty failedFilesProperty() { return failedFiles; }
    public IntegerProperty pendingFilesProperty() { return pendingFiles; }
    
    public double getOverallProgress() { return overallProgress.get(); }
    public int getTotalFiles() { return totalFiles.get(); }
    public int getCompletedFiles() { return completedFiles.get(); }
    public int getFailedFiles() { return failedFiles.get(); }
    public int getPendingFiles() { return pendingFiles.get(); }
    
    public void setOverallProgress(double progress) {
        Platform.runLater(() -> 
            overallProgress.set(Math.min(1.0, Math.max(0.0, progress)))
        );
    }
    
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
    
    public LongProperty fileTimeSpentMsProperty() { return fileTimeSpentMs; }
    public LongProperty fileTimeLeftMsProperty() { return fileTimeLeftMs; }
    public LongProperty totalTimeSpentMsProperty() { return totalTimeSpentMs; }
    public LongProperty totalTimeLeftMsProperty() { return totalTimeLeftMs; }
    
    public long getFileTimeSpentMs() { return fileTimeSpentMs.get(); }
    public long getFileTimeLeftMs() { return fileTimeLeftMs.get(); }
    public long getTotalTimeSpentMs() { return totalTimeSpentMs.get(); }
    public long getTotalTimeLeftMs() { return totalTimeLeftMs.get(); }
    
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
    
    public DoubleProperty fontSizeProperty() { return fontSize; }
    public BooleanProperty darkModeProperty() { return darkMode; }
    
    public double getFontSize() { return fontSize.get(); }
    public boolean isDarkMode() { return darkMode.get(); }
    
    public void setFontSize(double size) {
        Platform.runLater(() -> fontSize.set(Math.max(8, Math.min(20, size))));
    }
    
    public void setDarkMode(boolean dark) {
        Platform.runLater(() -> darkMode.set(dark));
    }
    
    // ===== Individual File Progress (for UI updates) =====
    private final AtomicReference<Runnable> progressUpdateCallback = new AtomicReference<>();
    
    /**
     * Sets a callback that will be invoked when individual file progress
     * needs to be refreshed. Used by ControlPanel to update individual progress rows.
     */
    public void setProgressUpdateCallback(Runnable callback) {
        progressUpdateCallback.set(callback);
    }
    
    public void notifyProgressUpdate() {
        Runnable callback = progressUpdateCallback.get();
        if (callback != null) {
            Platform.runLater(callback);
        }
    }
    
    // ===== Batch Reset =====
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
     * Format duration in seconds to a readable string.
     * @param totalSeconds total duration in seconds
     * @return formatted duration string (e.g., "2m 30s", "1h 15m 30s")
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