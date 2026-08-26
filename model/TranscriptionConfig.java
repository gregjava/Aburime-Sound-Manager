/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import audiomanager.constants.AppConstants;

/**
 * Immutable configuration for a single transcription operation.
 *
 * <p>This class holds all settings that control how transcription is performed,
 * including:
 * <ul>
 *   <li><b>Model selection:</b> Which Whisper model to use (tiny, base, small, medium, large-v2, etc.)</li>
 *   <li><b>Language:</b> Source language for transcription (or auto-detection)</li>
 *   <li><b>Output format:</b> SRT, TXT, JSON, or BOTH</li>
 *   <li><b>Diarisation:</b> Speaker diarisation settings</li>
 *   <li><b>Segmentation:</b> Maximum segment duration for long files</li>
 *   <li><b>Translation:</b> Post-transcription translation settings</li>
 *   <li><b>Advanced options:</b> Confidence scores, timestamps, SRT formatting</li>
 *   <li><b>Streaming:</b> Enable streaming transcription for large files</li>
 * </ul>
 *
 * <p><b>Immutability:</b> This class is immutable once built, using the
 * Builder pattern for construction. Use the {@link Builder} for construction.
 * All numeric fields are validated in the builder setter methods — invalid
 * values are clamped to their documented safe ranges rather than rejected
 * with exceptions.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see Builder
 * @see OutputFormat
 * @see TimestampMode
 */
public final class TranscriptionConfig {

    // -------------------------------------------------------------------------
    //  Fields
    // -------------------------------------------------------------------------

    // Whisper model settings
    private final String model;
    private final String language;

    // Output settings
    private final boolean timestampsEnabled;
    private final boolean confidenceEnabled;
    private final OutputFormat outputFormat;

    // Audio preprocessing
    private final float volumeBoost;
    private final float silenceThreshold;
    private final float silenceDuration;
    private final boolean noiseReduction;

    // SRT formatting
    private final int srtMaxChars;
    private final int srtMaxLines;

    // Advanced features
    private final boolean diarizeEnabled;
    private final String  hfToken;       // resolved externally; never hard-coded
    private final float   maxSegmentDuration; // seconds — used by SegmentProcessor

    // ===== Translation Settings =====
    private final boolean translationEnabled;
    private final String translationTargetLanguage;
    private final String translationEndpoint;
    private final String translationApiKey;

    // Pipeline control
    private final boolean skipSegmentation;
    private final boolean enabled;
    private final boolean streamingEnabled;  // Enable streaming for large files

    // -------------------------------------------------------------------------
    //  Private constructor (use Builder)
    // -------------------------------------------------------------------------

    /**
     * Private constructor for use by the Builder.
     *
     * @param b the builder containing the configuration values
     */
    private TranscriptionConfig(Builder b) {
        this.model              = b.model;
        this.language           = b.language;
        this.timestampsEnabled  = b.timestampsEnabled;
        this.confidenceEnabled  = b.confidenceEnabled;
        this.outputFormat       = b.outputFormat;
        this.volumeBoost        = b.volumeBoost;
        this.silenceThreshold   = b.silenceThreshold;
        this.silenceDuration    = b.silenceDuration;
        this.noiseReduction     = b.noiseReduction;
        this.srtMaxChars        = b.srtMaxChars;
        this.srtMaxLines        = b.srtMaxLines;
        this.diarizeEnabled     = b.diarizeEnabled;
        this.hfToken            = b.hfToken;
        this.maxSegmentDuration = b.maxSegmentDuration;
        this.skipSegmentation   = b.skipSegmentation;
        this.enabled            = b.enabled;
        this.streamingEnabled   = b.streamingEnabled;
        
        // ===== Translation fields =====
        this.translationEnabled       = b.translationEnabled;
        this.translationTargetLanguage = b.translationTargetLanguage;
        this.translationEndpoint      = b.translationEndpoint;
        this.translationApiKey        = b.translationApiKey;
    }

