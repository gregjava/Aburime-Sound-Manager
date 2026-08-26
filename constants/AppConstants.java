/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.constants;

import javafx.scene.input.KeyCombination;

import java.util.List;
import java.util.Map;

/**
 * Application-wide constants.
 * 
 * <p>Contains version information, supported formats, UI defaults,
 * and other global constants used throughout the application.</p>
 */
public final class AppConstants {

    // ========================================================================
    //  Version
    // ========================================================================

    /** Application version (semantic versioning). */
    public static final String APP_VERSION = "4.0.0";
    
    /** Application version major number. */
    public static final int APP_VERSION_MAJOR = 4;
    
    /** Application version minor number. */
    public static final int APP_VERSION_MINOR = 0;
    
    /** Application version patch number. */
    public static final int APP_VERSION_PATCH = 0;
    
    /** Application title. */
    public static final String APP_TITLE = "Aburime Sound Manager";

    /** Application release name. */
    public static final String APP_RELEASE_NAME = "Phoenix";

    /** Full application name with version and release. */
    public static final String APP_FULL_NAME = APP_TITLE + " v" + APP_VERSION + " — " + APP_RELEASE_NAME;

    // ========================================================================
    //  Audio Formats - Supported Input
    // ========================================================================

    /**
     * All supported audio file extensions (for file chooser filters).
     * Format: "*.extension" for FileChooser.ExtensionFilter.
     */
    public static final String[] AUDIO_EXTENSIONS = {
        // Common formats
        "*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a", "*.wma", 
        "*.aac", "*.opus", "*.alac", "*.aiff", "*.amr", "*.ac3",
        // Professional/Broadcast
        "*.aif", "*.snd", "*.bwf", "*.caf", "*.mxf",
        // Lossless/Audiophile
        "*.ape", "*.wv", "*.tta",
        // Container formats
        "*.mka",
        // Game/Console
        "*.adx", "*.brstm",
        // Streaming/Other
        "*.aa", "*.ra"
    };

    /**
     * List of audio file extensions without the "*." prefix.
     */
    public static final List<String> AUDIO_EXTENSION_LIST = List.of(
        "mp3", "wav", "flac", "ogg", "m4a", "wma",
        "aac", "opus", "alac", "aiff", "amr", "ac3",
        "aif", "snd", "bwf", "caf", "mxf",
        "ape", "wv", "tta",
        "mka",
        "adx", "brstm",
        "aa", "ra"
    );

    // ========================================================================
    //  Audio Formats - Format Names
    // ========================================================================

    /**
     * Mapping from file extension to human-readable format name.
     */
    public static final Map<String, String> FORMAT_NAMES = Map.ofEntries(
        // Common formats
        Map.entry("mp3", "MP3"),
        Map.entry("wav", "WAV"),
        Map.entry("flac", "FLAC"),
        Map.entry("ogg", "OGG"),
        Map.entry("m4a", "M4A"),
        Map.entry("wma", "WMA"),
        Map.entry("aac", "AAC"),
        Map.entry("opus", "OPUS"),
        Map.entry("alac", "ALAC"),
        Map.entry("aiff", "AIFF"),
        Map.entry("amr", "AMR"),
        Map.entry("ac3", "AC3"),
        // Professional/Broadcast
        Map.entry("aif", "AIFF"),
        Map.entry("snd", "AIFF"),
        Map.entry("bwf", "Broadcast WAV"),
        Map.entry("caf", "Core Audio Format"),
        Map.entry("mxf", "Material Exchange Format"),
        // Lossless/Audiophile
        Map.entry("ape", "Monkey's Audio"),
        Map.entry("wv", "WavPack"),
        Map.entry("tta", "True Audio"),
        // Container formats
        Map.entry("mka", "Matroska Audio"),
        // Game/Console
        Map.entry("adx", "CRI ADX"),
        Map.entry("brstm", "Nintendo BRSTM"),
        // Streaming/Other
        Map.entry("aa", "Audible Audio"),
        Map.entry("ra", "RealAudio")
    );

    // ========================================================================
    //  Audio Formats - Output
    // ========================================================================

    /**
     * Supported output formats for audio conversion.
     */
    public static final String[] OUTPUT_FORMATS = {
        "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "alac"
    };

    /**
     * Supported bitrates for audio conversion.
     */
    public static final String[] BITRATES = {
        "32k", "64k", "96k", "128k", "192k", "256k", "320k"
    };

    /**
     * Default output format if none specified.
     */
    public static final String DEFAULT_FORMAT = "mp3";

    /**
     * Default bitrate if none specified.
     */
    public static final String DEFAULT_BITRATE = "192k";

