/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.constants;

public final class AppConstants {
    private AppConstants() {} // Prevent instantiation
    
    // Application Info
    public static final String APP_TITLE = "Aburime Sound Manager v3.9";
    public static final String APP_SUBTITLE = "Convert, Clean & Split Audio";
    public static final String VERSION = "0.3.9";
    
    // File Constraints
    public static final long MAX_FILE_SIZE = 1024L * 1024 * 1024; // 750MB
    public static final int MAX_FILE_SIZE_MB = 750;
    
    // Processing Weights
    public static final double STAGE_CONVERSION_WEIGHT = 0.1;
    public static final double STAGE_TRANSCRIPTION_WEIGHT = 0.9;
    public static final double CONVERSION_TIME_FACTOR = 1.5;
    
    // Default Values
    public static final String DEFAULT_FORMAT = "mp3";
    public static final String DEFAULT_BITRATE = "128k";
    public static final double DEFAULT_VOLUME = 0.0;
    public static final double DEFAULT_FONT_SIZE = 12.0;
    
    // Timeouts
    public static final int COMMAND_TIMEOUT_SECONDS = 60;
    public static final int FFMPEG_TIMEOUT_HOURS = 12;
    
    // UI Constraints
    public static final int MIN_WINDOW_WIDTH = 600;
    public static final int MIN_WINDOW_HEIGHT = 600;
    public static final int DEFAULT_WINDOW_WIDTH = 800;
    public static final int DEFAULT_WINDOW_HEIGHT = 800;
    
    // Audio Processing
    public static final int WHISPER_SAMPLE_RATE = 16000;
    public static final int WHISPER_CHANNELS = 1;
    public static final double SILENCE_THRESHOLD = -50.0;
    public static final double SILENCE_DURATION = 1.5;
    public static final double MAX_DEPENDENCY_RETRIES = 2;
    public static final long RETRY_DELAY_MS = 2000;
    
    // Supported Formats (Add these arrays)
    public static final String[] AUDIO_EXTENSIONS = {
        "*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a", "*.wma", 
        "*.aac", "*.opus", "*.alac", "*.aiff", "*.amr", "*.ac3"
    };
    
    public static final String[] OUTPUT_FORMATS = {
        "mp3", "wav", "flac", "ogg"
    };
    
    public static final String[] BITRATES = {
        "64k", "128k", "192k", "256k", "320k"
    };
    
    public static final String[] WHISPER_MODELS = {
        "tiny", "base", "small", "medium", "large"
    };
    
    public static final String[] LANGUAGES = {
        "auto", "en", "es", "de", "fr", "it", "zh", "ja", "ru", "ko",
        "pt", "tr", "pl", "ca", "nl", "ar", "sv", "id", "hi", "fi",
        "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta"
    };
    
    // Resource Paths
    public static final String ICON_PATH = "/audiomanager/sound_manager_logo.jpg";
    public static final String CSS_PATH = "/audiomanager/styles.css";
    
    // Volume Boost Range
    public static final double MIN_VOLUME_BOOST = 0.0;
    public static final double MAX_VOLUME_BOOST = 12.0;
    public static final double VOLUME_BOOST_TICK = 4.0;
    
    // Font Size Range
    public static final double MIN_FONT_SIZE = 8.0;
    public static final double MAX_FONT_SIZE = 20.0;
    public static final double FONT_SIZE_TICK = 2.0;
    
    // Split Parts Range
    public static final int MIN_SPLIT_PARTS = 2;
    public static final int MAX_SPLIT_PARTS = 10;
    
    // Transcription defaults for WhisperX
    public static final String DEFAULT_MODEL = "base";
    public static final String DEFAULT_LANGUAGE = "en";
    
    // New defaults for WhisperX
    public static final float DEFAULT_VOLUME_BOOST = 1.5f;
    public static final float DEFAULT_SILENCE_THRESHOLD = -40.0f;
    public static final float DEFAULT_SILENCE_DURATION = 1.0f;
    public static final boolean DEFAULT_NOISE_REDUCTION = true;
    public static final int DEFAULT_SRT_MAX_CHARS = 42;
    public static final int DEFAULT_SRT_MAX_LINES = 2;
    public static final float DEFAULT_MAX_SEGMENT_DURATION = 12.0f;
    
    // Time estimation
    public static final double TRANSCRIPTION_TIME_FACTOR = 0.1; // 10% of audio duration
    public static final int WHISPER_TIMEOUT_MINUTES = 60;
}