    // -------------------------------------------------------------------------
    //  Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the Whisper model name.
     *
     * @return the model name
     */
    public String getModel() { return model; }

    /**
     * Returns the source language code.
     *
     * @return the language code, or "auto" for auto-detection
     */
    public String getLanguage() { return language; }

    /**
     * Returns whether timestamps are enabled in the output.
     *
     * @return {@code true} if timestamps are enabled
     */
    public boolean isTimestampsEnabled() { return timestampsEnabled; }

    /**
     * Returns whether confidence scores are included.
     *
     * @return {@code true} if confidence scores are included
     */
    public boolean isConfidenceEnabled() { return confidenceEnabled; }

    /**
     * Returns the output format.
     *
     * @return the output format (SRT, TXT, JSON, or BOTH)
     */
    public OutputFormat getOutputFormat() { return outputFormat; }

    /**
     * Returns the volume boost in decibels.
     *
     * @return the volume boost (clamped to 0-5 dB)
     */
    public float getVolumeBoost() { return volumeBoost; }

    /**
     * Returns the silence detection threshold in decibels.
     *
     * @return the silence threshold (clamped to -60 to 0 dB)
     */
    public float getSilenceThreshold() { return silenceThreshold; }

    /**
     * Returns the silence duration in seconds.
     *
     * @return the silence duration (minimum 0.1 seconds)
     */
    public float getSilenceDuration() { return silenceDuration; }

    /**
     * Returns whether noise reduction is enabled.
     *
     * @return {@code true} if noise reduction is enabled
     */
    public boolean isNoiseReduction() { return noiseReduction; }

    /**
     * Returns the maximum characters per SRT line.
     *
     * @return the maximum characters (minimum 20)
     */
    public int getSrtMaxChars() { return srtMaxChars; }

    /**
     * Returns the maximum lines per SRT block.
     *
     * @return the maximum lines (minimum 1)
     */
    public int getSrtMaxLines() { return srtMaxLines; }

    /**
     * Returns whether speaker diarisation is enabled.
     *
     * @return {@code true} if diarisation is enabled
     */
    public boolean isDiarizeEnabled() { return diarizeEnabled; }

    /**
     * Returns the HuggingFace token for diarisation.
     *
     * @return the HF token, or {@code null} if not set
     */
    public String getHfToken() { return hfToken; }

    /**
     * Returns the maximum segment duration in seconds.
     *
     * <p>Used by {@link audiomanager.core.SegmentProcessor} to set the FFmpeg
     * {@code -segment_time} parameter. A value ≤ 0 means "use the processor
     * default" (currently 30 s).</p>
     *
     * @return the maximum segment duration
     */
    public float getMaxSegmentDuration() { return maxSegmentDuration; }

    /**
     * Returns whether segmentation should be skipped.
     *
     * @return {@code true} if segmentation should be skipped
     */
    public boolean isSkipSegmentation() { return skipSegmentation; }

    /**
     * Returns whether transcription is enabled.
     *
     * @return {@code true} if transcription is enabled
     */
    public boolean isEnabled() { return enabled; }

    /**
     * Returns whether streaming transcription is enabled for large files.
     *
     * <p>When enabled, files larger than 100MB will be split into chunks
     * and processed in parallel to reduce memory usage. Default is true.</p>
     *
     * @return {@code true} if streaming is enabled
     */
    public boolean isStreamingEnabled() { return streamingEnabled; }

    // ===== Translation Getters =====

    /**
     * Returns whether translation is enabled for transcripts.
     *
     * <p>When enabled, transcripts will be translated to the target language
     * after transcription completes.</p>
     *
     * @return {@code true} if translation is enabled
     */
    public boolean isTranslationEnabled() { return translationEnabled; }

    /**
     * Returns the target language code for translation.
     *
     * <p>This should be an ISO 639-1 language code (e.g., "es", "fr", "de").
     * The default is "es" (Spanish).</p>
     *
     * @return the target language code
     */
    public String getTranslationTargetLanguage() { return translationTargetLanguage; }

