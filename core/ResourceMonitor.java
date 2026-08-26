/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import org.slf4j.Logger;

import java.util.DoubleSummaryStatistics;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * System resource probing and per-batch resource observability.
 *
 * <p>This class provides three related but separable capabilities:
 * <ol>
 *   <li><b>Point-in-time probes:</b> Methods to query current CPU load,
 *       system memory usage, and JVM heap usage</li>
 *   <li><b>Batch-level sampling:</b> A 2-second background sampler that
 *       accumulates CPU and memory statistics for reporting</li>
 *   <li><b>Scaling-event tracking:</b> A counter for adaptive concurrency
 *       changes, surfaced in batch summaries</li>
 * </ol>
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * ResourceMonitor monitor = new ResourceMonitor(logger);
 * monitor.resetForNewBatch();
 * monitor.startSampling(activeFileCountSupplier, uiLogger);
 * // ... batch processing ...
 * monitor.stopSampling();
 * String summary = monitor.buildBatchSummary(filesProcessed, audioDuration, elapsedMs);
 * }</pre>
 *
 * <p><b>Thread-safety:</b> One instance is meant to live for the lifetime
 * of a {@link ParallelProcessingManager}. Call {@link #resetForNewBatch()}
 * at the start of each batch to reset statistics.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see ParallelProcessingManager
 */
public class ResourceMonitor {

    private final Logger logger;

    private ScheduledExecutorService samplerExecutor;
    private DoubleSummaryStatistics cpuStats = new DoubleSummaryStatistics();
    private DoubleSummaryStatistics memStats = new DoubleSummaryStatistics();
    private volatile long peakHeapMB = 0;
    private final AtomicInteger scalingEventCount = new AtomicInteger(0);

    /**
     * Constructs a new ResourceMonitor with the specified logger.
     *
     * @param logger the logger for resource monitoring output
     */
    public ResourceMonitor(Logger logger) {
        this.logger = logger;
    }

    // -------------------------------------------------------------------------
    //  Point-in-time probes
    // -------------------------------------------------------------------------

    /**
     * Returns the system-wide CPU load as a percentage.
     *
     * @return the CPU load as a percentage, or {@code -1} if unavailable
     */
    public double getSystemCpuLoadPercent() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getCpuLoad();
                return load >= 0 ? load * 100.0 : -1;
            }
        } catch (Exception e) {
            logger.debug("CPU load measurement unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Returns the percentage of total physical (system-wide) RAM currently in use.
     *
     * <p>This is distinct from JVM heap usage. The memory-heavy work
     * (WhisperX/torch model inference) runs in external Python subprocesses,
     * whose memory usage is invisible to the JVM heap.</p>
     *
     * @return the system memory used as a percentage, or {@code -1} if unavailable
     */
    public double getSystemMemoryUsedPercent() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                long total = sunBean.getTotalMemorySize();
                long free = sunBean.getFreeMemorySize();
                if (total > 0) {
                    return (total - free) * 100.0 / total;
                }
            }
        } catch (Exception e) {
            logger.debug("System memory measurement unavailable: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Returns the currently-used JVM heap memory in megabytes.
     *
     * @return the JVM heap used in MB
     */
    public long getUsedMemoryMB() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    // -------------------------------------------------------------------------
    //  Batch-level sampling (2s, observation-only)
    // -------------------------------------------------------------------------

    /**
     * Clears accumulated stats for the start of a new batch.
     */
    public void resetForNewBatch() {
        cpuStats = new DoubleSummaryStatistics();
        memStats = new DoubleSummaryStatistics();
        peakHeapMB = 0;
        scalingEventCount.set(0);
    }

    /**
     * Starts a 2-second background sampler that logs resource snapshots.
     *
     * <p>This method accumulates CPU and memory statistics for
     * {@link #buildBatchSummary}. It should be called once per batch.</p>
     *
     * @param activeFileCountSupplier supplies the number of actively processed files
     * @param uiLogger a consumer for UI log messages (may be {@code null})
     */
    public void startSampling(IntSupplier activeFileCountSupplier, Consumer<String> uiLogger) {
        samplerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Batch-Resource-Sampler");
            t.setDaemon(true);
            return t;
        });
        samplerExecutor.scheduleAtFixedRate(() -> {
            try {
                double cpuPct = getSystemCpuLoadPercent();
                double memPct = getSystemMemoryUsedPercent();
                long heapMB = getUsedMemoryMB();
                if (cpuPct >= 0) cpuStats.accept(cpuPct);
                if (memPct >= 0) memStats.accept(memPct);
                peakHeapMB = Math.max(peakHeapMB, heapMB);

                String line = String.format("RESOURCE_SAMPLE: cpu=%s%% mem=%s%% heap=%dMB activeFiles=%d",
                        cpuPct >= 0 ? String.format("%.0f", cpuPct) : "n/a",
                        memPct >= 0 ? String.format("%.0f", memPct) : "n/a",
                        heapMB, activeFileCountSupplier.getAsInt());
                logger.info(line);
            } catch (Exception e) {
                logger.debug("Resource sample failed: {}", e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    /**
     * Stops the background sampler and releases resources.
     *
     * <p>This should be called when a batch completes (success, failure,
     * or cancellation) in a {@code finally} block.</p>
     */
    public void stopSampling() {
        if (samplerExecutor != null) {
            samplerExecutor.shutdownNow();
            samplerExecutor = null;
        }
    }

    // -------------------------------------------------------------------------
    //  Scaling-event tracking
    // -------------------------------------------------------------------------

    /**
     * Records a scaling event when the adaptive concurrency target changes.
     *
     * <p>This method logs the transition and increments the counter shown
     * in {@link #buildBatchSummary}. If {@code previousTarget} is {@code -1},
     * the call is a no-op.</p>
     *
     * @param previousTarget the previous concurrency target, or {@code -1} for the first cycle
     * @param newTarget the new concurrency target
     * @param cpuLoadPct the CPU load at the time of scaling
     * @param memUsedPct the memory used at the time of scaling
     */
    public void recordScalingEvent(int previousTarget, int newTarget, double cpuLoadPct, double memUsedPct) {
        if (previousTarget == -1 || previousTarget == newTarget) return;
        scalingEventCount.incrementAndGet();
        logger.info("Adaptive scaling: model concurrency target changed {} -> {} (cpu={}%, mem={}%)",
                previousTarget, newTarget, cpuLoadPct, memUsedPct);
    }

    // -------------------------------------------------------------------------
    //  Batch summary
    // -------------------------------------------------------------------------

    /**
     * Builds an end-of-batch summary block.
     *
     * @param filesProcessed the number of files processed
     * @param totalAudioDurationSeconds the total audio duration in seconds
     * @param elapsedMs the elapsed processing time in milliseconds
     * @return a formatted summary string
     */
    public String buildBatchSummary(int filesProcessed, double totalAudioDurationSeconds, long elapsedMs) {
        double elapsedHours = elapsedMs / 3_600_000.0;
        double audioMinutes = totalAudioDurationSeconds / 60.0;
        double throughputMinPerHour = elapsedHours > 0 ? audioMinutes / elapsedHours : 0;
        double avgCpu = cpuStats.getCount() > 0 ? cpuStats.getAverage() : -1;
        long cpuActiveMs = avgCpu >= 0 ? Math.round(elapsedMs * (avgCpu / 100.0)) : -1;

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Batch Summary ==========\n");
        sb.append(String.format("Files processed:      %d%n", filesProcessed));
        sb.append(String.format("Audio duration:       %s%n", formatDuration((long) (totalAudioDurationSeconds * 1000))));
        sb.append(String.format("Elapsed time:          %s%n", formatDuration(elapsedMs)));
        sb.append(String.format("Average throughput:    %.1f min audio / hour%n", throughputMinPerHour));
        sb.append(String.format("Average CPU:           %s%n", avgCpu >= 0 ? String.format("%.1f%%", avgCpu) : "n/a"));
        sb.append(String.format("Peak CPU:              %s%n",
                cpuStats.getCount() > 0 ? String.format("%.1f%%", cpuStats.getMax()) : "n/a"));
        sb.append(String.format("Average RAM:           %s%n",
                memStats.getCount() > 0 ? String.format("%.1f%%", memStats.getAverage()) : "n/a"));
        sb.append(String.format("Peak RAM:              %s%n",
                memStats.getCount() > 0 ? String.format("%.1f%%", memStats.getMax()) : "n/a"));
        sb.append(String.format("Peak Java heap:        %d MB%n", peakHeapMB));
        if (cpuActiveMs >= 0) {
            sb.append(String.format("CPU active / idle:     %s / %s%n",
                    formatDuration(cpuActiveMs), formatDuration(Math.max(0, elapsedMs - cpuActiveMs))));
        }
        sb.append(String.format("Scaling events:        %d (model-pool concurrency target changed this batch)%n",
                scalingEventCount.get()));
        sb.append("====================================");
        return sb.toString();
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     *
     * @param ms the duration in milliseconds
     * @return a formatted string (e.g., "1h 2m 30s")
     */
    public static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }
}