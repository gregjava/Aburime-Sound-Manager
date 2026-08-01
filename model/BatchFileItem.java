/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import javafx.beans.property.*;

import java.io.File;

/**
 * Represents a file in the batch processing queue
 */
public class BatchFileItem {
    private final File file;
    private final SimpleStringProperty status;
    private final SimpleDoubleProperty progress;
    private final SimpleStringProperty statusProperty;
    private File result;
    private String errorMessage;
    private long startTime = 0;

    // Stage tracking properties
    private final SimpleStringProperty currentStageName = new SimpleStringProperty("PENDING");
    private volatile long currentStageStartTime = 0;
    private volatile double currentStageEstimatedDuration = 0.0;
    private volatile double totalAudioDurationSeconds = 0.0;

    // 🚨 NEW: Enhanced properties for UI updates
    private final DoubleProperty individualProgress = new SimpleDoubleProperty(0.0);
    private final StringProperty estimatedTimeRemaining = new SimpleStringProperty("");
    private final LongProperty fileDuration = new SimpleLongProperty(0); // in seconds

    public BatchFileItem(File file) {
        this.file = file;
        this.status = new SimpleStringProperty(ProcessingStatus.PENDING.name());
        this.progress = new SimpleDoubleProperty(0.0);
        this.statusProperty = new SimpleStringProperty("PENDING");
    }

    // Getters and setters
    /**
     * Retrieves the digital play duration of the audio file in seconds.
     * Re-purposed to return the mutable field's value.
     */
    public double getDurationSeconds() { 
        // This is the getter used by the UI table/status calculations.
        return totalAudioDurationSeconds; 
    }
    
    public File getFile() { return file; }
    public String getFileName() { return file.getName(); }
    
    public String getStatus() { return status.get(); }
    public void setStatus(String status) { 
        this.status.set(status);
        this.statusProperty.set(status);
    }
    public SimpleStringProperty statusProperty() { return statusProperty; }
    
    public double getProgress() { return progress.get(); }
    public void setProgress(double progress) { this.progress.set(progress); }
    public SimpleDoubleProperty progressProperty() { return progress; }

    public File getResult() { return result; }
    public void setResult(File resultFile) { this.result = resultFile; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    // Stage tracking methods
    public String getCurrentStageName() { return currentStageName.get(); }
    public void setCurrentStageName(String currentStageName) { this.currentStageName.set(currentStageName); }
    public SimpleStringProperty currentStageNameProperty() { return currentStageName; }

    public long getCurrentStageStartTime() { return currentStageStartTime; }
    public void setCurrentStageStartTime(long currentStageStartTime) { this.currentStageStartTime = currentStageStartTime; }

    public double getCurrentStageEstimatedDuration() { return currentStageEstimatedDuration; }
    public void setCurrentStageEstimatedDuration(double currentStageEstimatedDuration) { 
        this.currentStageEstimatedDuration = currentStageEstimatedDuration; 
    }

    public double getTotalAudioDurationSeconds() { return totalAudioDurationSeconds; }
    public void setTotalAudioDurationSeconds(double totalAudioDurationSeconds) { 
        this.totalAudioDurationSeconds = totalAudioDurationSeconds; 
        this.fileDuration.set((long) totalAudioDurationSeconds); // Sync with new property
    }
    
    // 🚨 NEW: Enhanced property getters and setters
    public double getIndividualProgress() { return individualProgress.get(); }
    public void setIndividualProgress(double progress) { this.individualProgress.set(progress); }
    public DoubleProperty individualProgressProperty() { return individualProgress; }
    
    public String getEstimatedTimeRemaining() { return estimatedTimeRemaining.get(); }
    public void setEstimatedTimeRemaining(String eta) { this.estimatedTimeRemaining.set(eta); }
    public StringProperty estimatedTimeRemainingProperty() { return estimatedTimeRemaining; }
    
    public long getFileDuration() { return fileDuration.get(); }
    public void setFileDuration(long duration) { this.fileDuration.set(duration); }
    public LongProperty fileDurationProperty() { return fileDuration; }

    // Dynamic weight properties
    private volatile double conversionWeight = 0.2;
    private volatile double transcriptionWeight = 0.8;
    private TranscriptionConfig transcriptionConfig;

    // Add getters and setters
    public double getConversionWeight() { return conversionWeight; }
    public void setConversionWeight(double conversionWeight) { this.conversionWeight = conversionWeight; }
    
    public double getTranscriptionWeight() { return transcriptionWeight; }
    public void setTranscriptionWeight(double transcriptionWeight) { this.transcriptionWeight = transcriptionWeight; }
    
    public TranscriptionConfig getTranscriptionConfig() { return transcriptionConfig; }
    public void setTranscriptionConfig(TranscriptionConfig transcriptionConfig) { 
        this.transcriptionConfig = transcriptionConfig; 
    }

    @Override
    public String toString() {
        return String.format("BatchFileItem{file=%s, status=%s, progress=%.2f}", 
                           file.getName(), getStatus(), getProgress());
    }
}