    /**
     * FFmpeg codec mapping for output formats.
     */
    public static final Map<String, String> FFMPEG_CODECS = Map.ofEntries(
        Map.entry("mp3", "libmp3lame"),
        Map.entry("wav", "pcm_s16le"),
        Map.entry("flac", "flac"),
        Map.entry("ogg", "libvorbis"),
        Map.entry("m4a", "aac"),
        Map.entry("aac", "aac"),
        Map.entry("opus", "libopus"),
        Map.entry("alac", "alac")
    );

    // ========================================================================
    //  MIME Types
    // ========================================================================

    /**
     * MIME type mapping for supported audio formats.
     */
    public static final Map<String, String> MIME_TYPES = Map.ofEntries(
        Map.entry("mp3", "audio/mpeg"),
        Map.entry("wav", "audio/wav"),
        Map.entry("flac", "audio/flac"),
        Map.entry("ogg", "audio/ogg"),
        Map.entry("m4a", "audio/mp4"),
        Map.entry("wma", "audio/x-ms-wma"),
        Map.entry("aac", "audio/aac"),
        Map.entry("opus", "audio/opus"),
        Map.entry("alac", "audio/alac"),
        Map.entry("aiff", "audio/aiff"),
        Map.entry("aif", "audio/aiff"),
        Map.entry("snd", "audio/aiff"),
        Map.entry("ape", "audio/ape"),
        Map.entry("mka", "audio/x-matroska"),
        Map.entry("bwf", "audio/wav"),
        Map.entry("caf", "audio/x-caf"),
        Map.entry("mxf", "audio/mxf")
    );

    // ========================================================================
    //  Model Settings
    // ========================================================================

    /** Available Whisper models. */
    public static final String[] MODELS = {
        "tiny", "base", "small", "medium", "large", "large-v2", "large-v3"
    };

    /** Default model if none specified. */
    public static final String DEFAULT_MODEL = "base";

    /** Available languages (ISO 639-1 codes). */
    public static final String[] LANGUAGES = {
        "auto", "en", "es", "de", "fr", "it", "zh", "ja", "ru", "ko",
        "pt", "tr", "pl", "ca", "nl", "ar", "sv", "id", "hi", "fi",
        "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta"
    };

    /** Default language if none specified. */
    public static final String DEFAULT_LANGUAGE = "auto";

    // ========================================================================
    //  Whisper/Audio Processing Constants
    // ========================================================================

    /** Sample rate for Whisper transcription (16 kHz). */
    public static final int WHISPER_SAMPLE_RATE = 16000;

    /** Number of audio channels for Whisper (mono). */
    public static final int WHISPER_CHANNELS = 1;

    /** Default segment duration for Whisper (30 seconds). */
    public static final int WHISPER_SEGMENT_DURATION = 30;

    // ========================================================================
    //  FFmpeg Timeout
    // ========================================================================

    /** Maximum hours to wait for FFmpeg processing before timeout. */
    public static final int FFMPEG_TIMEOUT_HOURS = 4;

    // ========================================================================
    //  Dependency Check Constants
    // ========================================================================

    /** Maximum number of retry attempts for dependency checks. */
    public static final int MAX_DEPENDENCY_RETRIES = 3;

    /** Command timeout in seconds for dependency checks. */
    public static final int COMMAND_TIMEOUT_SECONDS = 30;

    /** Delay between retry attempts in milliseconds. */
    public static final int RETRY_DELAY_MS = 2000;

    // ========================================================================
    //  Audio Processing Defaults
    // ========================================================================

    /** Minimum volume boost (0x). */
    public static final double MIN_VOLUME_BOOST = 0.0;

    /** Maximum volume boost (5x). */
    public static final double MAX_VOLUME_BOOST = 5.0;

    /** Default volume boost. */
    public static final double DEFAULT_VOLUME = 1.0;

    /** Volume boost tick interval for slider. */
    public static final double VOLUME_BOOST_TICK = 1.0;

    /** Default silence threshold (dB). */
    public static final double SILENCE_THRESHOLD = -40.0;

    /** Default silence duration (seconds). */
    public static final double SILENCE_DURATION = 1.0;

    /** Minimum silence duration (seconds). */
    public static final double MIN_SILENCE_DURATION = 0.1;

    /** Maximum silence duration (seconds). */
    public static final double MAX_SILENCE_DURATION = 3.0;

    /** Minimum silence threshold (dB). */
    public static final double MIN_SILENCE_THRESHOLD = -60.0;

    /** Maximum silence threshold (dB). */
    public static final double MAX_SILENCE_THRESHOLD = -30.0;

