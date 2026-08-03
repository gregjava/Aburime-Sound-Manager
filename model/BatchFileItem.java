/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;

import java.io.File;

/**
 * Represents a file in the batch processing queue.
 *
 * <p>FIX (dead-code cleanup): this class previously carried several fields
 * and their accessors that were never read or written anywhere else in the
 * codebase I have visibility into — a duplicate {@code status}/
 * {@code statusProperty} pair tracking the same value twice, an
 * always-broken {@code getDurationSeconds()} that other code had already
 * fallen back away from (see the "FIX: was getDurationSeconds()..."
 * comments still in {@code FileSelectionPanel}), an entirely unused "stage
 * tracking" subsystem ({@code currentStageName}/{@code currentStageStartTime}/
 * {@code currentStageEstimatedDuration}), an unused "enhanced properties"
 * set ({@code individualProgress}/{@code estimatedTimeRemaining}/
 * {@code fileDuration}), and an unused "dynamic weight" subsystem
 * ({@code conversionWeight}/{@code transcriptionWeight}/
 * {@code transcriptionConfig}). All removed. If any of these turn out to be
 * used by a file outside what I've been given visibility into, they're
 * recoverable from version control — but nothing in the reviewed codebase
 * referenced them.</p>
 */
public class BatchFileItem {
    private final File file;
    private final SimpleStringProperty status;
    private final SimpleDoubleProperty progress;
    private File result;
    private String errorMessage;
    private long startTime = 0;
    private volatile double totalAudioDurationSeconds = 0.0;

    public BatchFileItem(File file) {
        this.file = file;
        this.status = new SimpleStringProperty(ProcessingStatus.PENDING.name());
        this.progress = new SimpleDoubleProperty(0.0);
    }

    public File getFile() { return file; }
    public String getFileName() { return file.getName(); }

    public String getStatus() { return status.get(); }
    public void setStatus(String status) { this.status.set(status); }
    public SimpleStringProperty statusProperty() { return status; }

    public double getProgress() { return progress.get(); }
    public void setProgress(double progress) { this.progress.set(progress); }
    public SimpleDoubleProperty progressProperty() { return progress; }

    public File getResult() { return result; }
    public void setResult(File resultFile) { this.result = resultFile; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public double getTotalAudioDurationSeconds() { return totalAudioDurationSeconds; }
    public void setTotalAudioDurationSeconds(double totalAudioDurationSeconds) {
        this.totalAudioDurationSeconds = totalAudioDurationSeconds;
    }

    @Override
    public String toString() {
        return String.format("BatchFileItem{file=%s, status=%s, progress=%.2f}",
                           file.getName(), getStatus(), getProgress());
    }
}