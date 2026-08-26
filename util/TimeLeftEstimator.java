/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced time estimation with real-time progress tracking and batch statistics
 * Integrates with BatchProcessor for accurate progress reporting
 */
public class TimeLeftEstimator {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimeLeftEstimator.class);
    
    // Historical performance data
    private final Map<String, ProcessTimingData> processTimingData;
    private final Map<String, ModelTimingProfile> modelProfiles;
    
    // Current batch tracking
    private final AtomicLong batchStartTime;
    private final List<FileProcessingRecord> currentBatchFiles; // queued files (with actual names)
    private final Map<String, FileProcessingRecord> activeFiles = new ConcurrentHashMap<>();
    private BatchStatistics currentBatchStats;
    
    // Real-time adjustment factors
    private double systemPerformanceFactor;
    private double currentSpeedMultiplier;
    
    // Configuration
    private static final int MAX_HISTORY_SIZE = 1000;
    private static final double INITIAL_SPEED_FACTOR = 1.0;

    // Persistence
    private static final String ESTIMATOR_DATA_FILE = "time_estimates.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path dataFilePath;
    
    public TimeLeftEstimator() {
        this.processTimingData = new ConcurrentHashMap<>();
        this.modelProfiles = new ConcurrentHashMap<>();
        this.batchStartTime = new AtomicLong(0);
        this.currentBatchFiles = new CopyOnWriteArrayList<>();
        this.systemPerformanceFactor = 1.0;
        this.currentSpeedMultiplier = INITIAL_SPEED_FACTOR;
        this.currentBatchStats = new BatchStatistics();
        initializeDefaultProfiles();
        double cpuFactor = detectCpuPerformanceFactor();
        currentSpeedMultiplier = INITIAL_SPEED_FACTOR * cpuFactor;
        LOGGER.info("CPU performance factor: {}, initial speed multiplier: {}", cpuFactor, currentSpeedMultiplier);

        Path resolvedPath = null;
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".audiomanager");
            Files.createDirectories(dir);
            resolvedPath = dir.resolve(ESTIMATOR_DATA_FILE);
        } catch (IOException e) {
            LOGGER.error("Failed to create/resolve time estimator data directory — estimates will not persist across restarts", e);
        }
        this.dataFilePath = resolvedPath;
        loadPersistedData();
    }

    /**
     * Constructor with parameters for compatibility
     */
    public TimeLeftEstimator(int maxSamples, PreferenceManager prefs) {
        this();
        LOGGER.debug("EnhancedTimeEstimator initialized with {} max samples", maxSamples);
    }

    /**
     * Initialize batch processing with file queue
     */
    public void startBatch() {
        this.batchStartTime.set(System.currentTimeMillis());
        this.currentBatchStats = new BatchStatistics();
        this.currentBatchFiles.clear();
        this.activeFiles.clear();
        LOGGER.info("Time estimation batch started");
    }
    
    /**
     * Add file to queue for time estimation – now accepts file name
     */
    public void addQueuedFile(String fileName, double fileSizeMB, String model, List<String> processes) {
        FileProcessingRecord fileRecord = new FileProcessingRecord(
            fileName,
            fileSizeMB,
            model,
            new ArrayList<>(processes)
        );
        fileRecord.setQueueTime(System.currentTimeMillis());
        currentBatchFiles.add(fileRecord);
        currentBatchStats.totalFiles++;
        
        LOGGER.debug("Added file to estimation queue: {} ({}MB, model: {})", fileName, fileSizeMB, model);
    }
    
    /**
     * Start tracking time for a specific file – removes its queued record
     */
    public void startFileProcessing(String fileName, double fileSizeMB, String model, List<String> processes) {
        // Find and remove the queued record for this file
        FileProcessingRecord queued = null;
        for (FileProcessingRecord rec : currentBatchFiles) {
            if (rec.getFileName().equals(fileName)) {
                queued = rec;
                break;
            }
        }
        if (queued != null) {
            currentBatchFiles.remove(queued);
        } else {
            LOGGER.warn("No queued record found for file: {} – creating new one", fileName);
        }

        FileProcessingRecord record = new FileProcessingRecord(fileName, fileSizeMB, model, processes);
        record.setStartTime(System.currentTimeMillis());

        // Compute a fresh estimate
        calculateFileTimeEstimates(record);

        activeFiles.put(fileName, record);

        LOGGER.debug("Started time tracking for: {} (estimated: {}ms, {} file(s) now active)",
                    fileName, record.getEstimatedTotalTime(), activeFiles.size());
    }
    
    /**
     * Record completion of a processing stage for a specific file.
     */
    public void recordProcessCompletion(String fileName, String processName, long actualDurationMs) {
        FileProcessingRecord record = activeFiles.get(fileName);
        if (record == null) {
            LOGGER.warn("No active record for file '{}' — process completion ignored: {}", fileName, processName);
            return;
        }

        updateProcessTimingData(processName, actualDurationMs, record.getFileSizeMB());

        record.recordProcessCompletion(processName, actualDurationMs);

        calculateRemainingTimeEstimates(record);

        LOGGER.trace("Process completed: {} in {}ms for {}",
                    processName, actualDurationMs, fileName);
    }
    
    /**
     * Ensure file tracking is cleared for a file that may not have been promoted.
     */
    public void ensureFileTrackingCleared(String fileName) {
        currentBatchFiles.removeIf(rec -> rec.getFileName().equals(fileName));
        activeFiles.remove(fileName);
    }

    /**
     * Complete processing for a specific file and update historical data.
     */
    public void completeFileProcessing(String fileName) {
        FileProcessingRecord record = activeFiles.remove(fileName);
        if (record == null) {
            LOGGER.warn("No active record for file '{}' to complete", fileName);
            return;
        }
        
        record.setEndTime(System.currentTimeMillis());
        long actualTotalTime = record.getActualTotalTime();
        
        // ===== FIX: Set remaining time to 0 immediately =====
        record.setEstimatedTimeLeft(0);
        
        currentBatchStats.completedFiles++;
        currentBatchStats.totalProcessingTime += actualTotalTime;
        
        learnFromFileProcessing(record);
        
        LOGGER.debug("File processing completed: {} in {}ms (estimated: {}ms), {} file(s) still active",
                    fileName, actualTotalTime, record.getEstimatedTotalTime(), activeFiles.size());

        // Persist immediately
        persistData();
    }
    
    /**
     * Chooses which actively-processing file "File Time Left" / "File Time
     * Spent" / current-file progress refer to.
     * 
     * FIX: Returns the file with the MOST remaining time (the one that
     * will finish last), not the least. The "File Time Left" should show the
     * time remaining for the CURRENTLY DISPLAYED file, and "Total Time Left"
     * should be the sum of all remaining files.
     */
    private FileProcessingRecord getDisplayFile() {
        FileProcessingRecord longest = null;
        long longestLeftMs = Long.MIN_VALUE;
        for (FileProcessingRecord record : activeFiles.values()) {
            long left = Math.max(0, record.getEstimatedTotalTime() - record.getTimeSpent());
            if (longest == null || left > longestLeftMs) {
                longest = record;
                longestLeftMs = left;
            }
        }
        return longest;
    }

    /**
     * Get enhanced time estimate for the displayed file with progress context
     */
    public EnhancedTimeEstimate getCurrentFileTimeEstimate() {
        FileProcessingRecord display = getDisplayFile();
        if (display == null) {
            return new EnhancedTimeEstimate(0, 0, 0, "No file currently processing");
        }
        
        long timeSpent = display.getTimeSpent();
        long timeLeft = display.getEstimatedTimeLeft();
        long totalEstimate = display.getEstimatedTotalTime();
        
        String context = String.format("File: %s, Progress: %.1f%%, Speed: %.2fx", 
            display.getFileName(),
            display.getProgress() * 100,
            currentSpeedMultiplier
        );
        
        return new EnhancedTimeEstimate(timeSpent, timeLeft, totalEstimate, context);
    }
    
    /**
     * Get batch-level time estimates
     * 
     * FIX: Total Time Left should be the sum of all remaining file processing times,
     * not just the current file's remaining time.
     */
    public BatchTimeEstimate getBatchTimeEstimate() {
        if (currentBatchFiles.isEmpty() && activeFiles.isEmpty()) {
            return new BatchTimeEstimate(0, 0, 0, "No active batch");
        }
        
        long elapsedTime = System.currentTimeMillis() - batchStartTime.get();

        // Calculate total remaining time for ALL active files
        long totalRemainingTime = 0;
        long fileTimeLeft = 0;
        
        FileProcessingRecord display = getDisplayFile();
        
        for (FileProcessingRecord active : activeFiles.values()) {
            long remaining = Math.max(0, active.getEstimatedTotalTime() - active.getTimeSpent());
            totalRemainingTime += remaining;
            if (active == display) {
                fileTimeLeft = remaining;
            }
        }
        
        // Add estimates for queued files
        Double liveMsPerMB = (display != null && display.getFileSizeMB() > 0.01)
                ? display.getEstimatedTotalTime() / display.getFileSizeMB()
                : null;
        
        for (FileProcessingRecord queuedFile : currentBatchFiles) {
            long queuedEstimate;
            if (liveMsPerMB != null) {
                queuedEstimate = (long) (liveMsPerMB * queuedFile.getFileSizeMB());
            } else {
                calculateFileTimeEstimates(queuedFile);
                queuedEstimate = queuedFile.getEstimatedTotalTime();
            }
            totalRemainingTime += queuedEstimate;
        }
        
        long estimatedTotalTime = elapsedTime + totalRemainingTime;
        int remainingFiles = currentBatchFiles.size() + activeFiles.size();
        double progress = currentBatchStats.getProgress();
        
        String context = String.format("Files: %d/%d (%.1f%%), Remaining: %d", 
            currentBatchStats.completedFiles,
            currentBatchStats.totalFiles,
            progress * 100,
            remainingFiles
        );
        
        return new BatchTimeEstimate(elapsedTime, totalRemainingTime, estimatedTotalTime, context);
    }
    
    /**
     * Get displayed-file progress for UI updates (0.0 to 1.0)
     */
    public double getCurrentFileProgress() {
        FileProcessingRecord display = getDisplayFile();
        return display != null ? display.getProgress() : 0.0;
    }
    
    /**
     * Get batch progress for UI updates (0.0 to 1.0)
     */
    public double getBatchProgress() {
        return currentBatchStats.getProgress();
    }
    
    /**
     * Get displayed-file time spent (for progress calculations)
     */
    public long getCurrentFileTimeSpent() {
        FileProcessingRecord display = getDisplayFile();
        return display != null ? display.getTimeSpent() : 0;
    }
    
    /**
     * Get displayed-file time left estimate (for progress calculations)
     */
    public long estimateCurrentFileTimeLeft() {
        FileProcessingRecord display = getDisplayFile();
        if (display == null) return 0;
        double progress = display.getActualProgress();
        if (progress > 0.01) {
            long elapsed = display.getTimeSpent();
            long remaining = (long)(elapsed / progress - elapsed);
            return Math.max(0, remaining);
        } else {
            return display.getEstimatedTimeLeft();
        }
    }
    
    /**
     * Get total time spent in current batch
     */
    public long getTotalTimeSpent() {
        return System.currentTimeMillis() - batchStartTime.get();
    }
    
    /**
     * Update system performance factor based on real-time conditions
     */
    public void updateSystemPerformanceFactor(double factor) {
        this.systemPerformanceFactor = Math.max(0.1, Math.min(5.0, factor));
        this.currentSpeedMultiplier = INITIAL_SPEED_FACTOR * systemPerformanceFactor;
        LOGGER.debug("System performance factor updated: {}", systemPerformanceFactor);
    }
    
    /**
     * Get current batch statistics for UI display
     */
    public BatchStatistics getCurrentBatchStatistics() {
        return new BatchStatistics(currentBatchStats);
    }
    
    // Private implementation methods
    
    private void initializeDefaultProfiles() {
        String[] processes = {"audio_enhancement", "audio_preprocessing", "transcription_base",
                             "transcription_small", "transcription_medium", "transcription_large",
                             "saving_transcription", "file_cleanup", "transcription_segment"};
        
        processTimingData.put("transcription_segment", new ProcessTimingData("transcription_segment"));
        for (String process : processes) {
            processTimingData.put(process, new ProcessTimingData(process));
        }
        
        modelProfiles.put("tiny", new ModelTimingProfile("tiny", 0.3, 0.5));
        modelProfiles.put("base", new ModelTimingProfile("base", 0.5, 1.0));
        modelProfiles.put("small", new ModelTimingProfile("small", 0.8, 1.5));
        modelProfiles.put("medium", new ModelTimingProfile("medium", 1.2, 2.5));
        modelProfiles.put("large", new ModelTimingProfile("large", 2.0, 4.0));
        
        LOGGER.debug("Initialized default timing profiles");
    }
    
    private void calculateFileTimeEstimates(FileProcessingRecord file) {
        long totalEstimate = 0;
        
        for (String process : file.getProcesses()) {
            long processEstimate = estimateProcessTime(process, file.getFileSizeMB(), file.getModel());
            file.setProcessEstimate(process, processEstimate);
            totalEstimate += processEstimate;
        }
        
        totalEstimate = (long)(totalEstimate / currentSpeedMultiplier);
        file.setEstimatedTotalTime(totalEstimate);
    }
    
    private void calculateRemainingTimeEstimates(FileProcessingRecord file) {
        long remainingTime = 0;
        
        for (String process : file.getProcesses()) {
            if (!file.isProcessCompleted(process)) {
                remainingTime += file.getProcessEstimate(process);
            }
        }
        
        remainingTime = (long)(remainingTime / currentSpeedMultiplier);
        file.setEstimatedTimeLeft(remainingTime);

        // Refresh estimatedTotalTime using actual elapsed time so far
        // plus the freshly-recalculated remaining estimate
        file.setEstimatedTotalTime(file.getTimeSpent() + remainingTime);
    }
    
    private long estimateProcessTime(String processName, double fileSizeMB, String model) {
        ProcessTimingData timingData = processTimingData.get(processName);
        if (timingData == null) {
            timingData = new ProcessTimingData(processName);
            processTimingData.put(processName, timingData);
        }
        
        long baseEstimate = timingData.getEstimatedTime(fileSizeMB);
        
        if (processName.startsWith("transcription_")) {
            ModelTimingProfile modelProfile = modelProfiles.get(model);
            if (modelProfile != null) {
                baseEstimate = (long)(baseEstimate * modelProfile.getTimeFactor());
            }
        }
        
        return baseEstimate;
    }
    
    private void updateProcessTimingData(String processName, long actualDuration, double fileSizeMB) {
        ProcessTimingData timingData = processTimingData.get(processName);
        if (timingData != null) {
            timingData.recordTiming(fileSizeMB, actualDuration);
        }
    }

    /**
     * Feed the persistent, per-process learned-timing model directly,
     * without requiring an active (in-progress) file record.
     */
    public void recordGlobalProcessTiming(String processName, long durationMs, double fileSizeMB) {
        updateProcessTimingData(processName, durationMs, fileSizeMB);
    }
    
    private void learnFromFileProcessing(FileProcessingRecord file) {
        long estimated = file.getEstimatedTotalTime();
        long actual = file.getActualTotalTime();
        
        if (estimated > 0 && actual > 0) {
            double performanceRatio = (double) estimated / actual;
            double learningRate = 0.1;
            
            currentSpeedMultiplier = currentSpeedMultiplier * (1 - learningRate) + 
                                   performanceRatio * learningRate;
            
            LOGGER.debug("Learning from file {}: estimated={}ms, actual={}ms, new speed factor={}", 
                        file.getFileName(), estimated, actual, currentSpeedMultiplier);
        }
    }
    
    private long calculateBatchTotalTime() {
        long totalTime = 0;
        
        totalTime += currentBatchStats.totalProcessingTime;
        
        for (FileProcessingRecord active : activeFiles.values()) {
            totalTime += active.getTimeSpent() + active.getEstimatedTimeLeft();
        }
        
        for (FileProcessingRecord queuedFile : currentBatchFiles) {
            calculateFileTimeEstimates(queuedFile);
            totalTime += queuedFile.getEstimatedTotalTime();
        }
        
        return totalTime;
    }
    
    // Data classes
    
    private static class FileProcessingRecord {
        private final String fileName;
        private final double fileSizeMB;
        private final String model;
        private final List<String> processes;
        private final Map<String, Long> processEstimates;
        private final Map<String, Long> processActualTimes;
        private final Set<String> completedProcesses;
        
        private long queueTime;
        private long startTime;
        private long endTime;
        private long estimatedTotalTime;
        private long estimatedTimeLeft;
        private double actualProgress = 0.0;
        private int totalSegments;
        private int completedSegments;
        private long avgSegmentTime;   // rolling average in milliseconds

        public FileProcessingRecord(String fileName, double fileSizeMB, String model, List<String> processes) {
            this.fileName = fileName;
            this.fileSizeMB = fileSizeMB;
            this.model = model;
            this.processes = new ArrayList<>(processes);
            this.processEstimates = new HashMap<>();
            this.processActualTimes = new HashMap<>();
            this.completedProcesses = new HashSet<>();
        }
        
        public String getFileName() { return fileName; }
        public double getFileSizeMB() { return fileSizeMB; }
        public String getModel() { return model; }
        public List<String> getProcesses() { return new ArrayList<>(processes); }
        public double getActualProgress() {
            return actualProgress;
        }
        public void setTotalSegments(int total) { this.totalSegments = total; }
        public int getTotalSegments() { return totalSegments; }
        public int getCompletedSegments() { return completedSegments; }
        public long getAvgSegmentTime() { return avgSegmentTime; }
        public void setAvgSegmentTime(long avg) { this.avgSegmentTime = avg; }
        
        public void setActualProgress(double progress) {
            this.actualProgress = Math.min(1.0, Math.max(0.0, progress));
        }
        public void setQueueTime(long time) { this.queueTime = time; }
        public void setStartTime(long time) { this.startTime = time; }
        public void setEndTime(long time) { this.endTime = time; }
        public void setEstimatedTotalTime(long time) { this.estimatedTotalTime = time; }
        public void setEstimatedTimeLeft(long time) { this.estimatedTimeLeft = time; }
        public void setProcessEstimate(String process, long estimate) { 
            processEstimates.put(process, estimate); 
        }
        
        public long getProcessEstimate(String process) { 
            return processEstimates.getOrDefault(process, 0L); 
        }

        public void recordSegmentCompletion(long durationMs) {
            completedSegments++;
            if (avgSegmentTime == 0) {
                avgSegmentTime = durationMs;
            } else {
                // Simple rolling average
                avgSegmentTime = (avgSegmentTime * (completedSegments - 1) + durationMs) / completedSegments;
            }
        }
        
        public void recordProcessCompletion(String process, long actualTime) {
            processActualTimes.put(process, actualTime);
            completedProcesses.add(process);
        }
        
        public boolean isProcessCompleted(String process) {
            return completedProcesses.contains(process);
        }
        
        public long getTimeSpent() {
            if (startTime == 0) return 0;
            long currentTime = (endTime > 0) ? endTime : System.currentTimeMillis();
            return currentTime - startTime;
        }
        
        public long getEstimatedTotalTime() { return estimatedTotalTime; }
        public long getEstimatedTimeLeft() { return estimatedTimeLeft; }
        public long getActualTotalTime() { return endTime > startTime ? endTime - startTime : 0; }
        
        public double getProgress() {
            if (totalSegments > 0) {
                return (double) completedSegments / totalSegments;
            }
            if (estimatedTotalTime > 0) {
                long spent = getTimeSpent();
                return Math.min(1.0, (double) spent / estimatedTotalTime);
            }
            return 0.0;
        }
    }
    
    /**
     * Start tracking a file that will be processed in segments.
     */
    public void startSegmentedFileProcessing(String fileName, double fileSizeMB, String model,
                                            List<String> processes, int totalSegments) {
        // Remove any queued record
        FileProcessingRecord queued = null;
        for (FileProcessingRecord rec : currentBatchFiles) {
            if (rec.getFileName().equals(fileName)) {
                queued = rec;
                break;
            }
        }
        if (queued != null) {
            currentBatchFiles.remove(queued);
        }

        FileProcessingRecord record = new FileProcessingRecord(fileName, fileSizeMB, model, processes);
        record.setTotalSegments(totalSegments);
        record.setStartTime(System.currentTimeMillis());

        // Estimate segment time using "transcription_segment" process
        double segmentSizeMB = fileSizeMB / totalSegments;
        long baseSegmentTime = estimateProcessTime("transcription_segment", segmentSizeMB, model);
        record.setAvgSegmentTime(baseSegmentTime);
        
        // ===== FIX: Calculate TOTAL file time (all segments) =====
        long estimatedTotal = totalSegments * baseSegmentTime;
        record.setEstimatedTotalTime(estimatedTotal);
        record.setEstimatedTimeLeft(estimatedTotal);

        activeFiles.put(fileName, record);

        LOGGER.debug("Started segmented file tracking: {} ({} segments, estimate {}ms, {} file(s) now active)",
                     fileName, totalSegments, estimatedTotal, activeFiles.size());
    }

    /**
     * Record completion of a segment for a specific file.
     */
    public void recordSegmentCompletion(String fileName, long durationMs) {
        FileProcessingRecord record = activeFiles.get(fileName);
        if (record == null) {
            LOGGER.warn("No active record for file '{}' — segment completion ignored", fileName);
            return;
        }
        record.recordSegmentCompletion(durationMs);

        // Feed this segment's actual duration into the persistent timing model
        double segmentSizeMB = record.getTotalSegments() > 0
                ? record.getFileSizeMB() / record.getTotalSegments()
                : record.getFileSizeMB();
        updateProcessTimingData("transcription_segment", durationMs, segmentSizeMB);

        // Update remaining time for the ENTIRE file
        int remainingSegments = record.getTotalSegments() - record.getCompletedSegments();
        long estimatedRemaining = remainingSegments * record.getAvgSegmentTime();
        record.setEstimatedTimeLeft(estimatedRemaining);

        // Refresh estimatedTotalTime anchored to actual elapsed time so far
        // plus the freshly-recalculated remaining estimate
        record.setEstimatedTotalTime(record.getTimeSpent() + estimatedRemaining);

        LOGGER.debug("Segment completed for {} in {}ms, avg now {}ms, remaining {} segments => {}ms left for entire file",
                     fileName, durationMs, record.getAvgSegmentTime(), remainingSegments, estimatedRemaining);
    }

    /**
     * Live, continuously-decaying estimate of time left for the displayed file.
     * Returns the total remaining time for the ENTIRE file, not just the current segment.
     */
    public long getLiveCurrentFileTimeLeftMs() {
        FileProcessingRecord display = getDisplayFile();
        if (display == null) return 0;
        long total = display.getEstimatedTotalTime();
        long spent = display.getTimeSpent();
        return Math.max(0, total - spent);
    }

    /**
     * Live, continuously-decaying estimate of time left for the WHOLE batch.
     * Returns the sum of all remaining file processing times.
     */
    public long getLiveTotalTimeLeftMs() {
        if (currentBatchFiles.isEmpty() && activeFiles.isEmpty()) {
            return 0;
        }
        
        long totalRemaining = 0;
        
        // Sum remaining time for all active files
        for (FileProcessingRecord active : activeFiles.values()) {
            long remaining = Math.max(0, active.getEstimatedTotalTime() - active.getTimeSpent());
            totalRemaining += remaining;
        }
        
        // Sum estimates for queued files
        FileProcessingRecord display = getDisplayFile();
        Double liveMsPerMB = (display != null && display.getFileSizeMB() > 0.01)
                ? display.getEstimatedTotalTime() / display.getFileSizeMB()
                : null;
        
        for (FileProcessingRecord queuedFile : currentBatchFiles) {
            long queuedEstimate;
            if (liveMsPerMB != null) {
                queuedEstimate = (long) (liveMsPerMB * queuedFile.getFileSizeMB());
            } else {
                calculateFileTimeEstimates(queuedFile);
                queuedEstimate = queuedFile.getEstimatedTotalTime();
            }
            totalRemaining += queuedEstimate;
        }
        
        return totalRemaining;
    }
    
    private static class ProcessTimingData {
        private final String processName;
        private final List<TimingSample> historicalSamples;
        private double baseTimeMsPerMB;
        private double fixedTimeMs;
        
        public ProcessTimingData(String processName) {
            this.processName = processName;
            this.historicalSamples = new ArrayList<>();
            initializeDefaultTiming();
        }
        
        private void initializeDefaultTiming() {
            // Default timing estimates based on process type
            switch (processName) {
                case "audio_enhancement":
                    baseTimeMsPerMB = 1000; // 1 second per MB
                    fixedTimeMs = 5000;
                    break;
                case "audio_preprocessing":
                    baseTimeMsPerMB = 500; // 0.5 seconds per MB
                    fixedTimeMs = 3000;
                    break;
                case "transcription_base":
                    baseTimeMsPerMB = 2000; // 2 seconds per MB
                    fixedTimeMs = 10000;
                    break;
                case "saving_transcription":
                    baseTimeMsPerMB = 100; // 0.1 seconds per MB
                    fixedTimeMs = 1000;
                    break;
                case "file_cleanup":
                    baseTimeMsPerMB = 50; // 0.05 seconds per MB
                    fixedTimeMs = 500;
                    break;
                case "transcription_segment":
                    baseTimeMsPerMB = 2000;   // 2 seconds per MB (segment size)
                    fixedTimeMs = 5000;       // 5 seconds overhead per segment
                    break;
                default:
                    baseTimeMsPerMB = 1000;
                    fixedTimeMs = 5000;
            }
        }
        
        public long getEstimatedTime(double fileSizeMB) {
            return (long)(baseTimeMsPerMB * fileSizeMB + fixedTimeMs);
        }
        
        public void recordTiming(double fileSizeMB, long actualTimeMs) {
            historicalSamples.add(new TimingSample(fileSizeMB, actualTimeMs));
            
            // Keep only recent samples
            if (historicalSamples.size() > MAX_HISTORY_SIZE) {
                historicalSamples.remove(0);
            }
            
            // Learn from new data (simplified linear regression)
            if (historicalSamples.size() >= 5) {
                learnFromSamples();
            }
        }
        
        private void learnFromSamples() {
            // Simplified learning: calculate average time per MB
            double totalSize = 0;
            double totalTime = 0;
            int count = 0;
            
            for (TimingSample sample : historicalSamples) {
                if (sample.fileSizeMB > 0.1) { // Ignore very small files
                    totalSize += sample.fileSizeMB;
                    totalTime += sample.actualTimeMs;
                    count++;
                }
            }
            
            if (count > 0 && totalSize > 0) {
                double newBaseTime = (totalTime - fixedTimeMs * count) / totalSize;
                // Smooth update
                baseTimeMsPerMB = baseTimeMsPerMB * 0.7 + newBaseTime * 0.3;
            }
        }
        
        public int getSampleCount() {
            return historicalSamples.size();
        }

        public double getBaseTimePerMB() {
            return baseTimeMsPerMB;
        }

        public double getFixedTimeMs() {
            return fixedTimeMs;
        }

        public void applyPersisted(double baseTimeMsPerMB, double fixedTimeMs, int sampleCount) {
            this.baseTimeMsPerMB = baseTimeMsPerMB;
            this.fixedTimeMs = fixedTimeMs;
            historicalSamples.clear();
            for (int i = 0; i < sampleCount; i++) {
                historicalSamples.add(new TimingSample(0, 0));
            }
        }
    }
    
    private static class TimingSample {
        final double fileSizeMB;
        final long actualTimeMs;
        
        TimingSample(double fileSizeMB, long actualTimeMs) {
            this.fileSizeMB = fileSizeMB;
            this.actualTimeMs = actualTimeMs;
        }
    }
    
    private static class ModelTimingProfile {
        final String modelName;
        final double baseTimeFactor;
        final double memoryFactor;
        
        ModelTimingProfile(String modelName, double baseTimeFactor, double memoryFactor) {
            this.modelName = modelName;
            this.baseTimeFactor = baseTimeFactor;
            this.memoryFactor = memoryFactor;
        }
        
        double getTimeFactor() { return baseTimeFactor; }
        double getMemoryFactor() { return memoryFactor; }
    }
    
    /**
     * Public data classes for time estimates
     */
    public static class EnhancedTimeEstimate {
        public final long timeSpentMs;
        public final long timeLeftMs;
        public final long totalTimeMs;
        public final String context;
        
        public EnhancedTimeEstimate(long timeSpentMs, long timeLeftMs, long totalTimeMs, String context) {
            this.timeSpentMs = timeSpentMs;
            this.timeLeftMs = timeLeftMs;
            this.totalTimeMs = totalTimeMs;
            this.context = context;
        }
        
        public double getProgress() {
            return totalTimeMs > 0 ? (double) timeSpentMs / totalTimeMs : 0.0;
        }
    }
    
    public static class BatchTimeEstimate {
        public final long timeSpentMs;
        public final long timeLeftMs;
        public final long totalTimeMs;
        public final String context;
        
        public BatchTimeEstimate(long timeSpentMs, long timeLeftMs, long totalTimeMs, String context) {
            this.timeSpentMs = timeSpentMs;
            this.timeLeftMs = timeLeftMs;
            this.totalTimeMs = totalTimeMs;
            this.context = context;
        }
        
        public double getProgress() {
            return totalTimeMs > 0 ? (double) timeSpentMs / totalTimeMs : 0.0;
        }
    }
    
    /**
     * Batch statistics for UI updates
     */
    public static class BatchStatistics {
        public int totalFiles = 0;
        public int completedFiles = 0;
        public int failedFiles = 0;
        public long totalProcessingTime = 0;
        public long batchStartTime = 0;
        
        public BatchStatistics() {}
        
        public BatchStatistics(BatchStatistics other) {
            this.totalFiles = other.totalFiles;
            this.completedFiles = other.completedFiles;
            this.failedFiles = other.failedFiles;
            this.totalProcessingTime = other.totalProcessingTime;
            this.batchStartTime = other.batchStartTime;
        }
        
        public double getProgress() {
            return totalFiles > 0 ? (double) completedFiles / totalFiles : 0.0;
        }
        
        public int getRemainingFiles() {
            return totalFiles - completedFiles - failedFiles;
        }
    
        public int getCompletedFiles() { return completedFiles; }
        public int getFailedFiles() { return failedFiles; }
        public int getTotalFiles() { return totalFiles; }
        public long getTotalProcessingTime() { return totalProcessingTime; }
        public long getBatchStartTime() { return batchStartTime; }

        public double getAverageFileTime() {
            return completedFiles > 0 ? (double) totalProcessingTime / completedFiles : 0;
        }
    }
    
    /**
     * Reset the time estimator (for new batch)
     */
    public void reset() {
        this.batchStartTime.set(System.currentTimeMillis());
        this.currentBatchFiles.clear();
        this.activeFiles.clear();
        this.currentBatchStats = new BatchStatistics();
        LOGGER.info("Time estimator reset");
    }

    /**
     * Get current transcription progress (compatibility method)
     */
    public double getCurrentTranscriptionProgress() {
        return getCurrentFileProgress();
    }

    /**
     * Estimate total time left for batch
     */
    public long estimateTotalTimeLeft() {
        if (currentBatchFiles.isEmpty() && activeFiles.isEmpty()) {
            return 0;
        }

        long elapsedTime = getTotalTimeSpent();
        long estimatedTotalTime = calculateBatchTotalTime();
        return Math.max(0, estimatedTotalTime - elapsedTime);
    }

    /**
     * Clear saved time estimation data
     */
    public void clearSavedData() {
        processTimingData.clear();
        modelProfiles.clear();
        initializeDefaultProfiles();
        LOGGER.info("Time estimation data cleared");
    }

    /**
     * Save time estimation data
     */
    public void saveData() {
        persistData();
        LOGGER.debug("Time estimation data saved");
    }

    /**
     * Format time for display (ms to readable string)
     */
    public static String formatTime(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + "ms";
        }

        long seconds = milliseconds / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }

        long minutes = seconds / 60;
        seconds = seconds % 60;

        if (minutes < 60) {
            return String.format("%dm %ds", minutes, seconds);
        }

        long hours = minutes / 60;
        minutes = minutes % 60;

        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }

    /**
     * Get batch statistics
     */
    public BatchStatistics getBatchStatistics() {
        return new BatchStatistics(currentBatchStats);
    }
    
    /**
     * Save session data (called from BatchProcessor)
     */
    public void saveSessionData() {
        persistData();
        LOGGER.info("Saving session data: {} processes with historical data", getProcessCountWithData());
        LOGGER.debug("Session data saved successfully");
    }

    /**
     * Get learned pattern count - returns the number of processes with real learned data.
     */
    public int getLearnedPatternCount() {
        return (int) processTimingData.values().stream()
                .filter(data -> {
                    return data.historicalSamples.stream()
                            .anyMatch(sample -> sample.fileSizeMB > 0.1);
                })
                .count();
    }
    
    /**
     * Get the current speed multiplier.
     */
    public double getSpeedMultiplier() {
        return currentSpeedMultiplier;
    }

    /**
     * Clear learned data (called from BatchProcessor)
     */
    public void clearLearnedData() {
        processTimingData.clear();
        modelProfiles.clear();
        initializeDefaultProfiles();
        currentBatchStats = new BatchStatistics();
        LOGGER.info("Cleared all learned data - reset to default estimates");
    }

    /**
     * Get total files processed (called from BatchProcessor)
     */
    public long getTotalFilesProcessed() {
        return currentBatchStats.completedFiles;
    }

    /**
     * Get the number of processes with historical data
     */
    private int getProcessCountWithData() {
        return (int) processTimingData.values().stream()
                .filter(data -> !data.historicalSamples.isEmpty())
                .count();
    }

    /**
     * Compatibility method for old TimeTracker interface
     */
    public void recordProcessTime(String processName, String fileName, long durationMillis, 
                                 double fileSizeMB, String model) {
        if (activeFiles.containsKey(fileName)) {
            recordProcessCompletion(fileName, processName, durationMillis);
        }
        updateProcessTimingData(processName, durationMillis, fileSizeMB);
    }
    
    /** Unused by any current caller — kept for API compatibility. */
    public void updateCurrentFileProgress(double progress) {
        FileProcessingRecord display = getDisplayFile();
        if (display != null) {
            display.setActualProgress(progress);
        }
    }
    
    private double detectCpuPerformanceFactor() {
        String processor = null;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            processor = System.getenv("PROCESSOR_IDENTIFIER");
        } else {
            try {
                Path cpuinfo = Paths.get("/proc/cpuinfo");
                if (Files.exists(cpuinfo)) {
                    String content = Files.readString(cpuinfo);
                    for (String line : content.split("\n")) {
                        if (line.startsWith("model name")) {
                            processor = line.split(":")[1].trim();
                            break;
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (processor == null) return 1.0;
        String lower = processor.toLowerCase();
        if (lower.contains("i3")) return 0.7;
        if (lower.contains("i5")) return 1.0;
        if (lower.contains("i7")) return 1.5;
        if (lower.contains("i9")) return 2.0;
        if (lower.contains("xeon")) return 1.2;
        if (lower.contains("ryzen") && (lower.contains("3") || lower.contains("5"))) return 1.0;
        if (lower.contains("ryzen") && (lower.contains("7") || lower.contains("9"))) return 1.5;
        return 1.0;
    }

    // -------------------------------------------------------------------------
    //  Persistence implementation
    // -------------------------------------------------------------------------

    /**
     * Load previously-learned timing data from disk.
     */
    private void loadPersistedData() {
        if (dataFilePath == null || !Files.exists(dataFilePath)) {
            LOGGER.debug("No existing time estimator data file found — starting with defaults");
            return;
        }
        try {
            String json = new String(Files.readAllBytes(dataFilePath), StandardCharsets.UTF_8);
            PersistedData data = gson.fromJson(json, PersistedData.class);
            if (data == null) {
                return;
            }

            if (data.currentSpeedMultiplier > 0) {
                this.currentSpeedMultiplier = data.currentSpeedMultiplier;
            }

            if (data.processes != null) {
                for (Map.Entry<String, PersistedProcessTiming> entry : data.processes.entrySet()) {
                    String processName = entry.getKey();
                    PersistedProcessTiming saved = entry.getValue();
                    if (saved == null) continue;
                    ProcessTimingData timingData = processTimingData.computeIfAbsent(
                            processName, ProcessTimingData::new);
                    timingData.applyPersisted(saved.baseTimeMsPerMB, saved.fixedTimeMs, saved.sampleCount);
                }
            }

            LOGGER.info("Loaded persisted time estimator data: speed multiplier={}, {} learned process(es)",
                    String.format("%.3f", currentSpeedMultiplier),
                    data.processes != null ? data.processes.size() : 0);
        } catch (Exception e) {
            LOGGER.warn("Failed to load persisted time estimator data — starting with defaults: {}", e.getMessage());
        }
    }

    /**
     * Write current learned timing data to disk.
     */
    private synchronized void persistData() {
        if (dataFilePath == null) {
            LOGGER.warn("No time estimator data file path resolved — cannot save learned estimates");
            return;
        }
        try {
            PersistedData data = new PersistedData();
            data.currentSpeedMultiplier = this.currentSpeedMultiplier;
            data.processes = new HashMap<>();
            for (Map.Entry<String, ProcessTimingData> entry : processTimingData.entrySet()) {
                ProcessTimingData timingData = entry.getValue();
                if (timingData.getSampleCount() == 0) {
                    continue;
                }
                PersistedProcessTiming saved = new PersistedProcessTiming();
                saved.baseTimeMsPerMB = timingData.getBaseTimePerMB();
                saved.fixedTimeMs = timingData.getFixedTimeMs();
                saved.sampleCount = timingData.getSampleCount();
                data.processes.put(entry.getKey(), saved);
            }

            String json = gson.toJson(data);
            Files.write(dataFilePath, json.getBytes(StandardCharsets.UTF_8));
            LOGGER.debug("Persisted time estimator data to {}", dataFilePath);
        } catch (Exception e) {
            LOGGER.warn("Failed to persist time estimator data: {}", e.getMessage());
        }
    }

    /** On-disk shape for persisted estimator data. */
    private static class PersistedData {
        double currentSpeedMultiplier = 1.0;
        Map<String, PersistedProcessTiming> processes = new HashMap<>();
    }

    /** On-disk shape for one process's learned timing model. */
    private static class PersistedProcessTiming {
        double baseTimeMsPerMB;
        double fixedTimeMs;
        int sampleCount;
    }
}