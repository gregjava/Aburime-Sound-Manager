/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.constants;

/**
 * Enhanced preference keys
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

}