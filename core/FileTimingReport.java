/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-file stage-by-stage timing breakdown, combining Java-side measured
 * stages (preprocessing, output saving, cleanup) with the Python script's
 * self-reported internal stages (model load, audio load, transcription,
 * alignment, diarization) parsed from its {@code STAGE_TIMING:} log lines.
 *
 * <p>Previously this data existed only scattered across the log file (some
 * of it not at all, before the Python script gained timing instrumentation).
 * This is the structured, in-memory form surfaced in the UI's Performance
 * Report dialog.</p>
 */
public class FileTimingReport {

    private final String fileName;
    private final long timestamp = System.currentTimeMillis();
    private final Map<String, Long> stageMillis = new LinkedHashMap<>();
    private long peakHeapUsedMB = -1;
    private double avgCpuLoadPercent = -1;

    // FIX (wall-clock timeline): stageMillis stores DURATIONS (how long a
    // stage took), which is what most consumers of this class actually
    // want — but it can't answer "what time did this stage start, relative
    // to when the batch itself started?" A duration-only report makes you
    // manually reconstruct that by summing every prior stage's duration,
    // which is exactly the "screenshot-cross-referencing exercise" this
    // was built to eliminate. Kept as a SEPARATE map rather than
    // overloading setStage/getStageMillis (which are typed and named for
    // durations throughout the existing UI/log code) — this is purely
    // additive, so nothing that reads durations today is affected.
    private long batchStartEpochMs = -1;
    private final Map<String, Long> stageStartEpochMs = new LinkedHashMap<>();

    /** The wall-clock time (epoch ms) the whole batch this file belongs to started — set once, shared across every file in that batch. */
    public void setBatchStartEpochMs(long epochMs) {
        this.batchStartEpochMs = epochMs;
    }

    public long getBatchStartEpochMs() {
        return batchStartEpochMs;
    }

    /** Records the wall-clock time (epoch ms) a named stage boundary was reached — e.g. "queue_entered", "preprocess_start", "model_acquired", "transcribe_start", "save_start". */
    public void setStageEpoch(String stageName, long epochMs) {
        stageStartEpochMs.put(stageName, epochMs);
    }

    /** Epoch ms for a stage boundary previously recorded via {@link #setStageEpoch}, or -1 if that stage wasn't recorded (e.g. a script without STAGE_TIMING instrumentation, or a stage this file's path skipped). */
    public long getStageEpoch(String stageName) {
        return stageStartEpochMs.getOrDefault(stageName, -1L);
    }

    /** Milliseconds from batch start to this stage boundary, or -1 if either isn't known. This is the number that answers "how far into the batch was this file at stage X?" without the caller having to do the subtraction themselves. */
    public long getElapsedSinceBatchStartMs(String stageName) {
        long stageEpoch = getStageEpoch(stageName);
        if (stageEpoch < 0 || batchStartEpochMs < 0) return -1;
        return stageEpoch - batchStartEpochMs;
    }

    public Map<String, Long> getStageStartEpochMs() {
        return stageStartEpochMs;
    }

    // FIX (doc-review — "Average CPU utilization" / "Peak memory usage"):
    // the fields above are a single JVM-side system-CPU snapshot taken when
    // the file finished, and this JVM's own heap usage — neither is what
    // was actually asked for, which is the resource usage of the
    // transcription work itself. These two are the real thing: sampled by
    // the Python script throughout the whole transcribe() call (RSS memory
    // and CPU% of the Python process and any children), reported via
    // STAGE_TIMING:peak_memory_mb / STAGE_TIMING:avg_cpu_percent. -1 means
    // psutil wasn't installed in that Python venv, not that usage was zero.
    private double pythonPeakMemoryMb = -1;
    private double pythonAvgCpuPercent = -1;

    public void setPythonPeakMemoryMb(double mb) { this.pythonPeakMemoryMb = mb; }
    public void setPythonAvgCpuPercent(double percent) { this.pythonAvgCpuPercent = percent; }
    public double getPythonPeakMemoryMb() { return pythonPeakMemoryMb; }
    public double getPythonAvgCpuPercent() { return pythonAvgCpuPercent; }

    public FileTimingReport(String fileName) {
        this.fileName = fileName;
    }

    public void setStage(String stageName, long millis) {
        stageMillis.put(stageName, millis);
    }

    public void setPeakHeapUsedMB(long mb) {
        this.peakHeapUsedMB = mb;
    }

    public void setAvgCpuLoadPercent(double percent) {
        this.avgCpuLoadPercent = percent;
    }

    public String getFileName() { return fileName; }
    public long getTimestamp() { return timestamp; }
    public Map<String, Long> getStageMillis() { return stageMillis; }
    public long getStageMillis(String stage) { return stageMillis.getOrDefault(stage, -1L); }
    public long getPeakHeapUsedMB() { return peakHeapUsedMB; }
    public double getAvgCpuLoadPercent() { return avgCpuLoadPercent; }

    public long getTotalMillis() {
        return stageMillis.getOrDefault("total_pipeline", -1L);
    }
}