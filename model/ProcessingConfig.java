/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

public class ProcessingConfig {
    private final String outputDirectory;
    private final String outputFormat;
    private final String bitrate;
    private final double volumeBoost;
    private final double silenceThreshold;
    private final double silenceDuration;
    private final boolean noiseReduction;
    private final boolean removeSilence;
    private final boolean normalize;
    private final boolean keepProcessedAudio;
    private final boolean transcriptionEnabled;
    // Add to ProcessingConfig class
    private final boolean autoVolumeOptimization;

    private ProcessingConfig(Builder builder) {
        this.outputDirectory = builder.outputDirectory;
        this.outputFormat = builder.outputFormat;
        this.bitrate = builder.bitrate;
        this.volumeBoost = builder.volumeBoost;
        this.silenceThreshold = builder.silenceThreshold;
        this.silenceDuration = builder.silenceDuration;
        this.noiseReduction = builder.noiseReduction;
        this.removeSilence = builder.removeSilence;
        this.normalize = builder.normalize;
        this.keepProcessedAudio = builder.keepProcessedAudio;
        this.transcriptionEnabled = builder.transcriptionEnabled;
        this.autoVolumeOptimization = builder.autoVolumeOptimization;
    }

    // Getters
    public String getOutputDirectory() { return outputDirectory; }
    public String getOutputFormat() { return outputFormat; }
    public String getBitrate() { return bitrate; }
    public double getVolumeBoost() { return volumeBoost; }
    public double getSilenceThreshold() { return silenceThreshold; }
    public double getSilenceDuration() { return silenceDuration; }
    public boolean isNoiseReduction() { return noiseReduction; }
    public boolean isRemoveSilence() { return removeSilence; }
    public boolean isNormalize() { return normalize; }
    public boolean isKeepProcessedAudio() { return keepProcessedAudio; }
    public boolean isTranscriptionEnabled() { return transcriptionEnabled; }

    public static class Builder {
        private String outputDirectory;
        private String outputFormat = "mp3";
        private String bitrate = "128k";
        private double volumeBoost = 0.0;
        private double silenceThreshold = -50.0;
        private double silenceDuration = 1.5;
        private boolean noiseReduction = false;
        private boolean removeSilence = false;
        private boolean normalize = false;
        private boolean keepProcessedAudio = false;
        private boolean transcriptionEnabled = true;
        private boolean autoVolumeOptimization = true;

        public Builder outputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public Builder outputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public Builder bitrate(String bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        public Builder volumeBoost(double volumeBoost) {
            this.volumeBoost = volumeBoost;
            return this;
        }

        public Builder silenceThreshold(double silenceThreshold) {
            this.silenceThreshold = silenceThreshold;
            return this;
        }

        public Builder silenceDuration(double silenceDuration) {
            this.silenceDuration = silenceDuration;
            return this;
        }

        public Builder noiseReduction(boolean noiseReduction) {
            this.noiseReduction = noiseReduction;
            return this;
        }

        public Builder removeSilence(boolean removeSilence) {
            this.removeSilence = removeSilence;
            return this;
        }

        public Builder normalize(boolean normalize) {
            this.normalize = normalize;
            return this;
        }

        public Builder keepProcessedAudio(boolean keepProcessedAudio) {
            this.keepProcessedAudio = keepProcessedAudio;
            return this;
        }

        public Builder transcriptionEnabled(boolean transcriptionEnabled) {
            this.transcriptionEnabled = transcriptionEnabled;
            return this;
        }

        public ProcessingConfig build() {
            if (outputDirectory == null || outputDirectory.isEmpty()) {
                throw new IllegalStateException("Output directory is required");
            }
            return new ProcessingConfig(this);
        }

        public Builder autoVolumeOptimization(boolean enabled) {
            this.autoVolumeOptimization = enabled;
            return this;
        }
    }

    // Add getter
    public boolean isAutoVolumeOptimization() {
        return autoVolumeOptimization;
    }
}