    /**
     * Returns the translation endpoint URL.
     *
     * <p>This should be a LibreTranslate-compatible REST API endpoint.
     * Default is {@code https://libretranslate.com/translate}.</p>
     *
     * @return the translation endpoint URL
     */
    public String getTranslationEndpoint() { return translationEndpoint; }

    /**
     * Returns the API key for the translation service.
     *
     * <p>Some translation services require an API key. This can be null
     * for services that don't require authentication.</p>
     *
     * @return the API key, or {@code null} if not set
     */
    public String getTranslationApiKey() { return translationApiKey; }

    // -------------------------------------------------------------------------
    //  Factory methods
    // -------------------------------------------------------------------------

    /**
     * Returns a default configuration optimised for WhisperX.
     *
     * @return a default configuration with streaming enabled
     */
    public static TranscriptionConfig createDefault() {
        return builder()
                .streamingEnabled(true)
                .translationEnabled(false)
                .build();
    }

    /**
     * Returns a configuration with speaker diarisation enabled.
     *
     * @param hfToken the HuggingFace token for diarisation
     * @return a configuration with diarisation enabled
     */
    public static TranscriptionConfig withDiarization(String hfToken) {
        return builder()
                .timestampsEnabled(true)
                .diarizeEnabled(true)
                .hfToken(hfToken)
                .maxSegmentDuration(30.0f)
                .streamingEnabled(true)
                .translationEnabled(false)
                .build();
    }

    /**
     * Returns a configuration with translation enabled.
     *
     * @param targetLanguage the target language code (e.g., "es", "fr")
     * @param endpoint the translation endpoint URL
     * @param apiKey the API key (may be null)
     * @return a configuration with translation enabled
     */
    public static TranscriptionConfig withTranslation(String targetLanguage, 
                                                       String endpoint, 
                                                       String apiKey) {
        return builder()
                .timestampsEnabled(true)
                .outputFormat(OutputFormat.BOTH)
                .translationEnabled(true)
                .translationTargetLanguage(targetLanguage)
                .translationEndpoint(endpoint)
                .translationApiKey(apiKey)
                .streamingEnabled(true)
                .build();
    }

    /**
     * Returns a SRT-only subtitle output configuration.
     *
     * @return a configuration optimised for SRT subtitles
     */
    public static TranscriptionConfig forSubtitles() {
        return builder()
                .timestampsEnabled(true)
                .outputFormat(OutputFormat.SRT)
                .maxSegmentDuration(10.0f)  // shorter segments for tight subtitles
                .streamingEnabled(true)
                .translationEnabled(false)
                .build();
    }

    /**
     * Returns a plain-text output configuration.
     *
     * @return a configuration optimised for plain text output
     */
    public static TranscriptionConfig forTextOnly() {
        return builder()
                .timestampsEnabled(false)
                .outputFormat(OutputFormat.TXT)
                .maxSegmentDuration(15.0f)
                .streamingEnabled(true)
                .translationEnabled(false)
                .build();
    }

    /**
     * Returns a high-quality configuration using the large-v3 model.
     *
     * @return a high-quality configuration
     */
    public static TranscriptionConfig highQuality() {
        return builder()
                .model("large-v3")
                .timestampsEnabled(true)
                .confidenceEnabled(true)
                .silenceThreshold(-35.0f)
                .silenceDuration(1.5f)
                .srtMaxChars(100)
                .srtMaxLines(3)
                .maxSegmentDuration(30.0f)
                .streamingEnabled(true)
                .translationEnabled(false)
                .build();
    }

    /**
     * Returns a configuration with user-friendly defaults for first-time users.
     *
     * @return a user-friendly configuration
     */
    public static TranscriptionConfig userFriendly() {
        return builder()
                .model("small")
                .language("auto")
                .timestampsEnabled(true)
                .confidenceEnabled(true)
                .outputFormat(OutputFormat.BOTH)
                .volumeBoost(1.5f)
                .noiseReduction(true)
                .srtMaxChars(80)
                .srtMaxLines(3)
                .maxSegmentDuration(30.0f)
                .streamingEnabled(true)
                .translationEnabled(false)
                .build();
    }

