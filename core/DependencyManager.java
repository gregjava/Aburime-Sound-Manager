/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.constants.AppConstants;
import audiomanager.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manages checking and validating external dependencies for the application.
 *
 * <p>This class handles resolution of external executables required for
 * audio processing and transcription, including:
 * <ul>
 *   <li><b>FFmpeg:</b> Required for audio conversion and processing</li>
 *   <li><b>FFprobe:</b> Required for audio duration probing</li>
 *   <li><b>WhisperX:</b> Required for transcription</li>
 *   <li><b>Alignment Model:</b> Required for precise timestamp alignment</li>
 * </ul>
 *
 * <p>Executable resolution follows this priority order:
 * <ol>
 *   <li><b>Bundled runtime:</b> {@code runtime/ffmpeg/ffmpeg.exe} (or equivalent on macOS/Linux)</li>
 *   <li><b>Fixed install location:</b> {@code C:\AI\ffmpeg\bin\} (Windows only)</li>
 *   <li><b>System PATH:</b> Uses the bare command name (e.g., "ffmpeg")</li>
 * </ol>
 *
 * <p><b>Thread-safety:</b> Resolved paths are cached in volatile fields,
 * making this class safe for concurrent use.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see DependencyStatus
 * @see ProcessRunner
 */
public class DependencyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyManager.class);

    // -------------------------------------------------------------------------
    //  FFmpeg / FFprobe executable resolution
    // -------------------------------------------------------------------------

    /**
     * Relative-to-app-directory location of a bundled FFmpeg build.
     */
    private static final String BUNDLED_RUNTIME_SUBDIR = "runtime" + File.separator + "ffmpeg";

    /**
     * Fixed, well-known install location used by the AudioManager installer/docs.
     */
    private static final String FIXED_INSTALL_DIR = "C:\\AI\\ffmpeg\\bin";

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private volatile String ffmpegPath;
    private volatile String ffprobePath;

    // -------------------------------------------------------------------------
    //  Dependency Checks
    // -------------------------------------------------------------------------

    /**
     * Checks if FFmpeg is available with persistent retry logic.
     *
     * <p>This method attempts to locate and verify FFmpeg with up to
     * {@link AppConstants#MAX_DEPENDENCY_RETRIES} retries, waiting
     * {@link AppConstants#RETRY_DELAY_MS} between attempts.</p>
     *
     * @return a {@link DependencyStatus} object containing the check result
     */
    public DependencyStatus checkFFmpeg() {
        LOGGER.info("Checking FFmpeg availability with persistent retries...");

        String ffmpegExecutable = getFFmpegPath();

        for (int attempt = 1; attempt <= AppConstants.MAX_DEPENDENCY_RETRIES; attempt++) {
            LOGGER.debug("FFmpeg check attempt {}/{}", attempt, AppConstants.MAX_DEPENDENCY_RETRIES);
            
            boolean available = ProcessRunner.isCommandAvailable(
                quoteIfNeeded(ffmpegExecutable) + " -version",
                AppConstants.COMMAND_TIMEOUT_SECONDS, 
                TimeUnit.SECONDS
            );

            if (available) {
                LOGGER.info("FFmpeg found and operational (attempt {}): {}", attempt, ffmpegExecutable);
                return new DependencyStatus(
                    "FFmpeg", 
                    true, 
                    "FFmpeg found and ready for audio processing (" + ffmpegExecutable + ")",
                    null
                );
            }

            if (attempt < AppConstants.MAX_DEPENDENCY_RETRIES) {
                try {
                    LOGGER.debug("Waiting {}ms before FFmpeg retry...", AppConstants.RETRY_DELAY_MS);
                    Thread.sleep(AppConstants.RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOGGER.warn("FFmpeg not found after {} attempts (last tried: {})",
                AppConstants.MAX_DEPENDENCY_RETRIES, ffmpegExecutable);
        return new DependencyStatus(
            "FFmpeg", 
            false, 
            "FFmpeg NOT FOUND (Required)",
            "FFmpeg was not found bundled (runtime\\ffmpeg\\), at " + FIXED_INSTALL_DIR +
            ", or on your system PATH.\n" +
            "Please install FFmpeg to one of those locations.\n" +
            "Download from: https://ffmpeg.org/download.html\n" +
            "Windows: place it under runtime\\ffmpeg\\, or " + FIXED_INSTALL_DIR +
            ", or add it to PATH in System Environment Variables\n" +
            "Linux: sudo apt install ffmpeg\n" +
            "Mac: brew install ffmpeg"
        );
    }

    /**
     * Verifies FFprobe availability separately from FFmpeg.
     *
     * <p>FFmpeg and FFprobe are separate executables shipped side by side.
     * A partial/custom FFmpeg install can have one without the other,
     * so this check provides a clear installation hint when FFprobe is missing.</p>
     *
     * @return a {@link DependencyStatus} object containing the check result
     */
    public DependencyStatus checkFFprobe() {
        LOGGER.info("Checking FFprobe availability with persistent retries...");

        String ffprobeExecutable = getFFprobePath();

        for (int attempt = 1; attempt <= AppConstants.MAX_DEPENDENCY_RETRIES; attempt++) {
            LOGGER.debug("FFprobe check attempt {}/{}", attempt, AppConstants.MAX_DEPENDENCY_RETRIES);

            boolean available = ProcessRunner.isCommandAvailable(
                quoteIfNeeded(ffprobeExecutable) + " -version",
                AppConstants.COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );

            if (available) {
                LOGGER.info("FFprobe found and operational (attempt {}): {}", attempt, ffprobeExecutable);
                return new DependencyStatus(
                    "FFprobe",
                    true,
                    "FFprobe found and ready for audio duration probing (" + ffprobeExecutable + ")",
                    null
                );
            }

            if (attempt < AppConstants.MAX_DEPENDENCY_RETRIES) {
                try {
                    Thread.sleep(AppConstants.RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOGGER.warn("FFprobe not found after {} attempts (last tried: {})",
                AppConstants.MAX_DEPENDENCY_RETRIES, ffprobeExecutable);
        return new DependencyStatus(
            "FFprobe",
            false,
            "FFprobe NOT FOUND (Required)",
            "FFprobe was not found bundled (runtime\\ffmpeg\\), at " + FIXED_INSTALL_DIR +
            ", or on your system PATH.\n" +
            "FFprobe ships alongside FFmpeg in the same download — if FFmpeg was\n" +
            "found but this wasn't, your FFmpeg install is likely missing ffprobe.exe;\n" +
            "re-download a full FFmpeg build (not a partial/minimal one) and place both\n" +
            "executables in the same location.\n" +
            "Download from: https://ffmpeg.org/download.html"
        );
    }

    /**
     * Verifies that FFmpeg is visible to WhisperX's own Python interpreter.
     *
     * <p>This check ensures that the WhisperX Python environment can find
     * FFmpeg when it shells out internally. The check uses the same environment
     * (with FFmpeg directory injected onto PATH) that real transcription runs use.</p>
     *
     * @return a {@link DependencyStatus} object containing the check result
     */
    public DependencyStatus checkFFmpegVisibleToWhisperX() {
        String pythonExecutable;
        try {
            pythonExecutable = WhisperXTranscriptionService.resolvePythonExecutable();
        } catch (Exception e) {
            LOGGER.debug("Could not resolve WhisperX Python executable for FFmpeg-visibility check", e);
            return new DependencyStatus(
                "FFmpeg (WhisperX visibility)",
                false,
                "Could not resolve the WhisperX Python interpreter to check FFmpeg visibility.",
                "Ensure Python and WhisperX (pip install whisperx) are installed."
            );
        }

        List<String> command = List.of(
            pythonExecutable, "-c",
            "import shutil; print(shutil.which('ffmpeg'))"
        );

        try {
            ProcessRunner.ProcessResult result = ProcessRunner.executeCommand(
                command,
                AppConstants.COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                withFfmpegOnPath(null)
            );

            String output = result.getOutput() == null ? "" : result.getOutput().trim();

            if (result.isSuccess() && !output.isEmpty() && !output.equalsIgnoreCase("None")) {
                LOGGER.info("WhisperX Python environment can see FFmpeg at: {}", output);
                return new DependencyStatus(
                    "FFmpeg (WhisperX visibility)",
                    true,
                    "FFmpeg is visible to the WhisperX Python environment (" + output + ")",
                    null
                );
            }

            LOGGER.warn("WhisperX Python environment cannot see FFmpeg in this advisory check. python='{}', output='{}'",
                    pythonExecutable, output);
            return new DependencyStatus(
                "FFmpeg (WhisperX visibility)",
                false,
                "FFmpeg wasn't confirmed visible to the WhisperX Python environment in this check — "
                + "transcription will still attempt to inject it directly beforehand and often succeeds anyway.",
                "Interpreter checked: " + pythonExecutable + "\n" +
                "FFmpeg resolved to: " + getFFmpegPath() + "\n" +
                "This is an advisory check, not a guarantee of failure — if transcription " +
                "later fails with an FFmpeg-not-found error from within WhisperX, that " +
                "confirms a real problem; if it succeeds, this warning can be disregarded."
            );
        } catch (Exception e) {
            LOGGER.warn("Could not verify FFmpeg visibility inside the WhisperX Python environment", e);
            return new DependencyStatus(
                "FFmpeg (WhisperX visibility)",
                false,
                "Could not verify FFmpeg visibility inside the WhisperX Python environment.",
                "Ensure the WhisperX Python interpreter (" + pythonExecutable + ") can be started."
            );
        }
    }

    /**
     * Returns a copy of the environment with the resolved FFmpeg directory
     * prepended onto the PATH.
     *
     * <p>This allows child processes — particularly WhisperX's Python interpreter —
     * to find FFmpeg even when it isn't on the user's system PATH.</p>
     *
     * @param baseEnv the base environment variables (may be {@code null})
     * @return a new map with FFmpeg prepended to PATH
     */
    public Map<String, String> withFfmpegOnPath(Map<String, String> baseEnv) {
        Map<String, String> env = baseEnv != null ? new HashMap<>(baseEnv) : new HashMap<>();

        String ffmpegPath = getFFmpegPath();
        File ffmpegFile = new File(ffmpegPath);
        File ffmpegDir = ffmpegFile.getParentFile();

        if (ffmpegDir != null && ffmpegDir.isDirectory()) {
            String ffmpegDirAbs = ffmpegDir.getAbsolutePath();
            String existingPath = env.getOrDefault("PATH", System.getenv("PATH"));
            String newPath = ffmpegDirAbs + File.pathSeparator + existingPath;
            env.put("PATH", newPath);
            env.put("PYTHONPATH", env.getOrDefault("PYTHONPATH", ""));
            LOGGER.info("Injected FFmpeg onto PATH for Python: {}", ffmpegDirAbs);
        }
        return env;
    }

    /**
     * Checks if Whisper CLI is available with persistent retry logic.
     *
     * @return a {@link DependencyStatus} object containing the check result
     */
    public DependencyStatus checkWhisper() {
        LOGGER.info("Checking Whisper CLI availability with persistent retries...");

        for (int attempt = 1; attempt <= AppConstants.MAX_DEPENDENCY_RETRIES; attempt++) {
            LOGGER.debug("Whisper check attempt {}/{}", attempt, AppConstants.MAX_DEPENDENCY_RETRIES);

            // Try multiple ways to invoke whisper
            if (checkWhisperCommand("python -m whisper -h") ||
                checkWhisperCommand("whisper -h") ||
                checkWhisperCommand("whisper --version")) {
                
                LOGGER.info("Whisper CLI found and operational (attempt {})", attempt);
                return new DependencyStatus(
                    "Whisper CLI", 
                    true, 
                    "Whisper CLI found and ready for transcription",
                    null
                );
            }

            if (attempt < AppConstants.MAX_DEPENDENCY_RETRIES) {
                try {
                    LOGGER.debug("Waiting {}ms before Whisper retry...", AppConstants.RETRY_DELAY_MS);
                    Thread.sleep(AppConstants.RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOGGER.warn("Whisper CLI not found after {} attempts", AppConstants.MAX_DEPENDENCY_RETRIES);
        return new DependencyStatus(
            "Whisper CLI", 
            false, 
            "Whisper CLI NOT FOUND (Optional - needed for transcription)",
            "Whisper CLI is required for transcription.\n" +
            "Install via: pip install openai-whisper\n" +
            "Or: pip install -U openai-whisper\n" +
            "More info: https://github.com/openai/whisper\n" +
            "Note: Requires Python 3.8+ and pip"
        );
    }

    /**
     * Helper method to check if a Whisper command is available.
     *
     * @param command the command to check
     * @return {@code true} if the command is available
     */
    private boolean checkWhisperCommand(String command) {
        return ProcessRunner.isCommandAvailable(
            command, 
            AppConstants.COMMAND_TIMEOUT_SECONDS, 
            TimeUnit.SECONDS
        );
    }

    // -------------------------------------------------------------------------
    //  Executable Resolution
    // -------------------------------------------------------------------------

    /**
     * Returns the resolved FFmpeg executable path.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Studio-resolved bundled path (if running in a packaged app)</li>
     *   <li>Bundled runtime directory</li>
     *   <li>Fixed install location (Windows only)</li>
     *   <li>System PATH (bare "ffmpeg" command)</li>
     * </ol>
     *
     * @return the absolute path to FFmpeg, or the bare command name as a fallback
     */
    public String getFFmpegPath() {
        if (ffmpegPath == null) {
            audiomanager.Studio studio = audiomanager.Studio.getInstance();
            if (studio != null && studio.isFFmpegAvailable()) {
                ffmpegPath = studio.getFfmpegPath();
                LOGGER.info("Using Studio-resolved bundled ffmpeg: {}", ffmpegPath);
                return ffmpegPath;
            }
            ffmpegPath = resolveExecutable("ffmpeg");
        }
        return ffmpegPath;
    }

    /**
     * Returns the resolved FFprobe executable path.
     *
     * <p>Resolution order follows the same priority as {@link #getFFmpegPath()}.</p>
     *
     * @return the absolute path to FFprobe, or the bare command name as a fallback
     */
    public String getFFprobePath() {
        if (ffprobePath == null) {
            ffprobePath = resolveExecutable("ffprobe");
        }
        return ffprobePath;
    }

    /**
     * Resolves an executable name to an absolute path.
     *
     * @param baseName the executable name without extension (e.g., "ffmpeg")
     * @return the absolute path to the executable, or the bare name as a fallback
     */
    private String resolveExecutable(String baseName) {
        String exeFileName = IS_WINDOWS ? baseName + ".exe" : baseName;

        // 1. Bundled runtime
        File bundled = new File(getApplicationDirectory(), BUNDLED_RUNTIME_SUBDIR + File.separator + exeFileName);
        if (bundled.isFile()) {
            LOGGER.info("Using bundled {} at: {}", baseName, bundled.getAbsolutePath());
            return bundled.getAbsolutePath();
        }

        // 2. Fixed install location (Windows only)
        if (IS_WINDOWS) {
            File fixed = new File(FIXED_INSTALL_DIR, exeFileName);
            if (fixed.isFile()) {
                LOGGER.info("Using {} from fixed install location: {}", baseName, fixed.getAbsolutePath());
                return fixed.getAbsolutePath();
            }
        }

        LOGGER.debug("Neither bundled nor fixed-location {} found; falling back to PATH.", exeFileName);

        // 3. System PATH
        return baseName;
    }

    /**
     * Returns the application directory for bundled resource resolution.
     *
     * @return the directory containing the application
     */
    private File getApplicationDirectory() {
        try {
            Path codeSource = Paths.get(
                    DependencyManager.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());
            File location = codeSource.toFile();
            return location.isFile() ? location.getParentFile() : location;
        } catch (URISyntaxException | NullPointerException | SecurityException e) {
            LOGGER.debug("Could not determine application directory; using working directory instead.", e);
            return new File(System.getProperty("user.dir", "."));
        }
    }

    /**
     * Wraps a resolved path in quotes if it contains spaces.
     *
     * @param path the path to quote
     * @return the quoted path if necessary
     */
    private String quoteIfNeeded(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    // -------------------------------------------------------------------------
    //  Alignment Model Check
    // -------------------------------------------------------------------------

    /**
     * Checks if the alignment model is available and reports its status.
     *
     * <p>The alignment model (~360MB) is required for precise timestamp
     * alignment and speaker diarisation.</p>
     *
     * @return a {@link DependencyStatus} object containing the check result
     */
    public DependencyStatus checkAlignmentModel() {
        Path modelPath = Paths.get(System.getProperty("user.home"), 
            ".cache", "torch", "hub", "checkpoints", "wav2vec2_fairseq_base_ls960_asr_ls960.pth");

        try {
            if (Files.exists(modelPath)) {
                long size = Files.size(modelPath);
                if (size > 300_000_000) { // 300 MB minimum
                    return new DependencyStatus(
                        "Alignment Model",
                        true,
                        "Alignment model found (" + formatBytes(size) + ")",
                        null
                    );
                } else {
                    return new DependencyStatus(
                        "Alignment Model",
                        false,
                        "Alignment model is incomplete (" + formatBytes(size) + " expected >300MB)",
                        "The alignment model appears to be truncated. Delete the file and let the app re-download it."
                    );
                }
            } else {
                return new DependencyStatus(
                    "Alignment Model",
                    false,
                    "Alignment model not found (required for precise timestamps)",
                    "The alignment model (~360MB) will be downloaded on first use when alignment is enabled.\n" +
                    "Or download manually from:\n" +
                    "https://download.pytorch.org/torchaudio/models/wav2vec2_fairseq_base_ls960_asr_ls960.pth\n" +
                    "Save to: " + modelPath
                );
            }
        } catch (IOException e) {
            return new DependencyStatus(
                "Alignment Model",
                false,
                "Could not check alignment model: " + e.getMessage(),
                "Check file permissions at: " + modelPath
            );
        }
    }

    /**
     * Formats bytes to a human-readable string.
     *
     * @param bytes the number of bytes
     * @return a formatted string (e.g., "1.5 GB")
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    // -------------------------------------------------------------------------
    //  Inner Class: DependencyStatus
    // -------------------------------------------------------------------------

    /**
     * Result of a dependency check.
     *
     * <p>This class encapsulates the availability status of a dependency,
     * along with a message and optional installation instructions.</p>
     */
    public static class DependencyStatus {
        private final String name;
        private final boolean available;
        private final String message;
        private final String installationHint;

        /**
         * Constructs a new DependencyStatus.
         *
         * @param name the name of the dependency
         * @param available {@code true} if the dependency is available
         * @param message a status message
         * @param installationHint installation instructions (may be {@code null})
         */
        public DependencyStatus(String name, boolean available, String message, String installationHint) {
            this.name = name;
            this.available = available;
            this.message = message;
            this.installationHint = installationHint;
        }

        public String getName() { return name; }
        public boolean isAvailable() { return available; }
        public String getMessage() { return message; }
        public String getInstallationHint() { return installationHint; }

        public boolean hasInstallationHint() {
            return installationHint != null && !installationHint.isEmpty();
        }

        @Override
        public String toString() {
            return String.format("DependencyStatus{name='%s', available=%s, message='%s'}", 
                               name, available, message);
        }
    }
}