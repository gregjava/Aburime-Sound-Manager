/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

/**
 * Configuration for audio processing operations.
 *
 * <p>This class holds all settings that control how audio files are processed
 * before transcription, including:
 * <ul>
 *   <li><b>Output format:</b> The target format (mp3, wav, ogg, etc.)</li>
 *   <li><b>Bitrate:</b> The output bitrate for compressed formats</li>
 *   <li><b>Volume processing:</b> Normalisation and volume boost settings</li>
 *   <li><b>Audio filtering:</b> Noise reduction and silence removal</li>
 *   <li><b>Output directory:</b> Where to save processed files</li>
 * </ul>
 *
 * <p><b>Immutability:</b> This class is immutable once built, using the
 * Builder pattern for construction.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see Builder
 */
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
    private final boolean autoVolumeOptimization;

    /**
     * Private constructor for use by the Builder.
     *
     * @param builder the builder containing the configuration values
     */
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

    // ========================================================================
    //  Getters
    // ========================================================================

    /**
     * Returns the output directory path.
     *
     * @return the output directory
     */
    public String getOutputDirectory() { return outputDirectory; }

    /**
     * Returns the output format extension (e.g., "mp3", "wav", "ogg").
     *
     * @return the output format
     */
    public String getOutputFormat() { return outputFormat; }

    /**
     * Returns the output bitrate (e.g., "192k", "128k").
     *
     * @return the bitrate
     */
    public String getBitrate() { return bitrate; }

    /**
     * Returns the volume boost in decibels.
     *
     * @return the volume boost
     */
    public double getVolumeBoost() { return volumeBoost; }

    /**
     * Returns the silence detection threshold in decibels.
     *
     * @return the silence threshold
     */
    public double getSilenceThreshold() { return silenceThreshold; }

    /**
     * Returns the silence duration in seconds.
     *
     * @return the silence duration
     */
    public double getSilenceDuration() { return silenceDuration; }

    /**
     * Returns whether noise reduction is enabled.
     *
     * @return {@code true} if noise reduction is enabled
     */
    public boolean isNoiseReduction() { return noiseReduction; }

    /**
     * Returns whether silence removal is enabled.
     *
     * @return {@code true} if silence removal is enabled
     */
    public boolean isRemoveSilence() { return removeSilence; }

    /**
     * Returns whether loudness normalisation is enabled.
     *
     * @return {@code true} if normalisation is enabled
     */
    public boolean isNormalize() { return normalize; }

    /**
     * Returns whether processed audio files should be kept.
     *
     * @return {@code true} if processed audio should be kept
     */
    public boolean isKeepProcessedAudio() { return keepProcessedAudio; }

    /**
     * Returns whether transcription is enabled.
     *
     * @return {@code true} if transcription is enabled
     */
    public boolean isTranscriptionEnabled() { return transcriptionEnabled; }

    /**
     * Returns whether auto-volume optimisation is enabled.
     *
     * @return {@code true} if auto-volume optimisation is enabled
     */
    public boolean isAutoVolumeOptimization() {
        return autoVolumeOptimization;
    }

    // ========================================================================
    //  Builder
    // ========================================================================

    /**
     * Builder for creating ProcessingConfig instances.
     */
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

        /**
         * Sets the output directory.
         *
         * @param outputDirectory the output directory path
         * @return this builder
         */
        public Builder outputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        /**
         * Sets the output format.
         *
         * @param outputFormat the output format extension
         * @return this builder
         */
        public Builder outputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        /**
         * Sets the output bitrate.
         *
         * @param bitrate the bitrate (e.g., "192k")
         * @return this builder
         */
        public Builder bitrate(String bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the volume boost in decibels.
         *
         * @param volumeBoost the volume boost (positive values amplify)
         * @return this builder
         */
        public Builder volumeBoost(double volumeBoost) {
            this.volumeBoost = volumeBoost;
            return this;
        }

        /**
         * Sets the silence detection threshold.
         *
         * @param silenceThreshold the threshold in decibels
         * @return this builder
         */
        public Builder silenceThreshold(double silenceThreshold) {
            this.silenceThreshold = silenceThreshold;
            return this;
        }

        /**
         * Sets the silence duration in seconds.
         *
         * @param silenceDuration the duration in seconds
         * @return this builder
         */
        public Builder silenceDuration(double silenceDuration) {
            this.silenceDuration = silenceDuration;
            return this;
        }

        /**
         * Sets whether noise reduction is enabled.
         *
         * @param noiseReduction {@code true} to enable noise reduction
         * @return this builder
         */
        public Builder noiseReduction(boolean noiseReduction) {
            this.noiseReduction = noiseReduction;
            return this;
        }

        /**
         * Sets whether silence removal is enabled.
         *
         * @param removeSilence {@code true} to enable silence removal
         * @return this builder
         */
        public Builder removeSilence(boolean removeSilence) {
            this.removeSilence = removeSilence;
            return this;
        }

        /**
         * Sets whether loudness normalisation is enabled.
         *
         * @param normalize {@code true} to enable normalisation
         * @return this builder
         */
        public Builder normalize(boolean normalize) {
            this.normalize = normalize;
            return this;
        }

        /**
         * Sets whether processed audio files should be kept.
         *
         * @param keepProcessedAudio {@code true} to keep processed audio
         * @return this builder
         */
        public Builder keepProcessedAudio(boolean keepProcessedAudio) {
            this.keepProcessedAudio = keepProcessedAudio;
            return this;
        }

        /**
         * Sets whether transcription is enabled.
         *
         * @param transcriptionEnabled {@code true} to enable transcription
         * @return this builder
         */
        public Builder transcriptionEnabled(boolean transcriptionEnabled) {
            this.transcriptionEnabled = transcriptionEnabled;
            return this;
        }

        /**
         * Sets whether auto-volume optimisation is enabled.
         *
         * @param enabled {@code true} to enable auto-volume optimisation
         * @return this builder
         */
        public Builder autoVolumeOptimization(boolean enabled) {
            this.autoVolumeOptimization = enabled;
            return this;
        }

        /**
         * Builds the ProcessingConfig instance.
         *
         * @return a new ProcessingConfig
         * @throws IllegalStateException if the output directory is not set
         */
        public ProcessingConfig build() {
            if (outputDirectory == null || outputDirectory.isEmpty()) {
                throw new IllegalStateException("Output directory is required");
            }
            return new ProcessingConfig(this);
        }
    }
}