    /**
     * Returns a new Builder instance.
     *
     * @return a new Builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Copies this config into a fresh Builder for modification.
     *
     * @return a Builder pre-populated with this config's values
     */
    public Builder toBuilder() {
        return new Builder()
                .model(model)
                .language(language)
                .timestampsEnabled(timestampsEnabled)
                .confidenceEnabled(confidenceEnabled)
                .outputFormat(outputFormat)
                .volumeBoost(volumeBoost)
                .silenceThreshold(silenceThreshold)
                .silenceDuration(silenceDuration)
                .noiseReduction(noiseReduction)
                .srtMaxChars(srtMaxChars)
                .srtMaxLines(srtMaxLines)
                .diarizeEnabled(diarizeEnabled)
                .hfToken(hfToken)
                .maxSegmentDuration(maxSegmentDuration)
                .skipSegmentation(skipSegmentation)
                .enabled(enabled)
                .streamingEnabled(streamingEnabled)
                // ===== Translation fields =====
                .translationEnabled(translationEnabled)
                .translationTargetLanguage(translationTargetLanguage)
                .translationEndpoint(translationEndpoint)
                .translationApiKey(translationApiKey);
    }

    // -------------------------------------------------------------------------
    //  Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link TranscriptionConfig}.
     *
     * <p>All setters apply safe clamping rather than throwing exceptions.</p>
     */
    public static final class Builder {
        private String       model             = AppConstants.DEFAULT_MODEL;
        private String       language          = AppConstants.DEFAULT_LANGUAGE;
        private boolean      timestampsEnabled = false;
        private boolean      confidenceEnabled = false;
        private OutputFormat outputFormat      = OutputFormat.BOTH;
        private float        volumeBoost       = 1.5f;
        private float        silenceThreshold  = -40.0f;
        private float        silenceDuration   = 1.0f;
        private boolean      noiseReduction    = true;
        private int          srtMaxChars       = 80;
        private int          srtMaxLines       = 3;
        private boolean      diarizeEnabled    = false;
        private String       hfToken           = null;
        private float        maxSegmentDuration = 30.0f;
        private boolean      skipSegmentation  = false;
        private boolean      enabled           = true;
        private boolean      streamingEnabled  = true;
        
        // ===== Translation fields =====
        private boolean      translationEnabled = false;
        private String       translationTargetLanguage = "es";
        private String       translationEndpoint = "https://libretranslate.com/translate";
        private String       translationApiKey = null;

        // ===== Model setters =====

        /**
         * Sets the Whisper model.
         *
         * @param model the model name (tiny, base, small, medium, large-v2, large-v3)
         * @return this builder
         */
        public Builder model(String model) {
            this.model = (model != null && !model.isBlank()) ? model : AppConstants.DEFAULT_MODEL;
            return this;
        }

        /**
         * Sets the source language.
         *
         * @param language the language code, or "auto" for auto-detection
         * @return this builder
         */
        public Builder language(String language) {
            this.language = (language != null) ? language : AppConstants.DEFAULT_LANGUAGE;
            return this;
        }

        /**
         * Sets whether timestamps are enabled.
         *
         * @param v {@code true} to enable timestamps
         * @return this builder
         */
        public Builder timestampsEnabled(boolean v) { this.timestampsEnabled = v; return this; }

        /**
         * Sets whether confidence scores are included.
         *
         * @param v {@code true} to include confidence scores
         * @return this builder
         */
        public Builder confidenceEnabled(boolean v) { this.confidenceEnabled = v; return this; }

        /**
         * Sets the output format.
         *
         * @param f the output format
         * @return this builder
         */
        public Builder outputFormat(OutputFormat f) { this.outputFormat = f != null ? f : OutputFormat.BOTH; return this; }

        // ===== Audio preprocessing setters =====

