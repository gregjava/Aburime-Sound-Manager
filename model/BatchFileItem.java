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

    /** Simple three-level priority — higher-priority pending files are processed first. */
    public enum Priority { HIGH, NORMAL, LOW }

    private final File file;
    private final SimpleStringProperty status;
    private final SimpleDoubleProperty progress;
    private File result;
    private String errorMessage;
    private long startTime = 0;
    private volatile double totalAudioDurationSeconds = 0.0;
    private volatile Priority priority = Priority.NORMAL;

    // FIX (batch editing — rename/metadata): deliberately a DISPLAY-name
    // override, not a filesystem rename. Renaming the actual File on disk
    // mid-batch (or even pre-batch) is a genuinely destructive operation
    // with real failure modes of its own (a file mid-copy/mid-read
    // elsewhere, a name collision, permissions) for comparatively little
    // benefit — everything downstream (output writers, FFmpeg/WhisperX
    // invocations) keeps using getFile()/getFileName() completely
    // unmodified, so this cannot affect processing correctness. What it
    // changes is purely how the file is labeled in this app's own UI/logs
    // — useful for telling apart several similarly-auto-named recordings,
    // annotating a client name, etc. Defaults to the real file name so
    // every existing call site that expects getDisplayName() to always
    // return something sensible keeps working with zero changes.
    private final SimpleStringProperty displayName;

    /** Free-text per-file note (client name, batch context, anything the user wants to remember about this specific file). Empty string, never null, by default. */
    private final SimpleStringProperty notes;

    public BatchFileItem(File file) {
        this.file = file;
        this.status = new SimpleStringProperty(ProcessingStatus.PENDING.name());
        this.progress = new SimpleDoubleProperty(0.0);
        this.displayName = new SimpleStringProperty(file.getName());
        this.notes = new SimpleStringProperty("");
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

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority != null ? priority : Priority.NORMAL; }

    public String getDisplayName() { return displayName.get(); }
    /** Blank/null resets to the real file name rather than storing an empty label. */
    public void setDisplayName(String name) {
        displayName.set((name == null || name.isBlank()) ? file.getName() : name);
    }
    public SimpleStringProperty displayNameProperty() { return displayName; }
    /** True if the user has set a custom label distinct from the real file name. */
    public boolean hasCustomDisplayName() { return !displayName.get().equals(file.getName()); }

    public String getNotes() { return notes.get(); }
    public void setNotes(String notes) { this.notes.set(notes == null ? "" : notes); }
    public SimpleStringProperty notesProperty() { return notes; }
    public boolean hasNotes() { return !notes.get().isBlank(); }

    @Override
    public String toString() {
        return String.format("BatchFileItem{file=%s, status=%s, progress=%.2f}",
                           file.getName(), getStatus(), getProgress());
    }
    
    private volatile double individualProgress = 0.0;

    /** Fine-grained progress (0.0–1.0) within the current stage, distinct from the coarse pipeline-stage progress the existing progress property tracks. Used by BatchProgressAggregator for real-time overall-batch percentage. */
    public double getIndividualProgress() { return individualProgress; }
    public void setIndividualProgress(double individualProgress) { this.individualProgress = individualProgress; }
}