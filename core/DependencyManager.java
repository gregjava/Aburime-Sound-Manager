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
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manages checking and validating external dependencies (FFmpeg, Whisper)
 */
public class DependencyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyManager.class);

    // -------------------------------------------------------------------------
    //  FFmpeg / FFprobe executable resolution
    // -------------------------------------------------------------------------
    //
    // FIX: getFFmpegPath()/getFFprobePath() used to just return the bare
    // "ffmpeg"/"ffprobe" strings, which only worked if the user had manually
    // added FFmpeg to their system PATH. They are now resolved in priority
    // order:
    //   1. Bundled with the app:      runtime\ffmpeg\ffmpeg.exe / ffprobe.exe
    //   2. Fixed install location:    C:\AI\ffmpeg\bin\ffmpeg.exe / ffprobe.exe
    //   3. System PATH (last resort): bare "ffmpeg" / "ffprobe"
    // The first candidate that actually exists on disk wins. Resolved paths
    // are cached for the lifetime of this instance. On non-Windows platforms
    // the two Windows-specific candidates are skipped and resolution falls
    // straight through to PATH.

    /** Relative-to-app-directory location of a bundled FFmpeg build. */
    private static final String BUNDLED_RUNTIME_SUBDIR = "runtime" + File.separator + "ffmpeg";

    /** Fixed, well-known install location used by the AudioManager installer/docs. */
    private static final String FIXED_INSTALL_DIR = "C:\\AI\\ffmpeg\\bin";

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private volatile String ffmpegPath;
    private volatile String ffprobePath;

    /**
     * Check if FFmpeg is available with ASoM retry logic
     * @return 
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
     * Verifies FFprobe specifically, separately from {@link #checkFFmpeg()}.
     *
     * <p>FIX: previously nothing checked FFprobe at startup at all —
     * {@link #checkFFmpeg()} only verifies {@code ffmpeg -version} runs.
     * FFmpeg and FFprobe are separate executables shipped side by side in
     * the same install, and a partial/custom FFmpeg install can easily have
     * one without the other (this is exactly what happened in practice: a
     * fixed install directory containing {@code ffmpeg.exe} but not
     * {@code ffprobe.exe} passed every dependency check cleanly, then every
     * single file in the batch failed deep inside {@code AudioProcessor}
     * with a raw {@code CreateProcess error=2}, instead of a clear
     * installation hint at startup like a missing FFmpeg gets). Checking
     * both explicitly, up front, means a missing FFprobe is caught and
     * reported the same way a missing FFmpeg already is.</p>
     *
     * @return status describing whether FFprobe is resolvable and runnable
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
     * Verifies that FFmpeg isn't just runnable by Java — it also needs to be
     * <em>visible to WhisperX's own Python interpreter</em>, since WhisperX
     * shells out to ffmpeg internally. A machine can easily have FFmpeg
     * resolvable by {@link #checkFFmpeg()} (bundled / fixed install / system
     * PATH) while the WhisperX venv's Python process still can't see it,
     * because Python resolves executables using its own inherited PATH.
     *
     * <p>This runs, e.g.:</p>
     * <pre>{@code
     * C:\AI\whisperx_env\Scripts\python.exe -c "import shutil; print(shutil.which('ffmpeg'))"
     * }</pre>
     * <p>using the <em>same</em> environment (FFmpeg directory injected onto
     * PATH — see {@link #withFfmpegOnPath}) that real transcription runs use,
     * so a pass here means transcription won't fail later with a confusing
     * "ffmpeg not found" error from deep inside WhisperX.</p>
     *
     * @return status describing whether WhisperX's Python environment can see FFmpeg
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

            LOGGER.warn("WhisperX Python environment cannot see FFmpeg. python='{}', output='{}'",
                    pythonExecutable, output);
            return new DependencyStatus(
                "FFmpeg (WhisperX visibility)",
                false,
                "FFmpeg is installed but is not visible to the WhisperX Python environment.",
                "Interpreter checked: " + pythonExecutable + "\n" +
                "FFmpeg resolved to: " + getFFmpegPath() + "\n" +
                "The FFmpeg directory is normally injected onto that process's PATH " +
                "automatically — if this still fails, verify the interpreter above can " +
                "start at all and that the resolved FFmpeg path is a real executable."
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
     * Returns a copy of {@code baseEnv} (or a fresh map if {@code baseEnv} is
     * {@code null}) with the resolved FFmpeg directory prepended onto {@code PATH}.
     *
     * <p>This lets child processes — WhisperX's Python interpreter in
     * particular — find FFmpeg even when it isn't on the user's system PATH,
     * without ever touching the user's actual system environment variables.
     * If FFmpeg only resolved to the bare {@code "ffmpeg"}/{@code "ffprobe"}
     * PATH-fallback (i.e. no absolute path was found), there's no directory
     * to inject and {@code baseEnv} is returned unchanged.</p>
     *
     * @param baseEnv environment variables to layer the PATH change on top of; may be {@code null}
     * @return a new map safe to hand to {@link ProcessRunner}
     */
    public Map<String, String> withFfmpegOnPath(Map<String, String> baseEnv) {
        Map<String, String> env = baseEnv != null ? new HashMap<>(baseEnv) : new HashMap<>();

        String ffmpegPath = getFFmpegPath();
        File ffmpegFile = new File(ffmpegPath);
        File ffmpegDir = ffmpegFile.getParentFile();

        if (ffmpegDir != null && ffmpegDir.isDirectory()) {
            // FIX: Use absolute path explicitly
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
     * Check if Whisper CLI is available with v2.5 persistent retry logic
     * @return A dependencyStatus object that gives information about the System Dependencies state of the app.
     */
    public DependencyStatus checkWhisper() {
        LOGGER.info("Checking Whisper CLI availability with persistent retries...");

        for (int attempt = 1; attempt <= AppConstants.MAX_DEPENDENCY_RETRIES; attempt++) {
            LOGGER.debug("Whisper check attempt {}/{}", attempt, AppConstants.MAX_DEPENDENCY_RETRIES);

            // Try multiple ways to invoke whisper (v2.5 logic)
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

    private boolean checkWhisperCommand(String command) {
        return ProcessRunner.isCommandAvailable(
            command, 
            AppConstants.COMMAND_TIMEOUT_SECONDS, 
            TimeUnit.SECONDS
        );
    }

    /**
     * Get FFmpeg executable path.
     * Resolved bundled -&gt; fixed install location -&gt; PATH (see class Javadoc / comment above).
     * @return The path wherein FFmpeg is stored on the system, or the bare "ffmpeg" command as a PATH fallback.
     */
    public String getFFmpegPath() {
        if (ffmpegPath == null) {
            ffmpegPath = resolveExecutable("ffmpeg");
        }
        return ffmpegPath;
    }

    /**
     * Get FFprobe executable path.
     * Resolved bundled -&gt; fixed install location -&gt; PATH (see class Javadoc / comment above).
     * @return The path wherein FFprobe is stored on the system, or the bare "ffprobe" command as a PATH fallback.
     */
    public String getFFprobePath() {
        if (ffprobePath == null) {
            ffprobePath = resolveExecutable("ffprobe");
        }
        return ffprobePath;
    }

    /**
     * Resolves {@code baseName} (e.g. "ffmpeg", "ffprobe") to an absolute path
     * using the bundled-runtime -&gt; fixed-install -&gt; PATH priority order.
     *
     * @param baseName executable name without extension
     * @return an absolute path to an existing file, or the bare {@code baseName}
     *         if nothing was found on disk (letting PATH resolution try last)
     */
    private String resolveExecutable(String baseName) {
        if (IS_WINDOWS) {
            String exeFileName = baseName + ".exe";

            // 1. Bundled runtime, relative to the running application.
            File bundled = new File(getApplicationDirectory(), BUNDLED_RUNTIME_SUBDIR + File.separator + exeFileName);
            if (bundled.isFile()) {
                LOGGER.info("Using bundled {} at: {}", baseName, bundled.getAbsolutePath());
                return bundled.getAbsolutePath();
            }

            // 2. Fixed, well-known install location.
            File fixed = new File(FIXED_INSTALL_DIR, exeFileName);
            if (fixed.isFile()) {
                LOGGER.info("Using {} from fixed install location: {}", baseName, fixed.getAbsolutePath());
                return fixed.getAbsolutePath();
            }

            LOGGER.debug("Neither bundled nor fixed-location {} found; falling back to PATH.", exeFileName);
        }

        // 3. Last resort: rely on the system PATH.
        return baseName;
    }

    /**
     * Best-effort directory that the running application lives in — used as the
     * base for the bundled {@code runtime/ffmpeg} lookup. Falls back to the
     * current working directory if the code source can't be determined (e.g.
     * running from an IDE with a non-standard classpath).
     */
    private File getApplicationDirectory() {
        try {
            Path codeSource = Paths.get(
                    DependencyManager.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());
            File location = codeSource.toFile();
            // If running from a jar, codeSource points at the jar file itself;
            // if running from exploded classes, it points at a directory.
            return location.isFile() ? location.getParentFile() : location;
        } catch (URISyntaxException | NullPointerException | SecurityException e) {
            LOGGER.debug("Could not determine application directory; using working directory instead.", e);
            return new File(System.getProperty("user.dir", "."));
        }
    }

    /** Wraps a resolved path in quotes if it contains spaces, since it's about to be embedded in a single command string. */
    private String quoteIfNeeded(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    /**
     * Result of a dependency check
     */
    public static class DependencyStatus {
        private final String name;
        private final boolean available;
        private final String message;
        private final String installationHint;

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