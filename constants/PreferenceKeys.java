/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.constants;

/**
 * Preference keys
 */
public final class PreferenceKeys {
    private PreferenceKeys() {}
    
    // Directories
    public static final String OUTPUT_DIR = "output_directory";
    public static final String LAST_FILE_DIR = "last_file_directory";
    public static final String LAST_FILE_ADD_LOCATION = "last_file_add_location";
    public static final String LAST_AUDIO_SPLITTER_LOCATION = "last_audio_splitter_location";
    public static final String LAST_TEXT_COMBINER_LOCATION = "last_text_combiner_location";
    // FIX: MAX_PARALLEL_FILES and OUTPUT_DIRECTORY used to duplicate
    // MAX_PARALLEL and OUTPUT_DIR below under different names but the same
    // literal string value. Harmless today only because both names in each
    // pair happened to still map to the same string — but nothing enforced
    // that, and editing one constant's value without noticing its twin
    // would have silently split what call sites believed was one shared
    // setting into two. Callers now reference MAX_PARALLEL / OUTPUT_DIR
    // directly; see their definitions below.
    
    // Audio Settings
    public static final String MODEL = "model";
    public static final String LANGUAGE = "language";
    public static final String OUTPUT_FORMAT = "output_format";
    public static final String BITRATE = "bitrate";
    public static final String VOLUME_BOOST = "volume_boost";
    public static final String NOISE_REDUCTION = "noise_reduction";
    public static final String KEEP_PROCESSED = "keep_processed";
    public static final String NORMALIZE_AUDIO = "normalize_audio";
    public static final String REMOVE_SILENCE = "remove_silence";
    
    // Transcription Settings
    public static final String TRANSCRIPTION_ENABLED = "transcription_enabled";
    public static final String TIMESTAMPS_ENABLED = "timestamps_enabled";
    public static final String CONFIDENCE_ENABLED = "confidence_enabled";
    
    // Batch Settings
    public static final String MAX_PARALLEL = "max_parallel_files";
    public static final String AUTO_REMOVE_COMPLETED = "auto_remove_completed";
    
    // Window State
    public static final String STAGE_WIDTH = "stage_width";
    public static final String STAGE_HEIGHT = "stage_height";
    public static final String STAGE_X = "stage_x";
    public static final String STAGE_Y = "stage_y";
    public static final String FONT_SIZE = "font_size";
    
    // Time Estimation Data (NEW)
    public static final String TIME_ESTIMATION_DATA = "time_estimation_data";
    public static final String PROCESS_TIMES_PREFIX = "process_time_";
    public static final String SAMPLE_COUNT_PREFIX = "sample_count_";
    public static final String LAST_SAVED_TIMESTAMP = "last_saved_timestamp";

    // Audio Processing
    public static final String NOISE_REDUCTION_ENABLED = "noise_reduction_enabled";
    public static final String NORMALIZE_AUDIO_ENABLED = "normalize_audio_enabled";

    // Transcription
    public static final String WHISPER_MODEL = "whisper_model";

    // UI Settings
    public static final String THEME = "theme";

    // =============================================
    // EULA Settings (NEW)
    // =============================================
    
    /**
     * The version of the EULA that the user has accepted.
     * 0 means not accepted, 1+ means accepted at that version.
     */
    public static final String EULA_ACCEPTED_VERSION = "eula.accepted_version";

    // =============================================
    // ID3 Tagging Settings (NEW)
    // =============================================
    
    /**
     * Whether ID3 tagging is enabled.
     * When enabled, sidecar .meta files are created with metadata.
     */
    public static final String ID3_TAGGING_ENABLED = "id3_tagging_enabled";

    // =============================================
    // Code Signing Settings (NEW)
    // =============================================
    
    /**
     * Whether the current application instance has been verified as code-signed.
     * This is set after successful signature verification.
     */
    public static final String CODE_SIGNED = "code.signed";

    // =============================================
    // Audio Enhancement Settings (NEW)
    // =============================================

    /**
     * Whether automatic volume optimization is applied during processing.
     * Literal value preserved as-is from PreferenceManager's former local
     * constant so existing users' stored preferences keep resolving.
     */
    public static final String AUTO_VOLUME_OPTIMIZATION = "autoVolumeOptimization";

    /** Target volume level (dB) used by automatic volume optimization. */
    public static final String TARGET_VOLUME_DB = "targetVolumeDb";

    // =============================================
    // Translation Settings (NEW)
    // =============================================

    public static final String TRANSLATION_ENABLED = "translation.enabled";
    public static final String TRANSLATION_TARGET_LANGUAGE = "translation.target_language";
    public static final String TRANSLATION_ENDPOINT = "translation.endpoint";
    public static final String TRANSLATION_API_KEY = "translation.api_key";

    // =============================================
    // Batch Queue Settings (NEW)
    // =============================================

    public static final String BATCH_QUEUE_FILES = "batch_queue_files";
    public static final String BATCH_QUEUE_LAST_SAVED = "batch_queue_last_saved";

    // =============================================
    // Error Reporting Settings (NEW)
    // =============================================

    public static final String ERROR_REPORTING_ENABLED = "error.reporting.enabled";
    public static final String ERROR_REPORTING_LAST_SENT = "error.reporting.last_sent";

    // =============================================
    // Auto-Update Settings (NEW)
    // =============================================

    public static final String AUTO_UPDATE_ENABLED = "auto.update.enabled";
    public static final String UPDATE_SKIPPED_VERSION = "update.skipped.version";

    // =============================================
    // Dependency Check Settings (NEW)
    // =============================================

    public static final String LAST_DEPENDENCY_CHECK = "last.dependency.check";
    public static final String DEPENDENCIES_OK = "dependencies.ok";

    // =============================================
    // Tool-Specific Output Locations (NEW)
    // =============================================

    public static final String LAST_AUDIO_SPLITTER_OUTPUT_LOCATION = "last_audio_splitter_output_location";
    public static final String LAST_TEXT_COMBINER_OUTPUT_LOCATION = "last_text_combiner_output_location";
    public static final String LAST_SOUND_RECORDER_LOCATION = "last_sound_recorder_location";

    // =============================================
    // Session / Processing State (NEW)
    // =============================================

    public static final String LAST_PROCESSING_STATE = "last_processing_state";
    public static final String PROCESSING_STATE_TIMESTAMP = "processing_state_timestamp";
}