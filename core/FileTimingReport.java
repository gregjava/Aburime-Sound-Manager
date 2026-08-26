/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-file stage-by-stage timing breakdown for performance analysis.
 *
 * <p>This class captures detailed timing information for each file processed
 * through the system, combining:
 * <ul>
 *   <li><b>Java-side measured stages:</b> Preprocessing, output saving, cleanup</li>
 *   <li><b>Python script self-reported stages:</b> Model load, audio load,
 *       transcription, alignment, diarisation</li>
 *   <li><b>Resource usage:</b> Peak heap memory, average CPU load</li>
 *   <li><b>GPU usage:</b> Whether GPU acceleration was used, GPU name, memory</li>
 * </ul>
 *
 * <p>This structured data is used in the Performance Report dialog to show
 * users detailed breakdowns of processing time for each file.</p>
 *
 * <p><b>Timeline support:</b> In addition to stage durations, this class
 * also records wall-clock timestamps for stage boundaries, enabling
 * visualisation of when each stage occurred relative to batch start.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see ParallelProcessingManager
 * @see GpuConfig
 */
public class FileTimingReport {

    private final String fileName;
    private final long timestamp = System.currentTimeMillis();
    private final Map<String, Long> stageMillis = new LinkedHashMap<>();
    private long peakHeapUsedMB = -1;
    private double avgCpuLoadPercent = -1;

    // Timeline support - wall-clock timestamps for stage boundaries
    private long batchStartEpochMs = -1;
    private final Map<String, Long> stageStartEpochMs = new LinkedHashMap<>();

    // Python-side resource usage
    private double pythonPeakMemoryMb = -1;
    private double pythonAvgCpuPercent = -1;

    // Processing mode
    private String processingMode = "ADAPTIVE";

    // Failure information
    private String failureExceptionType = null;
    private String failureExceptionMessage = null;

    // GPU Support
    private boolean gpuUsed = false;
    private String gpuName = "None";
    private String gpuComputeCapability = "N/A";
    private long gpuMemoryMB = 0;

    /**
     * Constructs a new timing report for the specified file.
     *
     * @param fileName the name of the processed file
     */
    public FileTimingReport(String fileName) {
        this.fileName = fileName;
    }

    // ========================================================================
    //  Timeline (Wall-Clock) Methods
    // ========================================================================

    /**
     * Sets the wall-clock time (epoch ms) when the batch started.
     *
     * @param epochMs the batch start time in milliseconds since epoch
     */
    public void setBatchStartEpochMs(long epochMs) {
        this.batchStartEpochMs = epochMs;
    }

    /**
     * Returns the batch start time in epoch milliseconds.
     *
     * @return the batch start epoch in milliseconds
     */
    public long getBatchStartEpochMs() {
        return batchStartEpochMs;
    }

    /**
     * Records the wall-clock time when a stage boundary was reached.
     *
     * <p>Stage names include: {@code queue_entered}, {@code preprocess_start},
     * {@code model_acquired}, {@code transcribe_start}, {@code save_start},
     * {@code completed}.</p>
     *
     * @param stageName the name of the stage
     * @param epochMs the time in milliseconds since epoch
     */
    public void setStageEpoch(String stageName, long epochMs) {
        stageStartEpochMs.put(stageName, epochMs);
    }

    /**
     * Returns the epoch time for a stage boundary.
     *
     * @param stageName the name of the stage
     * @return the epoch time, or {@code -1} if not recorded
     */
    public long getStageEpoch(String stageName) {
        return stageStartEpochMs.getOrDefault(stageName, -1L);
    }

    /**
     * Returns the elapsed time from batch start to a stage boundary.
     *
     * @param stageName the name of the stage
     * @return the elapsed time in milliseconds, or {@code -1} if not available
     */
    public long getElapsedSinceBatchStartMs(String stageName) {
        long stageEpoch = getStageEpoch(stageName);
        if (stageEpoch < 0 || batchStartEpochMs < 0) return -1;
        return stageEpoch - batchStartEpochMs;
    }

    /**
     * Returns a map of all stage start times.
     *
     * @return an unmodifiable view of the stage start times
     */
    public Map<String, Long> getStageStartEpochMs() {
        return stageStartEpochMs;
    }

    // ========================================================================
    //  Stage Duration Methods
    // ========================================================================

    /**
     * Records the duration of a stage.
     *
     * @param stageName the name of the stage
     * @param millis the duration in milliseconds
     */
    public void setStage(String stageName, long millis) {
        stageMillis.put(stageName, millis);
    }

    /**
     * Returns the duration of a stage.
     *
     * @param stage the name of the stage
     * @return the duration in milliseconds, or {@code -1} if not recorded
     */
    public long getStageMillis(String stage) {
        return stageMillis.getOrDefault(stage, -1L);
    }

    /**
     * Returns all stage durations.
     *
     * @return a map of stage names to durations in milliseconds
     */
    public Map<String, Long> getStageMillis() {
        return stageMillis;
    }

    /**
     * Returns the total pipeline time.
     *
     * @return the total time in milliseconds, or {@code -1} if not recorded
     */
    public long getTotalMillis() {
        return stageMillis.getOrDefault("total_pipeline", -1L);
    }

    // ========================================================================
    //  Resource Usage Methods
    // ========================================================================

    /**
     * Sets the peak JVM heap usage.
     *
     * @param mb the peak heap size in megabytes
     */
    public void setPeakHeapUsedMB(long mb) {
        this.peakHeapUsedMB = mb;
    }

    /**
     * Returns the peak JVM heap usage.
     *
     * @return the peak heap size in megabytes
     */
    public long getPeakHeapUsedMB() {
        return peakHeapUsedMB;
    }