    // ========================================================================
    //  UI Defaults
    // ========================================================================

    /** Default font size (points). */
    public static final double DEFAULT_FONT_SIZE = 12.0;

    /** Minimum font size (points). */
    public static final double MIN_FONT_SIZE = 8.0;

    /** Maximum font size (points). */
    public static final double MAX_FONT_SIZE = 20.0;

    /** Default window width. */
    public static final double DEFAULT_WINDOW_WIDTH = 1100;

    /** Default window height. */
    public static final double DEFAULT_WINDOW_HEIGHT = 800;

    /** Minimum window width. */
    public static final double MIN_WINDOW_WIDTH = 800;

    /** Minimum window height. */
    public static final double MIN_WINDOW_HEIGHT = 600;

    /** CSS path for light theme. */
    public static final String CSS_PATH = "/audiomanager/styles.css";

    /** CSS path for dark theme. */
    public static final String DARK_CSS_PATH = "/audiomanager/styles/dark.css";

    /** Application icon path. */
    public static final String ICON_PATH = "/audiomanager/sound_manager_logo.jpg";

    /** Path to the bundled transcription script. */
    public static final String TRANSCRIPTION_SCRIPT_PATH = "/scripts/transcribe.py";

    /** Path to the user manual. */
    public static final String USER_MANUAL_PATH = "/docs/USER_MANUAL.md";

    /** Path to the troubleshooting guide. */
    public static final String TROUBLESHOOTING_PATH = "/docs/TROUBLESHOOTING.md";

    // ========================================================================
    //  File Size Limits
    // ========================================================================

    /** Maximum file size for free version (100 MB). */
    public static final long MAX_FILE_SIZE = 100 * 1024 * 1024L;

    /** Maximum file size for pro version (750 MB). */
    public static final long MAX_FILE_SIZE_PRO = 750 * 1024 * 1024L;

    /** Maximum file size for waveform preview (200 MB). */
    public static final long MAX_WAVEFORM_SIZE = 200 * 1024 * 1024L;

    /** Maximum file size in MB (for display). */
    public static final int MAX_FILE_SIZE_MB = 100;

    /** Maximum file size in MB for pro version (for display). */
    public static final int MAX_FILE_SIZE_PRO_MB = 750;

    // ========================================================================
    //  Batch State & Persistence
    // ========================================================================

    /** Batch state file name. */
    public static final String BATCH_STATE_FILE = "batch_state.json";

    /** Time estimation data file name. */
    public static final String TIME_ESTIMATES_FILE = "time_estimates.json";

    // ========================================================================
    //  Keyboard Shortcuts
    // ========================================================================

    /** Toggle dark mode: Ctrl+Shift+D. */
    public static final KeyCombination KEY_TOGGLE_DARK_MODE =
        KeyCombination.keyCombination("Shortcut+Shift+D");

    /** Check dependencies: F5. */
    public static final KeyCombination KEY_CHECK_DEPENDENCIES =
        KeyCombination.keyCombination("F5");

    /** Toggle folder watch: Ctrl+Shift+W. */
    public static final KeyCombination KEY_TOGGLE_FOLDER_WATCH =
        KeyCombination.keyCombination("Shortcut+Shift+W");

    /** Performance report: Ctrl+Shift+P. */
    public static final KeyCombination KEY_PERFORMANCE_REPORT =
        KeyCombination.keyCombination("Shortcut+Shift+P");

    /** ===== NEW: Run Setup Wizard: Ctrl+Shift+S ===== */
    public static final KeyCombination KEY_SETUP_WIZARD =
        KeyCombination.keyCombination("Shortcut+Shift+S");

    /** Batch settings: Ctrl+B. */
    public static final KeyCombination KEY_BATCH_SETTINGS =
        KeyCombination.keyCombination("Shortcut+B");

    /** Exit application: Ctrl+Q. */
    public static final KeyCombination KEY_EXIT =
        KeyCombination.keyCombination("Shortcut+Q");

    /** Preferences: Ctrl+Comma. */
    public static final KeyCombination KEY_PREFERENCES =
        KeyCombination.keyCombination("Shortcut+Comma");

    /** Clear session data: Ctrl+Shift+C. */
    public static final KeyCombination KEY_CLEAR_SESSION =
        KeyCombination.keyCombination("Shortcut+Shift+C");

    /** Troubleshooting guide: F1. */
    public static final KeyCombination KEY_TROUBLESHOOTING =
        KeyCombination.keyCombination("F1");

    /** Browse for files: Ctrl+O. */
    public static final KeyCombination KEY_BROWSE_FILES =
        KeyCombination.keyCombination("Shortcut+O");

