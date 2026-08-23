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
 * 
 * <p>Now includes GPU usage tracking to show whether GPU acceleration
 * was used for each file processed.</p>
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

    // Architectural spec: logging must self-identify the processing mode
    // (Adaptive / Baseline-Conservative / Baseline-Naive) while staying
    // structurally identical otherwise — same batch-summary format, same
    // wall-clock fields, same peak RAM/CPU capture — so the existing
    // adaptive-mode analysis script keeps working unmodified on baseline
    // logs too, with the mode as just one more column rather than a
    // reason to build a second parsing pipeline.
    private String processingMode = "ADAPTIVE";

    public void setProcessingMode(String mode) { this.processingMode = mode; }
    public String getProcessingMode() { return processingMode; }

    // Architectural spec, failure semantics: under baseline mode a whole
    // file either succeeds or throws — no partial credit, no retry. What
    // specifically failed (exception type + message) is the evidence that
    // makes a fault-tolerance comparison concrete rather than just "fewer
    // files completed", so it's captured here rather than only as a
    // pass/fail count — and this is populated in both modes, not only
    // baseline, since the same evidence is just as useful when adaptive
    // mode's retry logic ultimately still fails a file.
    private String failureExceptionType = null;
    private String failureExceptionMessage = null;

    public void setFailure(Throwable t) {
        if (t == null) return;
        this.failureExceptionType = t.getClass().getName();
        this.failureExceptionMessage = t.getMessage();
    }

    public String getFailureExceptionType() { return failureExceptionType; }
    public String getFailureExceptionMessage() { return failureExceptionMessage; }
    public boolean isFailed() { return failureExceptionType != null; }

    // ===== GPU Support =====
    private boolean gpuUsed = false;
    private String gpuName = "None";
    private String gpuComputeCapability = "N/A";
    private long gpuMemoryMB = 0;

    /**
     * Sets whether GPU acceleration was used for this file.
     */
    public void setGpuUsed(boolean used) { this.gpuUsed = used; }

    /**
     * Returns true if GPU acceleration was used for this file.
     */
    public boolean isGpuUsed() { return gpuUsed; }

    /**
     * Sets the GPU name used for this file (e.g., "NVIDIA GeForce RTX 3080").
     */
    public void setGpuName(String name) { this.gpuName = name != null ? name : "None"; }

    /**
     * Returns the GPU name used for this file.
     */
    public String getGpuName() { return gpuName; }

    /**
     * Sets the GPU compute capability (e.g., "8.6").
     */
    public void setGpuComputeCapability(String capability) { 
        this.gpuComputeCapability = capability != null ? capability : "N/A"; 
    }

    /**
     * Returns the GPU compute capability.
     */
    public String getGpuComputeCapability() { return gpuComputeCapability; }

    /**
     * Sets the GPU memory in MB.
     */
    public void setGpuMemoryMB(long memoryMB) { this.gpuMemoryMB = memoryMB; }

    /**
     * Returns the GPU memory in MB.
     */
    public long getGpuMemoryMB() { return gpuMemoryMB; }

    /**
     * Returns a formatted GPU status string for display.
     */
    public String getGpuStatus() {
        if (!gpuUsed) {
            return "CPU";
        }
        return String.format("GPU: %s (%d MB)", gpuName, gpuMemoryMB);
    }

    /**
     * Returns a detailed GPU info string for display.
     */
    public String getGpuDetails() {
        if (!gpuUsed) {
            return "No GPU acceleration";
        }
        return String.format("%s | Memory: %d MB | Compute: %s", 
            gpuName, gpuMemoryMB, gpuComputeCapability);
    }

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

    // ========================================================================
    //  Builder-style methods for convenient chaining
    // ========================================================================

    /**
     * Convenience method to set all GPU-related fields at once.
     * 
     * @param gpuConfig the GpuConfig instance to read GPU info from
     * @param used whether GPU was actually used for this file
     */
    public void setGpuInfo(GpuConfig gpuConfig, boolean used) {
        setGpuUsed(used);
        if (used && gpuConfig.isGpuAvailable()) {
            setGpuName(gpuConfig.getGpuName());
            setGpuComputeCapability(gpuConfig.getComputeCapability());
            setGpuMemoryMB(gpuConfig.getGpuMemoryMB());
        } else {
            setGpuName("CPU");
            setGpuComputeCapability("N/A");
            setGpuMemoryMB(0);
        }
    }

    /**
     * Creates a copy of this report with GPU info added.
     * Useful for when GPU info is determined after the report is created.
     */
    public FileTimingReport withGpuInfo(GpuConfig gpuConfig, boolean used) {
        FileTimingReport copy = new FileTimingReport(this.fileName);
        copy.stageMillis.putAll(this.stageMillis);
        copy.stageStartEpochMs.putAll(this.stageStartEpochMs);
        copy.batchStartEpochMs = this.batchStartEpochMs;
        copy.peakHeapUsedMB = this.peakHeapUsedMB;
        copy.avgCpuLoadPercent = this.avgCpuLoadPercent;
        copy.pythonPeakMemoryMb = this.pythonPeakMemoryMb;
        copy.pythonAvgCpuPercent = this.pythonAvgCpuPercent;
        copy.processingMode = this.processingMode;
        copy.failureExceptionType = this.failureExceptionType;
        copy.failureExceptionMessage = this.failureExceptionMessage;
        copy.setGpuInfo(gpuConfig, used);
        return copy;
    }

    @Override
    public String toString() {
        return String.format("FileTimingReport{fileName='%s', totalMillis=%d, gpuUsed=%s, gpuName='%s'}",
                fileName, getTotalMillis(), gpuUsed, gpuName);
    }
}