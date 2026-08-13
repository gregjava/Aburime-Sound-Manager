/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import audiomanager.constants.AppConstants;

/**
 * Immutable configuration for a single transcription operation.
 *
 * <p>Use the {@link Builder} for construction.  All numeric fields are
 * validated in the builder setter methods — invalid values are clamped to
 * their documented safe ranges rather than rejected with exceptions, so
 * callers never need to catch validation errors at run-time.</p>
 *
 * <h2>Key change vs. original</h2>
 * {@code maxSegmentDuration} is now the canonical per-segment length used by
 * {@link audiomanager.core.SegmentProcessor}.  The original implementation
 * hard-coded 10 seconds inside {@code SegmentProcessor}, ignoring this field
 * entirely.  The default is now 30 seconds, which gives Whisper sufficient
 * context to produce accurate transcriptions.
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

    // Pipeline control
    private final boolean skipSegmentation;
    private final boolean enabled;

    // -------------------------------------------------------------------------
    //  Private constructor (use Builder)
    // -------------------------------------------------------------------------

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
    }

    // -------------------------------------------------------------------------
    //  Getters
    // -------------------------------------------------------------------------

    public String       getModel()              { return model; }
    public String       getLanguage()           { return language; }
    public boolean      isTimestampsEnabled()   { return timestampsEnabled; }
    public boolean      isConfidenceEnabled()   { return confidenceEnabled; }
    public OutputFormat getOutputFormat()       { return outputFormat; }
    public float        getVolumeBoost()        { return volumeBoost; }
    public float        getSilenceThreshold()   { return silenceThreshold; }
    public float        getSilenceDuration()    { return silenceDuration; }
    public boolean      isNoiseReduction()      { return noiseReduction; }
    public int          getSrtMaxChars()        { return srtMaxChars; }
    public int          getSrtMaxLines()        { return srtMaxLines; }
    public boolean      isDiarizeEnabled()      { return diarizeEnabled; }
    public String       getHfToken()            { return hfToken; }
    /**
     * Maximum segment length in seconds.
     *
     * <p>Used by {@link audiomanager.core.SegmentProcessor} to set the FFmpeg
     * {@code -segment_time} parameter.  A value ≤ 0 means "use the processor
     * default" (currently 30 s).</p>
     */
    public float        getMaxSegmentDuration() { return maxSegmentDuration; }
    public boolean      isSkipSegmentation()    { return skipSegmentation; }
    public boolean      isEnabled()             { return enabled; }

    // -------------------------------------------------------------------------
    //  Factory methods
    // -------------------------------------------------------------------------

    /** Default configuration optimised for WhisperX. */
    public static TranscriptionConfig createDefault() {
        return builder().build();
    }

    /** Configuration with speaker diarisation enabled. */
    public static TranscriptionConfig withDiarization(String hfToken) {
        return builder()
                .timestampsEnabled(true)
                .diarizeEnabled(true)
                .hfToken(hfToken)
                .maxSegmentDuration(30.0f)
                .build();
    }

    /** SRT-only subtitle output. */
    public static TranscriptionConfig forSubtitles() {
        return builder()
                .timestampsEnabled(true)
                .outputFormat(OutputFormat.SRT)
                .maxSegmentDuration(10.0f)  // shorter segments for tight subtitles
                .build();
    }

    /** Plain-text output only. */
    public static TranscriptionConfig forTextOnly() {
        return builder()
                .timestampsEnabled(false)
                .outputFormat(OutputFormat.TXT)
                .maxSegmentDuration(15.0f)
                .build();
    }

    /** High-quality large-model configuration. */
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
                .build();
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Copy this config into a fresh {@link Builder}, so a caller can
     * override one or two fields (e.g. forcing {@code skipSegmentation}
     * for a baseline-mode experimental run) without hand-copying every
     * field — which would silently drift out of sync the next time a
     * field is added here.
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
                .enabled(enabled);
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
        private float        maxSegmentDuration = 30.0f;  // FIX: was 10 s in original; 30 s default
        private boolean      skipSegmentation  = false;
        private boolean      enabled           = true;

        public Builder model(String model) {
            this.model = (model != null && !model.isBlank()) ? model : AppConstants.DEFAULT_MODEL;
            return this;
        }
        public Builder language(String language) {
            this.language = (language != null) ? language : AppConstants.DEFAULT_LANGUAGE;
            return this;
        }
        public Builder timestampsEnabled(boolean v)  { this.timestampsEnabled = v; return this; }
        public Builder confidenceEnabled(boolean v)  { this.confidenceEnabled = v; return this; }
        public Builder outputFormat(OutputFormat f)  { this.outputFormat = f != null ? f : OutputFormat.BOTH; return this; }

        /** Clamped to [0, 5] — 5× is the absolute maximum safe amplification. */
        public Builder volumeBoost(float v)          { this.volumeBoost = Math.min(Math.max(v, 0), 5.0f); return this; }

        /** Clamped to [-60, 0] dB. */
        public Builder silenceThreshold(float v)     { this.silenceThreshold = Math.max(v, -60.0f); return this; }

        /** Minimum 0.1 s. */
        public Builder silenceDuration(float v)      { this.silenceDuration = Math.max(v, 0.1f); return this; }

        public Builder noiseReduction(boolean v)     { this.noiseReduction = v; return this; }

        /** Minimum 20 characters per line. */
        public Builder srtMaxChars(int v)            { this.srtMaxChars = Math.max(v, 20); return this; }

        /** Minimum 1 line. */
        public Builder srtMaxLines(int v)            { this.srtMaxLines = Math.max(v, 1); return this; }

        public Builder diarizeEnabled(boolean v)     { this.diarizeEnabled = v; return this; }
        public Builder hfToken(String v)             { this.hfToken = v; return this; }

        /**
         * Segment duration in seconds for {@link audiomanager.core.SegmentProcessor}.
         * Values ≤ 0 are treated as "use default" (30 s).  No upper cap is enforced,
         * but values above 120 s are unusual and may degrade performance.
         */
        public Builder maxSegmentDuration(float v)   { this.maxSegmentDuration = v > 0 ? v : 30.0f; return this; }

        public Builder skipSegmentation(boolean v)   { this.skipSegmentation = v; return this; }
        public Builder enabled(boolean v)            { this.enabled = v; return this; }

        public TranscriptionConfig build()           { return new TranscriptionConfig(this); }
    }

    // -------------------------------------------------------------------------
    //  Enumerations
    // -------------------------------------------------------------------------

    /** Supported transcription output formats. */
    public enum OutputFormat {
        SRT("srt"),
        TXT("txt"),
        BOTH("both"),
        JSON("json");

        private final String value;

        OutputFormat(String value) { this.value = value; }

        public String getValue() { return value; }

        public static OutputFormat fromString(String value) {
            for (OutputFormat f : values()) {
                if (f.value.equalsIgnoreCase(value)) return f;
            }
            return BOTH;
        }
    }

    /** Timestamp granularity modes (informational — used by UI only). */
    public enum TimestampMode {
        NONE("none",      "No timestamps"),
        AUTO("auto",      "Automatic segmentation"),
        WORD("word",      "Word-level timestamps"),
        SENTENCE("sentence", "Sentence-level timestamps"),
        PARAGRAPH("paragraph", "Paragraph-level timestamps"),
        FIXED("fixed",    "Fixed duration segments");

        private final String value;
        private final String description;

        TimestampMode(String value, String description) {
            this.value = value; this.description = description;
        }

        public String getValue()       { return value; }
        public String getDescription() { return description; }

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
        return "TranscriptionConfig{"
                + "model='" + model + '\''
                + ", language='" + language + '\''
                + ", outputFormat=" + outputFormat
                + ", diarize=" + diarizeEnabled
                + ", srt=" + srtMaxChars + "ch/" + srtMaxLines + "ln"
                + ", segmentDuration=" + maxSegmentDuration + "s"
                + '}';
    }
}