    /**
     * Sets the average CPU load percentage.
     *
     * @param percent the average CPU load as a percentage
     */
    public void setAvgCpuLoadPercent(double percent) {
        this.avgCpuLoadPercent = percent;
    }

    /**
     * Returns the average CPU load percentage.
     *
     * @return the average CPU load as a percentage
     */
    public double getAvgCpuLoadPercent() {
        return avgCpuLoadPercent;
    }

    /**
     * Sets the Python process peak memory usage.
     *
     * @param mb the peak memory in megabytes
     */
    public void setPythonPeakMemoryMb(double mb) {
        this.pythonPeakMemoryMb = mb;
    }

    /**
     * Returns the Python process peak memory usage.
     *
     * @return the peak memory in megabytes, or {@code -1} if not available
     */
    public double getPythonPeakMemoryMb() {
        return pythonPeakMemoryMb;
    }

    /**
     * Sets the Python process average CPU usage.
     *
     * @param percent the average CPU usage as a percentage
     */
    public void setPythonAvgCpuPercent(double percent) {
        this.pythonAvgCpuPercent = percent;
    }

    /**
     * Returns the Python process average CPU usage.
     *
     * @return the average CPU usage as a percentage, or {@code -1} if not available
     */
    public double getPythonAvgCpuPercent() {
        return pythonAvgCpuPercent;
    }

    // ========================================================================
    //  Processing Mode Methods
    // ========================================================================

    /**
     * Sets the processing mode.
     *
     * @param mode the processing mode (e.g., "ADAPTIVE", "BASELINE")
     */
    public void setProcessingMode(String mode) {
        this.processingMode = mode;
    }

    /**
     * Returns the processing mode.
     *
     * @return the processing mode
     */
    public String getProcessingMode() {
        return processingMode;
    }

    // ========================================================================
    //  Failure Methods
    // ========================================================================

    /**
     * Records failure information from a throwable.
     *
     * @param t the throwable that caused the failure
     */
    public void setFailure(Throwable t) {
        if (t == null) return;
        this.failureExceptionType = t.getClass().getName();
        this.failureExceptionMessage = t.getMessage();
    }

    /**
     * Returns the failure exception type.
     *
     * @return the exception class name, or {@code null} if no failure
     */
    public String getFailureExceptionType() {
        return failureExceptionType;
    }

    /**
     * Returns the failure exception message.
     *
     * @return the exception message, or {@code null} if no failure
     */
    public String getFailureExceptionMessage() {
        return failureExceptionMessage;
    }

    /**
     * Returns whether this file failed.
     *
     * @return {@code true} if the file failed processing
     */
    public boolean isFailed() {
        return failureExceptionType != null;
    }

    // ========================================================================
    //  GPU Methods
    // ========================================================================

    /**
     * Sets whether GPU acceleration was used.
     *
     * @param used {@code true} if GPU was used
     */
    public void setGpuUsed(boolean used) {
        this.gpuUsed = used;
    }

    /**
     * Returns whether GPU acceleration was used.
     *
     * @return {@code true} if GPU was used
     */
    public boolean isGpuUsed() {
        return gpuUsed;
    }

    /**
     * Sets the GPU name.
     *
     * @param name the GPU name (e.g., "NVIDIA GeForce RTX 3080")
     */
    public void setGpuName(String name) {
        this.gpuName = name != null ? name : "None";
    }

    /**
     * Returns the GPU name.
     *
     * @return the GPU name
     */
    public String getGpuName() {
        return gpuName;
    }

    /**
     * Sets the GPU compute capability.
     *
     * @param capability the compute capability (e.g., "8.6")
     */
    public void setGpuComputeCapability(String capability) {
        this.gpuComputeCapability = capability != null ? capability : "N/A";
    }

    /**
     * Returns the GPU compute capability.
     *
     * @return the compute capability
     */
    public String getGpuComputeCapability() {
        return gpuComputeCapability;
    }

    /**
     * Sets the GPU memory size.
     *
     * @param memoryMB the GPU memory in megabytes
     */
    public void setGpuMemoryMB(long memoryMB) {
        this.gpuMemoryMB = memoryMB;
    }

    /**
     * Returns the GPU memory size.
     *
     * @return the GPU memory in megabytes
     */
    public long getGpuMemoryMB() {
        return gpuMemoryMB;
    }

    /**
     * Returns a formatted GPU status string.
     *
     * @return a string like "GPU: NVIDIA GeForce RTX 3080 (10240 MB)"
     */
    public String getGpuStatus() {
        if (!gpuUsed) {
            return "CPU";
        }
        return String.format("GPU: %s (%d MB)", gpuName, gpuMemoryMB);
    }

    /**
     * Returns a detailed GPU info string.
     *
     * @return a detailed string with GPU name, memory, and compute capability
     */
    public String getGpuDetails() {
        if (!gpuUsed) {
            return "No GPU acceleration";
        }
        return String.format("%s | Memory: %d MB | Compute: %s",
            gpuName, gpuMemoryMB, gpuComputeCapability);
    }

    /**
     * Convenience method to set all GPU fields at once.
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
     *
     * @param gpuConfig the GpuConfig instance to read GPU info from
     * @param used whether GPU was used for this file
     * @return a copy of this report with GPU info populated
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

    // ========================================================================
    //  Getters
    // ========================================================================

    /**
     * Returns the file name.
     *
     * @return the file name
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Returns the report timestamp.
     *
     * @return the timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("FileTimingReport{fileName='%s', totalMillis=%d, gpuUsed=%s, gpuName='%s'}",
                fileName, getTotalMillis(), gpuUsed, gpuName);
    }
}