        /**
         * Sets the volume boost in decibels.
         *
         * <p>Clamped to [0, 5] — 5× is the absolute maximum safe amplification.</p>
         *
         * @param v the volume boost
         * @return this builder
         */
        public Builder volumeBoost(float v) { this.volumeBoost = Math.min(Math.max(v, 0), 5.0f); return this; }

        /**
         * Sets the silence detection threshold in decibels.
         *
         * <p>Clamped to [-60, 0] dB.</p>
         *
         * @param v the silence threshold
         * @return this builder
         */
        public Builder silenceThreshold(float v) { this.silenceThreshold = Math.max(v, -60.0f); return this; }

        /**
         * Sets the silence duration in seconds.
         *
         * <p>Minimum 0.1 seconds.</p>
         *
         * @param v the silence duration
         * @return this builder
         */
        public Builder silenceDuration(float v) { this.silenceDuration = Math.max(v, 0.1f); return this; }

        /**
         * Sets whether noise reduction is enabled.
         *
         * @param v {@code true} to enable noise reduction
         * @return this builder
         */
        public Builder noiseReduction(boolean v) { this.noiseReduction = v; return this; }

        // ===== SRT formatting setters =====

        /**
         * Sets the maximum characters per SRT line.
         *
         * <p>Minimum 20 characters per line.</p>
         *
         * @param v the maximum characters
         * @return this builder
         */
        public Builder srtMaxChars(int v) { this.srtMaxChars = Math.max(v, 20); return this; }

        /**
         * Sets the maximum lines per SRT block.
         *
         * <p>Minimum 1 line.</p>
         *
         * @param v the maximum lines
         * @return this builder
         */
        public Builder srtMaxLines(int v) { this.srtMaxLines = Math.max(v, 1); return this; }

        // ===== Advanced features setters =====

        /**
         * Sets whether speaker diarisation is enabled.
         *
         * @param v {@code true} to enable diarisation
         * @return this builder
         */
        public Builder diarizeEnabled(boolean v) { this.diarizeEnabled = v; return this; }

        /**
         * Sets the HuggingFace token for diarisation.
         *
         * @param v the HF token
         * @return this builder
         */
        public Builder hfToken(String v) { this.hfToken = v; return this; }

        /**
         * Sets the maximum segment duration in seconds.
         *
         * <p>Values ≤ 0 are treated as "use default" (30 s). No upper cap is
         * enforced, but values above 120 s are unusual and may degrade performance.</p>
         *
         * @param v the segment duration in seconds
         * @return this builder
         */
        public Builder maxSegmentDuration(float v) { this.maxSegmentDuration = v > 0 ? v : 30.0f; return this; }

        // ===== Pipeline control setters =====

        /**
         * Sets whether segmentation should be skipped.
         *
         * @param v {@code true} to skip segmentation
         * @return this builder
         */
        public Builder skipSegmentation(boolean v) { this.skipSegmentation = v; return this; }

        /**
         * Sets whether transcription is enabled.
         *
         * @param v {@code true} to enable transcription
         * @return this builder
         */
        public Builder enabled(boolean v) { this.enabled = v; return this; }

        /**
         * Enables or disables streaming transcription for large files.
         *
         * <p>When enabled, files larger than 100MB will be split into chunks
         * and processed in parallel to reduce memory usage. Default is true.</p>
         *
         * @param v {@code true} to enable streaming
         * @return this builder
         */
        public Builder streamingEnabled(boolean v) { this.streamingEnabled = v; return this; }

        // ===== Translation Builder Methods =====

        /**
         * Enables or disables translation of transcripts.
         *
         * <p>When enabled, transcripts will be translated to the target language
         * after transcription completes. Requires a translation endpoint.</p>
         *
         * @param v {@code true} to enable translation
         * @return this builder
         */
        public Builder translationEnabled(boolean v) { 
            this.translationEnabled = v; 
            return this; 
        }