    /** Undo: Ctrl+Z. */
    public static final KeyCombination KEY_UNDO =
        KeyCombination.keyCombination("Shortcut+Z");

    /** Redo: Ctrl+Y or Ctrl+Shift+Z. */
    public static final KeyCombination KEY_REDO =
        KeyCombination.keyCombination("Shortcut+Y");

    /** Redo alternative: Ctrl+Shift+Z. */
    public static final KeyCombination KEY_REDO_ALT =
        KeyCombination.keyCombination("Shortcut+Shift+Z");

    // ========================================================================
    //  Processing Constants
    // ========================================================================

    /** Default max parallel files. */
    public static final int DEFAULT_MAX_PARALLEL = 4;

    /** Minimum max parallel files. */
    public static final int MIN_MAX_PARALLEL = 1;

    /** Maximum max parallel files. */
    public static final int MAX_MAX_PARALLEL = 16;

    /** Large file threshold for streaming (100 MB). */
    public static final long LARGE_FILE_THRESHOLD = 100 * 1024 * 1024L;

    /** Default chunk duration for streaming (seconds). */
    public static final int DEFAULT_CHUNK_DURATION = 30;

    /** Maximum chunks per file. */
    public static final int MAX_CHUNKS_PER_FILE = 50;

    /** Processing timeout (hours). */
    public static final int PROCESSING_TIMEOUT_HOURS = 24;

    /** Model pool capacity. */
    public static final int MODEL_POOL_CAPACITY = 4;

    /** Queue size for thread pool. */
    public static final int THREAD_POOL_QUEUE_SIZE = 100;

    // ========================================================================
    //  REST API
    // ========================================================================

    /** Default REST API port. */
    public static final int DEFAULT_API_PORT = 8756;

    /** Minimum REST API port. */
    public static final int MIN_API_PORT = 1024;

    /** Maximum REST API port. */
    public static final int MAX_API_PORT = 65535;

    // ========================================================================
    //  Logging
    // ========================================================================

    /** Maximum log area characters. */
    public static final int MAX_LOG_AREA_CHARS = 500_000;

    /** Default log level. */
    public static final String DEFAULT_LOG_LEVEL = "INFO";

    // ========================================================================
    //  Utility Methods
    // ========================================================================

    /**
     * Check if a file extension is supported.
     *
     * @param extension The file extension (without the dot)
     * @return true if the extension is supported
     */
    public static boolean isSupportedExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        String ext = extension.toLowerCase();
        return AUDIO_EXTENSION_LIST.contains(ext);
    }

    /**
     * Get the format name for a file extension.
     *
     * @param extension The file extension (without the dot)
     * @return The format name, or null if not found
     */
    public static String getFormatName(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        return FORMAT_NAMES.get(extension.toLowerCase());
    }

    /**
     * Get the MIME type for a file extension.
     *
     * @param extension The file extension (without the dot)
     * @return The MIME type, or "application/octet-stream" if not found
     */
    public static String getMimeType(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "application/octet-stream";
        }
        return MIME_TYPES.getOrDefault(extension.toLowerCase(), "application/octet-stream");
    }

    /**
     * Get the FFmpeg codec for an output format.
     *
     * @param format The output format
     * @return The FFmpeg codec name, or "libmp3lame" as fallback
     */
    public static String getFFmpegCodec(String format) {
        if (format == null || format.isEmpty()) {
            return "libmp3lame";
        }
        return FFMPEG_CODECS.getOrDefault(format.toLowerCase(), "libmp3lame");
    }

    /**
     * Check if a format is valid for output.
     *
     * @param format The format to check
     * @return true if the format is valid for output
     */
    public static boolean isValidOutputFormat(String format) {
        if (format == null || format.isEmpty()) {
            return false;
        }
        for (String f : OUTPUT_FORMATS) {
            if (f.equalsIgnoreCase(format)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the maximum file size for a license type.
     *
     * @param isPro true if pro license, false for free
     * @return Maximum file size in bytes
     */
    public static long getMaxFileSize(boolean isPro) {
        return isPro ? MAX_FILE_SIZE_PRO : MAX_FILE_SIZE;
    }

    /**
     * Get the maximum file size in MB for a license type.
     *
     * @param isPro true if pro license, false for free
     * @return Maximum file size in MB
     */
    public static int getMaxFileSizeMB(boolean isPro) {
        return isPro ? MAX_FILE_SIZE_PRO_MB : MAX_FILE_SIZE_MB;
    }

    // ========================================================================
    //  Private Constructor (Utility Class)
    // ========================================================================

    private AppConstants() {
        // Prevent instantiation
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}