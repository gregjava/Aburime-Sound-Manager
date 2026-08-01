/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes audio volume levels and determines optimal amplification
 */
public class AudioVolumeAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioVolumeAnalyzer.class);
    
    // Target volume levels (in dB)
    private static final double TARGET_MAX_DB = -1.0;  // Target maximum volume (just below 0dB to avoid clipping)
    private static final double TARGET_RMS_DB = -16.0; // Target average volume (professional level)
    private static final double MIN_ACCEPTABLE_MAX_DB = -10.0; // Minimum acceptable max volume
    private static final double MAX_AMPLIFICATION_FACTOR = 10.0; // Maximum amplification (10x = 20dB)
    
    /**
     * Volume analysis result
     */
    public static class VolumeAnalysis {
        private final double maxVolume;      // Maximum volume in dB
        private final double avgVolume;      // Average (RMS) volume in dB
        private final double minVolume;      // Minimum volume in dB
        private final double dynamicRange;   // Dynamic range in dB
        private final double recommendedGain; // Recommended gain in dB
        private final boolean needsAmplification;
        private final String recommendation;
        
        public VolumeAnalysis(double maxVolume, double avgVolume, double minVolume) {
            this.maxVolume = maxVolume;
            this.avgVolume = avgVolume;
            this.minVolume = minVolume;
            this.dynamicRange = maxVolume - minVolume;
            
            // Calculate recommended gain
            double gainNeeded = 0;
            
            // Check if we need amplification based on max volume
            if (maxVolume < MIN_ACCEPTABLE_MAX_DB) {
                // Amplify to reach TARGET_MAX_DB, but don't exceed MAX_AMPLIFICATION_FACTOR
                gainNeeded = Math.min(TARGET_MAX_DB - maxVolume, 20 * Math.log10(MAX_AMPLIFICATION_FACTOR));
                needsAmplification = true;
                recommendation = String.format("Low volume detected (max: %.1fdB). Recommended gain: %.1fdB", 
                    maxVolume, gainNeeded);
            } 
            // Check if average volume is too low even if peaks are OK
            else if (avgVolume < TARGET_RMS_DB - 5) {
                gainNeeded = Math.min(TARGET_RMS_DB - avgVolume, 20 * Math.log10(MAX_AMPLIFICATION_FACTOR));
                needsAmplification = true;
                recommendation = String.format("Low average volume (avg: %.1fdB). Recommended gain: %.1fdB", 
                    avgVolume, gainNeeded);
            }
            // Check if dynamic range is too high (very quiet and very loud parts)
            else if (dynamicRange > 40 && minVolume < -50) {
                // Suggest compression instead of just amplification
                needsAmplification = true;
                gainNeeded = Math.min(TARGET_MAX_DB - maxVolume, 10); // Limit to 10dB
                recommendation = String.format("High dynamic range (%.1fdB). Consider compression. Minimal gain: %.1fdB", 
                    dynamicRange, gainNeeded);
            }
            else {
                needsAmplification = false;
                recommendation = "Volume levels are adequate. No amplification needed.";
            }
            
            this.recommendedGain = Math.max(0, gainNeeded);
        }
        
        public double getMaxVolume() { return maxVolume; }
        public double getAvgVolume() { return avgVolume; }
        public double getMinVolume() { return minVolume; }
        public double getDynamicRange() { return dynamicRange; }
        public double getRecommendedGain() { return recommendedGain; }
        public boolean needsAmplification() { return needsAmplification; }
        public String getRecommendation() { return recommendation; }
        
        /**
         * Get amplification factor (linear, not dB)
         */
        public double getAmplificationFactor() {
            return Math.pow(10, recommendedGain / 20);
        }
        
        @Override
        public String toString() {
            return String.format(
                "VolumeAnalysis{max=%.1fdB, avg=%.1fdB, min=%.1fdB, range=%.1fdB, gain=%.1fdB, needs=%s}",
                maxVolume, avgVolume, minVolume, dynamicRange, recommendedGain, needsAmplification);
        }
    }
    
    /**
     * Analyze audio file volume levels
     */
    public VolumeAnalysis analyzeVolume(String audioFilePath) throws Exception {
        LOGGER.info("Analyzing volume levels for: {}", audioFilePath);
        
        Path audioPath = Paths.get(audioFilePath);
        if (!Files.exists(audioPath)) {
            throw new Exception("Audio file not found: " + audioFilePath);
        }
        
        // Use ffmpeg to get volume statistics
        List<String> command = Arrays.asList(
            "ffmpeg",
            "-i", audioFilePath,
            "-af", "volumedetect",
            "-f", "null",
            "-"
        );
        
        StringBuilder output = new StringBuilder();
        int exitCode = ProcessRunner.runCommand(
            command,
            30, // 30 second timeout
            TimeUnit.SECONDS,
            line -> output.append(line).append("\n"),
            null
        );
        
        if (exitCode != 0) {
            throw new Exception("Failed to analyze audio: " + output);
        }
        
        return parseVolumeAnalysis(output.toString());
    }
    
    /**
     * Parse ffmpeg volumedetect output
     */
    private VolumeAnalysis parseVolumeAnalysis(String ffmpegOutput) {
        double maxVolume = -999;
        double avgVolume = -999;
        double minVolume = 999;
        
        Pattern maxPattern = Pattern.compile("max_volume: ([\\-\\d.]+) dB");
        Pattern meanPattern = Pattern.compile("mean_volume: ([\\-\\d.]+) dB");
        Pattern minPattern = Pattern.compile("min_volume: ([\\-\\d.]+) dB");
        
        Matcher maxMatcher = maxPattern.matcher(ffmpegOutput);
        Matcher meanMatcher = meanPattern.matcher(ffmpegOutput);
        Matcher minMatcher = minPattern.matcher(ffmpegOutput);
        
        if (maxMatcher.find()) {
            maxVolume = Double.parseDouble(maxMatcher.group(1));
        }
        if (meanMatcher.find()) {
            avgVolume = Double.parseDouble(meanMatcher.group(1));
        }
        if (minMatcher.find()) {
            minVolume = Double.parseDouble(minMatcher.group(1));
        }
        
        LOGGER.debug("Parsed volumes - max: {}dB, avg: {}dB, min: {}dB", 
            maxVolume, avgVolume, minVolume);
        
        return new VolumeAnalysis(maxVolume, avgVolume, minVolume);
    }
    
    /**
     * Apply amplification to audio file
     */
    public String amplifyAudio(String inputFilePath, double gainDb, String outputDir) throws Exception {
        LOGGER.info("Amplifying audio by {}dB: {}", gainDb, inputFilePath);
        
        Path inputPath = Paths.get(inputFilePath);
        String fileName = inputPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        
        Path outputPath;
        if (outputDir != null) {
            outputPath = Paths.get(outputDir, baseName + "_amplified.wav");
        } else {
            outputPath = inputPath.getParent().resolve(baseName + "_amplified.wav");
        }
        
        // Apply amplification using ffmpeg
        List<String> command = Arrays.asList(
            "ffmpeg",
            "-y",  // Overwrite output
            "-i", inputFilePath,
            "-af", String.format("volume=%fdB", gainDb),
            "-c:a", "pcm_s16le",  // Convert to WAV
            "-ar", "16000",        // 16kHz for Whisper
            "-ac", "1",            // Mono
            outputPath.toString()
        );
        
        StringBuilder output = new StringBuilder();
        int exitCode = ProcessRunner.runCommand(
            command,
            60, // 60 second timeout
            TimeUnit.SECONDS,
            line -> {
                LOGGER.debug("FFmpeg: {}", line);
                output.append(line).append("\n");
            },
            null
        );
        
        if (exitCode != 0) {
            throw new Exception("Failed to amplify audio: " + output);
        }
        
        LOGGER.info("Amplified audio saved to: {}", outputPath);
        return outputPath.toString();
    }
    
    /**
     * Quick check if audio needs amplification
     */
    public boolean needsAmplification(String audioFilePath) throws Exception {
        VolumeAnalysis analysis = analyzeVolume(audioFilePath);
        return analysis.needsAmplification();
    }
    
    /**
     * Get optimal amplification in dB
     */
    public double getOptimalAmplification(String audioFilePath) throws Exception {
        VolumeAnalysis analysis = analyzeVolume(audioFilePath);
        return analysis.getRecommendedGain();
    }
    
    /**
     * Format volume analysis for display
     */
    public String formatAnalysis(VolumeAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📊 Audio Volume Analysis:\n");
        sb.append(String.format("  Max Volume: %.1f dB\n", analysis.getMaxVolume()));
        sb.append(String.format("  Avg Volume: %.1f dB\n", analysis.getAvgVolume()));
        sb.append(String.format("  Min Volume: %.1f dB\n", analysis.getMinVolume()));
        sb.append(String.format("  Dynamic Range: %.1f dB\n", analysis.getDynamicRange()));
        sb.append("\n💡 Recommendation: ").append(analysis.getRecommendation());
        
        if (analysis.needsAmplification()) {
            sb.append(String.format("\n🔊 Recommended gain: %.1f dB (%.2fx amplification)", 
                analysis.getRecommendedGain(), analysis.getAmplificationFactor()));
        }
        
        return sb.toString();
    }
}