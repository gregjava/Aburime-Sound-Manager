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
 * <p>This class holds all the state information for a file being processed,
 * including:
 * <ul>
 *   <li><b>File information:</b> The actual file object and derived metadata</li>
 *   <li><b>Processing status:</b> Current status, progress, and error messages</li>
 *   <li><b>Results:</b> Output file and audio duration</li>
 *   <li><b>User metadata:</b> Display name and notes for file annotation</li>
 *   <li><b>Priority:</b> Processing priority level (HIGH, NORMAL, LOW)</li>
 * </ul>
 *
 * <p><b>Thread-safety:</b> This class uses JavaFX properties for thread-safe
 * updates from background threads.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see ProcessingStatus
 * @see Priority
 */
public class BatchFileItem {

    /**
     * Simple three-level priority — higher-priority pending files are processed first.
     */
    public enum Priority { HIGH, NORMAL, LOW }

    private final File file;
    private final SimpleStringProperty status;
    private final SimpleDoubleProperty progress;
    private File result;
    private String errorMessage;
    private long startTime = 0;
    private volatile double totalAudioDurationSeconds = 0.0;
    private volatile Priority priority = Priority.NORMAL;

    // FIX: DELIBERATELY a DISPLAY-name override, not a filesystem rename.
    // Renaming the actual File on disk mid-batch is a destructive operation
    // with real failure modes. This changes purely how the file is labeled
    // in the UI/logs — defaults to the real file name so every existing
    // call site that expects getDisplayName() to return something sensible
    // keeps working.
    private final SimpleStringProperty displayName;
    private final SimpleStringProperty notes;

    /**
     * Constructs a new BatchFileItem from a file.
     *
     * @param file the audio file to process
     */
    public BatchFileItem(File file) {
        this.file = file;
        this.status = new SimpleStringProperty(ProcessingStatus.PENDING.name());
        this.progress = new SimpleDoubleProperty(0.0);
        this.displayName = new SimpleStringProperty(file.getName());
        this.notes = new SimpleStringProperty("");
    }

    /**
     * Returns the file object.
     *
     * @return the file
     */
    public File getFile() { return file; }

    /**
     * Returns the file name.
     *
     * @return the file name
     */
    public String getFileName() { return file.getName(); }

    /**
     * Returns the current status string.
     *
     * @return the status
     */
    public String getStatus() { return status.get(); }

    /**
     * Sets the processing status.
     *
     * @param status the new status
     */
    public void setStatus(String status) { this.status.set(status); }

    /**
     * Returns the status property for binding.
     *
     * @return the status property
     */
    public SimpleStringProperty statusProperty() { return status; }

    /**
     * Returns the current progress (0.0 to 1.0).
     *
     * @return the progress
     */
    public double getProgress() { return progress.get(); }

    /**
     * Sets the progress.
     *
     * @param progress the progress value (0.0 to 1.0)
     */
    public void setProgress(double progress) { this.progress.set(progress); }

    /**
     * Returns the progress property for binding.
     *
     * @return the progress property
     */
    public SimpleDoubleProperty progressProperty() { return progress; }

    /**
     * Returns the output result file.
     *
     * @return the result file, or {@code null} if not processed
     */
    public File getResult() { return result; }

    /**
     * Sets the output result file.
     *
     * @param resultFile the result file
     */
    public void setResult(File resultFile) { this.result = resultFile; }

    /**
     * Returns the error message.
     *
     * @return the error message, or {@code null} if no error
     */
    public String getErrorMessage() { return errorMessage; }

    /**
     * Sets the error message.
     *
     * @param errorMessage the error message
     */
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    /**
     * Returns the processing start time.
     *
     * @return the start time in milliseconds
     */
    public long getStartTime() { return startTime; }

    /**
     * Sets the processing start time.
     *
     * @param startTime the start time in milliseconds
     */
    public void setStartTime(long startTime) { this.startTime = startTime; }

    /**
     * Returns the total audio duration in seconds.
     *
     * @return the audio duration
     */
    public double getTotalAudioDurationSeconds() { return totalAudioDurationSeconds; }

    /**
     * Sets the total audio duration in seconds.
     *
     * @param totalAudioDurationSeconds the audio duration
     */
    public void setTotalAudioDurationSeconds(double totalAudioDurationSeconds) {
        this.totalAudioDurationSeconds = totalAudioDurationSeconds;
    }

    /**
     * Returns the priority level.
     *
     * @return the priority
     */
    public Priority getPriority() { return priority; }

    /**
     * Sets the priority level.
     *
     * @param priority the priority (never {@code null})
     */
    public void setPriority(Priority priority) { this.priority = priority != null ? priority : Priority.NORMAL; }

    /**
     * Returns the display name shown in the UI.
     *
     * @return the display name
     */
    public String getDisplayName() { return displayName.get(); }

    /**
     * Sets the display name shown in the UI.
     *
     * <p>Blank/null values reset to the real file name.</p>
     *
     * @param name the display name
     */
    public void setDisplayName(String name) {
        displayName.set((name == null || name.isBlank()) ? file.getName() : name);
    }

    /**
     * Returns the display name property for binding.
     *
     * @return the display name property
     */
    public SimpleStringProperty displayNameProperty() { return displayName; }

    /**
     * Returns whether the user has set a custom display name.
     *
     * @return {@code true} if a custom display name is set
     */
    public boolean hasCustomDisplayName() { return !displayName.get().equals(file.getName()); }

    /**
     * Returns the free-text notes for this file.
     *
     * @return the notes
     */
    public String getNotes() { return notes.get(); }

    /**
     * Sets the free-text notes for this file.
     *
     * @param notes the notes (null becomes empty string)
     */
    public void setNotes(String notes) { this.notes.set(notes == null ? "" : notes); }

    /**
     * Returns the notes property for binding.
     *
     * @return the notes property
     */
    public SimpleStringProperty notesProperty() { return notes; }

    /**
     * Returns whether this item has notes.
     *
     * @return {@code true} if notes are present
     */
    public boolean hasNotes() { return !notes.get().isBlank(); }

    /**
     * Fine-grained progress (0.0–1.0) within the current stage.
     *
     * <p>Distinct from the coarse pipeline-stage progress the existing
     * progress property tracks. Used by BatchProgressAggregator for
     * real-time overall-batch percentage.</p>
     */
    private volatile double individualProgress = 0.0;

    /**
     * Returns the individual file progress.
     *
     * @return the individual progress (0.0 to 1.0)
     */
    public double getIndividualProgress() { return individualProgress; }

    /**
     * Sets the individual file progress.
     *
     * @param individualProgress the progress value (0.0 to 1.0)
     */
    public void setIndividualProgress(double individualProgress) { this.individualProgress = individualProgress; }

    @Override
    public String toString() {
        return String.format("BatchFileItem{file=%s, status=%s, progress=%.2f}",
                           file.getName(), getStatus(), getProgress());
    }
}