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