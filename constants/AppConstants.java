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
    //
    // FIX: MAX_FILE_SIZE was hardcoded to 1024L*1024*1024 (exactly 1024 MB /
    // 1 GB) with a comment claiming "// 750MB" right next to it, while
    // MAX_FILE_SIZE_MB (used in the user-facing "exceeds 750 MB limit"
    // message in FileSelectionPanel) was correctly 750. The enforced byte
    // limit was actually ~37% larger than what the UI told users the limit
    // was — a file between 750MB and 1024MB would be silently accepted
    // despite the displayed cap. Deriving MAX_FILE_SIZE from MAX_FILE_SIZE_MB
    // makes them structurally impossible to disagree, instead of two
    // independently-hardcoded numbers that already had drifted apart once.
    public static final int MAX_FILE_SIZE_MB = 750;
    public static final long MAX_FILE_SIZE = MAX_FILE_SIZE_MB * 1024L * 1024L;
    
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
    
    // Supported Formats
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
    
    // Time estimation
    public static final double TRANSCRIPTION_TIME_FACTOR = 0.1; // 10% of audio duration
    public static final int WHISPER_TIMEOUT_MINUTES = 60;

    // FIX (dead-code cleanup): removed four constants confirmed unused
    // anywhere in the codebase I have visibility into:
    //   - STAGE_CONVERSION_WEIGHT (0.1), STAGE_TRANSCRIPTION_WEIGHT (0.9),
    //     CONVERSION_TIME_FACTOR (1.5) — ParallelProcessingManager's actual
    //     pipeline uses different, independently hardcoded checkpoint values
    //     (0.3 / 0.65 split) and never reads these at all.
    //   - DEFAULT_MAX_SEGMENT_DURATION (12.0f) — TranscriptionConfig.Builder
    //     hardcodes its own default of 30.0f directly and never reads this
    //     constant either. Anyone reading this constant to understand actual
    //     segment-duration behavior would be misled — the real default is
    //     30s, not 12s.
    // If real behavior should actually match these constants' original
    // intent (e.g. segments really should default to 12s), that's a decision
    // to make explicitly in TranscriptionConfig, not by resurrecting a
    // constant nothing reads.
}