        /**
         * Sets the target language for translation.
         *
         * <p>This should be an ISO 639-1 language code (e.g., "es" for Spanish,
         * "fr" for French, "de" for German). Default is "es".</p>
         *
         * @param v the target language code
         * @return this builder
         */
        public Builder translationTargetLanguage(String v) {
            this.translationTargetLanguage = (v != null && !v.isBlank()) ? v : "es";
            return this;
        }

        /**
         * Sets the translation endpoint URL.
         *
         * <p>This should be a LibreTranslate-compatible REST API endpoint.
         * Default is {@code https://libretranslate.com/translate}.</p>
         *
         * @param v the endpoint URL
         * @return this builder
         */
        public Builder translationEndpoint(String v) {
            this.translationEndpoint = (v != null && !v.isBlank()) 
                ? v 
                : "https://libretranslate.com/translate";
            return this;
        }

        /**
         * Sets the API key for the translation service.
         *
         * <p>Some translation services require an API key. This can be null
         * for services that don't require authentication.</p>
         *
         * @param v the API key, or null if not needed
         * @return this builder
         */
        public Builder translationApiKey(String v) {
            this.translationApiKey = v;
            return this;
        }

        /**
         * Builds the TranscriptionConfig instance.
         *
         * @return a new TranscriptionConfig
         */
        public TranscriptionConfig build() { return new TranscriptionConfig(this); }
    }

    // -------------------------------------------------------------------------
    //  Enumerations
    // -------------------------------------------------------------------------

    /**
     * Supported transcription output formats.
     */
    public enum OutputFormat {
        SRT("srt"),
        TXT("txt"),
        BOTH("both"),
        JSON("json");

        private final String value;

        OutputFormat(String value) { this.value = value; }

        /**
         * Returns the string value of this format.
         *
         * @return the format string
         */
        public String getValue() { return value; }

        /**
         * Parses an OutputFormat from a string.
         *
         * @param value the string to parse
         * @return the OutputFormat, or BOTH if the string is not recognised
         */
        public static OutputFormat fromString(String value) {
            for (OutputFormat f : values()) {
                if (f.value.equalsIgnoreCase(value)) return f;
            }
            return BOTH;
        }
    }

    /**
     * Timestamp granularity modes (informational — used by UI only).
     */
    public enum TimestampMode {
        NONE("none", "No timestamps"),
        AUTO("auto", "Automatic segmentation"),
        WORD("word", "Word-level timestamps"),
        SENTENCE("sentence", "Sentence-level timestamps"),
        PARAGRAPH("paragraph", "Paragraph-level timestamps"),
        FIXED("fixed", "Fixed duration segments");

        private final String value;
        private final String description;

        TimestampMode(String value, String description) {
            this.value = value;
            this.description = description;
        }

        /**
         * Returns the string value of this mode.
         *
         * @return the mode string
         */
        public String getValue() { return value; }

        /**
         * Returns the description of this mode.
         *
         * @return the description
         */
        public String getDescription() { return description; }

        /**
         * Parses a TimestampMode from a string.
         *
         * @param value the string to parse
         * @return the TimestampMode, or AUTO if the string is not recognised
         */
        public static TimestampMode fromString(String value) {
            for (TimestampMode m : values()) {
                if (m.value.equalsIgnoreCase(value)) return m;
            }
            return AUTO;
        }
    }

    // -------------------------------------------------------------------------
    //  Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TranscriptionConfig{");
        sb.append("model='").append(model).append('\'');
        sb.append(", language='").append(language).append('\'');
        sb.append(", outputFormat=").append(outputFormat);
        sb.append(", diarize=").append(diarizeEnabled);
        sb.append(", srt=").append(srtMaxChars).append("ch/").append(srtMaxLines).append("ln");
        sb.append(", segmentDuration=").append(maxSegmentDuration).append("s");
        sb.append(", streaming=").append(streamingEnabled);
        // ===== Translation in toString =====
        sb.append(", translation=").append(translationEnabled);
        if (translationEnabled) {
            sb.append(", targetLang='").append(translationTargetLanguage).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}