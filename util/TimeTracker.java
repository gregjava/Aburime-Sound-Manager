/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

import audiomanager.constants.PreferenceKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced time tracking for individual processes with machine learning
 */
public class TimeTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimeTracker.class);
    
    private final Map<String, ProcessTimeData> processTimes;
    private final Map<String, List<Long>> historicalData;
    private final int maxSamples;
    
    // Batch tracking
    private long batchStartTime;
    private final Map<String, FileProcessingData> currentBatchData;
    
    public TimeTracker(int maxSamples) {
        this.processTimes = new ConcurrentHashMap<>();
        this.historicalData = new ConcurrentHashMap<>();
        this.maxSamples = maxSamples;
        this.currentBatchData = new ConcurrentHashMap<>();
        initializeDefaultProcesses();
    }
    
    private void initializeDefaultProcesses() {
        // Initialize with default process types
        String[] processes = {
            "audio_enhancement",
            "audio_preprocessing",
            "transcription_tiny",
            "transcription_base", 
            "transcription_small",
            "transcription_medium",
            "transcription_large",
            "saving_transcription",
            "file_cleanup"
        };
        
        for (String process : processes) {
            processTimes.put(process, new ProcessTimeData(process));
            historicalData.put(process, new ArrayList<>());
        }
    }
    
    
    
    /**
     * Save time estimation data to preferences
     * @param prefs  An object of type PreferenceManager.
     */
    public void saveToPreferences(PreferenceManager prefs) {
        try {
            LOGGER.info("Saving time estimation data to preferences...");
            
            // Save each process data
            for (Map.Entry<String, ProcessTimeData> entry : processTimes.entrySet()) {
                String processName = entry.getKey();
                ProcessTimeData data = entry.getValue();
                
                if (data.sampleCount > 0) { // Only save if we have data
                    String timeKey = PreferenceKeys.PROCESS_TIMES_PREFIX + processName;
                    String countKey = PreferenceKeys.SAMPLE_COUNT_PREFIX + processName;
                    
                    prefs.putDouble(timeKey, data.baseTimePerMB);
                    prefs.putInt(countKey, data.sampleCount);
                    
                    LOGGER.debug("Saved {}: {:.4f} s/MB ({} samples)", 
                               processName, data.baseTimePerMB, data.sampleCount);
                }
            }
            
            // Save timestamp
            prefs.putLong(PreferenceKeys.LAST_SAVED_TIMESTAMP, System.currentTimeMillis());
            prefs.flush();
            
            LOGGER.info("Time estimation data saved successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to save time estimation data", e);
        }
    }
    
    /**
     * Load time estimation data from preferences
     * @param prefs  An object of type PreferenceManager
     */
    public void loadFromPreferences(PreferenceManager prefs) {
        try {
            LOGGER.info("Loading time estimation data from preferences...");
            
            long lastSaved = prefs.getLong(PreferenceKeys.LAST_SAVED_TIMESTAMP, 0);
            if (lastSaved == 0) {
                LOGGER.info("No previous time estimation data found");
                return;
            }
            
            int loadedCount = 0;
            for (String processName : processTimes.keySet()) {
                String timeKey = PreferenceKeys.PROCESS_TIMES_PREFIX + processName;
                String countKey = PreferenceKeys.SAMPLE_COUNT_PREFIX + processName;
                
                double baseTime = prefs.getDouble(timeKey, -1);
                int sampleCount = prefs.getInt(countKey, 0);
                
                if (baseTime > 0 && sampleCount > 0) {
                    ProcessTimeData data = processTimes.get(processName);
                    data.baseTimePerMB = baseTime;
                    data.sampleCount = sampleCount;
                    loadedCount++;
                    
                    LOGGER.debug("Loaded {}: {:.4f} s/MB ({} samples)", 
                               processName, baseTime, sampleCount);
                }
            }
            
            LOGGER.info("Loaded time estimation data for {} processes (saved: {})", 
                       loadedCount, new Date(lastSaved));
            
        } catch (Exception e) {
            LOGGER.error("Failed to load time estimation data", e);
        }
    }
    
    /**
     * Clear all saved time estimation data
     * @param prefs  An object of type PreferenceManager
     */
    public void clearSavedData(PreferenceManager prefs) {
        try {
            LOGGER.info("Clearing time estimation data...");
            
            for (String processName : processTimes.keySet()) {
                String timeKey = PreferenceKeys.PROCESS_TIMES_PREFIX + processName;
                String countKey = PreferenceKeys.SAMPLE_COUNT_PREFIX + processName;
                
                prefs.putDouble(timeKey, -1);
                prefs.putInt(countKey, 0);
            }
            
            prefs.putLong(PreferenceKeys.LAST_SAVED_TIMESTAMP, 0);
            prefs.flush();
            
            // Reset in-memory data
            for (ProcessTimeData data : processTimes.values()) {
                data.baseTimePerMB = getDefaultBaseTime(data.processName);
                data.sampleCount = 0;
            }
            
            LOGGER.info("Time estimation data cleared");
            
        } catch (Exception e) {
            LOGGER.error("Failed to clear time estimation data", e);
        }
    }
    
    private static double getDefaultBaseTime(String processName) {
        return switch (processName) {
            case "audio_enhancement" -> 1.0;
            case "audio_preprocessing" -> 2.0;
            case "transcription_tiny" -> 3.0;
            case "transcription_base" -> 5.0;
            case "transcription_small" -> 8.0;
            case "transcription_medium" -> 15.0;
            case "transcription_large" -> 25.0;
            case "saving_transcription" -> 0.5;
            case "file_cleanup" -> 0.1;
            default -> 1.0;
        };
    }
    
    /**
     * Start tracking a batch of files
     */
    public void startBatch() {
        batchStartTime = System.currentTimeMillis();
        currentBatchData.clear();
        LOGGER.debug("Started new batch tracking at {}", batchStartTime);
    }
    
    /**
     * Start tracking a file process
     * @param fileName  The name of the file to be processed.
     */
    public void startFileProcess(String fileName) {
        FileProcessingData data = new FileProcessingData(fileName);
        data.setFileStartTime(System.currentTimeMillis());
        currentBatchData.put(fileName, data);
        LOGGER.debug("Started tracking file: {}", fileName);
    }
    
    /**
     * Record time for a specific process
     */
    public void recordProcessTime(String processName, String fileName, long durationMillis, 
                                 double fileSizeMB, String model) {
        ProcessTimeData processData = processTimes.get(processName);
        if (processData == null) {
            processData = new ProcessTimeData(processName);
            processTimes.put(processName, processData);
        }
        
        processData.recordTime(durationMillis, fileSizeMB, model);
        
        // Store in historical data
        List<Long> history = historicalData.computeIfAbsent(processName, k -> new ArrayList<>());
        history.add(durationMillis);
        if (history.size() > maxSamples) {
            history.remove(0);
        }
        
        // Update file data
        FileProcessingData fileData = currentBatchData.get(fileName);
        if (fileData != null) {
            fileData.recordProcessTime(processName, durationMillis);
        }
        
        LOGGER.debug("Recorded {}: {}ms for {:.2f}MB file with model {}", 
                    processName, durationMillis, fileSizeMB, model);
    }
    
    /**
     * Complete file processing
     */
    public void completeFileProcess(String fileName) {
        FileProcessingData data = currentBatchData.get(fileName);
        if (data != null) {
            data.setCompleted(true);
            data.setFileEndTime(System.currentTimeMillis());
            LOGGER.debug("Completed tracking for file: {} (total: {}ms)", 
                        fileName, data.getTotalFileTime());
        }
    }
    
    /**
     * Estimate time for a specific process
     */
    public long estimateProcessTime(String processName, double fileSizeMB, String model) {
        ProcessTimeData processData = processTimes.get(processName);
        if (processData == null) {
            return getDefaultEstimate(processName, fileSizeMB);
        }
        return processData.estimateTime(fileSizeMB, model);
    }
    
    /**
     * Get current file time spent
     */
    public long getCurrentFileTimeSpent(String fileName) {
        FileProcessingData data = currentBatchData.get(fileName);
        if (data == null) return 0;
        return data.getCurrentTimeSpent();
    }
    
    /**
     * Estimate current file time left
     */
    public long estimateCurrentFileTimeLeft(String fileName, List<String> remainingProcesses, 
                                           double fileSizeMB, String model) {
        FileProcessingData data = currentBatchData.get(fileName);
        if (data == null) return 0;
        
        long total = 0;
        for (String process : remainingProcesses) {
            total += estimateProcessTime(process, fileSizeMB, model);
        }
        return total;
    }
    
    /**
     * Get total time spent in current batch
     */
    public long getTotalTimeSpent() {
        return System.currentTimeMillis() - batchStartTime;
    }
    
    /**
     * Estimate total time left for batch
     */
    public long estimateTotalTimeLeft(List<FileEstimationData> remainingFiles) {
        long total = 0;
        
        // Add time for currently processing files
        for (FileProcessingData data : currentBatchData.values()) {
            if (!data.isCompleted()) {
                // Estimate remaining time for current file
                long currentSpent = data.getCurrentTimeSpent();
                long estimatedTotal = data.getEstimatedTotalTime();
                if (estimatedTotal > currentSpent) {
                    total += (estimatedTotal - currentSpent);
                }
            }
        }
        
        // Add time for queued files
        for (FileEstimationData file : remainingFiles) {
            total += estimateFileTotalTime(file.getFileSizeMB(), file.getModel(), 
                                         file.getProcesses());
        }
        
        return total;
    }
    
    /**
     * Estimate total time for a file
     */
    public long estimateFileTotalTime(double fileSizeMB, String model, List<String> processes) {
        long total = 0;
        for (String process : processes) {
            total += estimateProcessTime(process, fileSizeMB, model);
        }
        return total;
    }
    
    /**
     * Get batch statistics
     * @return  An object representing the processing statistics of the current batch.
     */
    public BatchStatistics getBatchStatistics() {
        int completed = 0;
        int failed = 0;
        long totalFileTime = 0;
        
        for (FileProcessingData data : currentBatchData.values()) {
            if (data.isCompleted()) {
                completed++;
                totalFileTime += data.getTotalFileTime();
            }
        }
        
        return new BatchStatistics(completed, failed, totalFileTime, getTotalTimeSpent());
    }
    
    private long getDefaultEstimate(String processName, double fileSizeMB) {
        // Default estimates based on process type
        return (long) (switch (processName) {
            case "audio_enhancement" -> fileSizeMB * 1000;
            case "audio_preprocessing" -> fileSizeMB * 2000;
            case "transcription_tiny" -> fileSizeMB * 3000;
            case "transcription_base" -> fileSizeMB * 5000;
            case "transcription_small" -> fileSizeMB * 8000;
            case "transcription_medium" -> fileSizeMB * 15000;
            case "transcription_large" -> fileSizeMB * 25000;
            default -> fileSizeMB * 1000;
        }); // 1 second per MB
        // 2 seconds per MB
        // 3 seconds per MB
        // 5 seconds per MB
        // 8 seconds per MB
        // 15 seconds per MB
        // 25 seconds per MB
        // Default 1 second per MB
    }
    
    /**
     * Process time data with machine learning
     */
    private static class ProcessTimeData {
        private final String processName;
        private double baseTimePerMB = 1.0;
        private int sampleCount = 0;
        private final Map<String, Double> modelFactors;
        
        public ProcessTimeData(String processName) {
            this.processName = processName;
            this.baseTimePerMB = getDefaultBaseTime(processName);
            this.sampleCount = 0;
            this.modelFactors = new HashMap<>();
            initializeModelFactors();
        }
        
        private void initializeModelFactors() {
            modelFactors.put("tiny", 0.5);
            modelFactors.put("base", 1.0);
            modelFactors.put("small", 1.5);
            modelFactors.put("medium", 2.5);
            modelFactors.put("large", 4.0);
        }
        
        public void recordTime(long durationMillis, double fileSizeMB, String model) {
            if (fileSizeMB <= 0) return;
            
            double timePerMB = (durationMillis / 1000.0) / fileSizeMB;
            double modelFactor = modelFactors.getOrDefault(model.toLowerCase(), 1.0);
            
            // Adaptive learning: weighted average
            double adjustedTime = timePerMB / modelFactor;
            if (sampleCount == 0) {
                baseTimePerMB = adjustedTime;
            } else {
                baseTimePerMB = (baseTimePerMB * 0.7) + (adjustedTime * 0.3);
            }
            
            sampleCount++;
            LOGGER.trace("Updated {}: {:.2f} s/MB (samples: {})", 
                        processName, baseTimePerMB, sampleCount);
        }
        
        public long estimateTime(double fileSizeMB, String model) {
            if (fileSizeMB <= 0) return 30000; // 30 seconds default
            
            double modelFactor = modelFactors.getOrDefault(model.toLowerCase(), 1.0);
            double estimatedSeconds = fileSizeMB * baseTimePerMB * modelFactor;
            
            // Apply safety margin and minimum time
            estimatedSeconds = Math.max(10, estimatedSeconds * 1.2);
            
            return (long) (estimatedSeconds * 1000);
        }
    }
    
    /**
     * File processing data
     */
    private static class FileProcessingData {
        private long fileStartTime;
        private long fileEndTime;
        private boolean completed = false;
        private long estimatedTotalTime;
        private final Map<String, Long> processTimes;
        
        public FileProcessingData(String fileName) {
            this.processTimes = new HashMap<>();
        }
        
        public void setFileStartTime(long startTime) {
            this.fileStartTime = startTime;
        }
        
        public void setFileEndTime(long endTime) {
            this.fileEndTime = endTime;
        }
        
        public void setCompleted(boolean completed) {
            this.completed = completed;
        }
        
        public void setEstimatedTotalTime(long estimatedTime) {
            this.estimatedTotalTime = estimatedTime;
        }
        
        public void recordProcessTime(String processName, long duration) {
            processTimes.put(processName, duration);
        }
        
        public long getCurrentTimeSpent() {
            if (completed) {
                return fileEndTime - fileStartTime;
            }
            return System.currentTimeMillis() - fileStartTime;
        }
        
        public long getTotalFileTime() {
            if (!completed) return getCurrentTimeSpent();
            return fileEndTime - fileStartTime;
        }
        
        public long getEstimatedTotalTime() {
            return estimatedTotalTime;
        }
        
        public boolean isCompleted() {
            return completed;
        }
    }
    
    /**
     * Data class for file estimation
     */
    public static class FileEstimationData {
        private final double fileSizeMB;
        private final String model;
        private final List<String> processes;
        
        public FileEstimationData(double fileSizeMB, String model, List<String> processes) {
            this.fileSizeMB = fileSizeMB;
            this.model = model;
            this.processes = processes;
        }
        
        public double getFileSizeMB() { return fileSizeMB; }
        public String getModel() { return model; }
        public List<String> getProcesses() { return processes; }
    }
    
    /**
     * Batch statistics
     */
    public static class BatchStatistics {
        private final int completedFiles;
        private final int failedFiles;
        private final long totalFileProcessingTime;
        private final long totalBatchTime;
        
        public BatchStatistics(int completedFiles, int failedFiles, 
                              long totalFileProcessingTime, long totalBatchTime) {
            this.completedFiles = completedFiles;
            this.failedFiles = failedFiles;
            this.totalFileProcessingTime = totalFileProcessingTime;
            this.totalBatchTime = totalBatchTime;
        }
        
        public int getCompletedFiles() { return completedFiles; }
        public int getFailedFiles() { return failedFiles; }
        public long getTotalFileProcessingTime() { return totalFileProcessingTime; }
        public long getTotalBatchTime() { return totalBatchTime; }
        public double getAverageFileTime() {
            return completedFiles > 0 ? (double) totalFileProcessingTime / completedFiles : 0;
        }
    }
}