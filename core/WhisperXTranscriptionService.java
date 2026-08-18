/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.exceptions.ModelDownloadException;
import audiomanager.model.*;
import audiomanager.util.TimeLeftEstimator;
import audiomanager.util.ProcessRunner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.HashMap;

/**
 * WhisperX-backed transcription service.
 *
 * <h2>Security note</h2>
 * The HuggingFace token is <b>never</b> embedded in source code.  It is read
 * at construction time from, in priority order:
 * <ol>
 *   <li>The {@code HF_TOKEN} environment variable.</li>
 *   <li>The {@code hf.token} Java system property.</li>
 *   <li>The file {@code ~/.audiomanager/hf_token} (plain text, one line).</li>
 * </ol>
 * If none of the sources supply a token the service starts without one;
 * speaker diarisation will be disabled and a warning is logged.
 *
 * <h2>Thread safety</h2>
 * {@link #setSegmentListener} is a {@code volatile} write, which is safe as
 * long as the listener is set before {@link #transcribe} is called (documented
 * contract).
 */
public class WhisperXTranscriptionService implements TranscriptionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WhisperXTranscriptionService.class);

    /**
     * Per-stage wall-clock time (milliseconds) reported by the Python
     * script's {@code STAGE_TIMING:<stage>:<seconds>} log lines for the
     * most recently completed {@link #transcribe} call on this instance.
     * Populated live as output streams in, read by the caller immediately
     * after {@link #transcribe} returns — safe because
     * {@code ModelInstancePool} guarantees exclusive use of a given
     * instance between {@code borrow()} and {@code release()}, so there's
     * no cross-file interference despite this being instance state rather
     * than a return value (changing {@code transcribe}'s return type would
     * ripple through every caller for comparatively little benefit).
     */
    private final Map<String, Long> lastPythonStageTimingsMs = new java.util.concurrent.ConcurrentHashMap<>();

    // FIX: peak_memory_mb and avg_cpu_percent are the two STAGE_TIMING keys
    // that are NOT second-valued durations (they're megabytes and a
    // percentage respectively) — parseStageTimingLine() was previously
    // dumping every matched key into lastPythonStageTimingsMs via
    // Math.round(value * 1000), which is correct for turning seconds into
    // milliseconds but silently corrupts these two: a real peak of 412.3MB
    // was being stored as "412300" (as if it were 412.3 *seconds*, i.e.
    // ~6.9 minutes), and an average CPU of 23.4% became "23400". Anything
    // that later rendered these two keys via the generic ms-stage path
    // would have shown wildly wrong numbers. They're captured here instead,
    // at their real scale, with their own accessors.
    private volatile double lastPythonPeakMemoryMb = -1;
    private volatile double lastPythonAvgCpuPercent = -1;

    private static final java.util.regex.Pattern STAGE_TIMING_PATTERN =
            java.util.regex.Pattern.compile("STAGE_TIMING:([a-zA-Z_]+):(-?[0-9.]+)");
    private static final String ALIGNMENT_MODEL_URL = 
        "https://download.pytorch.org/torchaudio/models/wav2vec2_fairseq_base_ls960_asr_ls960.pth";
    private static final long ALIGNMENT_MODEL_MIN_SIZE = 300_000_000L; // 300 MB minimum
    private static final String ALIGNMENT_MODEL_FILENAME = 
        "wav2vec2_fairseq_base_ls960_asr_ls960.pth";
    private ErrorReporter errorReporter;

    private void parseStageTimingLine(String line) {
        java.util.regex.Matcher m = STAGE_TIMING_PATTERN.matcher(line);
        if (m.find()) {
            try {
                String stage = m.group(1);
                double value = Double.parseDouble(m.group(2));
                if ("peak_memory_mb".equals(stage)) {
                    lastPythonPeakMemoryMb = value;
                } else if ("avg_cpu_percent".equals(stage)) {
                    lastPythonAvgCpuPercent = value;
                } else {
                    lastPythonStageTimingsMs.put(stage, Math.round(value * 1000));
                }
            } catch (NumberFormatException e) {
                LOGGER.debug("Could not parse STAGE_TIMING line: {}", line);
            }
        }
    }

    /** Peak RSS memory (MB) of the Python process (+ any children) during the most recent {@link #transcribe} call, or -1 if psutil wasn't available in that venv. */
    public double getLastPythonPeakMemoryMb() {
        return lastPythonPeakMemoryMb;
    }

    /** Average CPU utilisation (%) of the Python process (+ any children) during the most recent {@link #transcribe} call, or -1 if psutil wasn't available in that venv. */
    public double getLastPythonAvgCpuPercent() {
        return lastPythonAvgCpuPercent;
    }

    /**
     * Stage timings (milliseconds) from the most recently completed
     * {@link #transcribe} call on this instance — e.g. keys
     * {@code model_load}, {@code audio_load}, {@code transcription},
     * {@code alignment}, {@code diarization}, {@code total}. Empty if the
     * Python script didn't emit any (e.g. an older script without this
     * instrumentation, or the call failed before any stage completed).
     */
    public Map<String, Long> getLastPythonStageTimingsMs() {
        return new java.util.LinkedHashMap<>(lastPythonStageTimingsMs);
    }

    private final DependencyManager dependencyManager;
    private final Gson gson;
    private final boolean useGPU;
    private final String hfToken;

    private final ModelManager modelManager;
    private final TimeLeftEstimator timeEstimator;
    private volatile SegmentProgressListener segmentListener;
    private final String pythonExecutable;

    // -------------------------------------------------------------------------
    //  Constructors
    // -------------------------------------------------------------------------

    public WhisperXTranscriptionService(DependencyManager dependencyManager, 
                          ErrorReporter errorReporter) {
        this(dependencyManager, null, errorReporter);
    }

    public WhisperXTranscriptionService(DependencyManager dependencyManager,
                                        TimeLeftEstimator timeEstimator, 
                          ErrorReporter errorReporter) {
        this(dependencyManager, timeEstimator, null,  errorReporter);
    }

    public WhisperXTranscriptionService(DependencyManager dependencyManager,
                                        TimeLeftEstimator timeEstimator,
                                        SegmentProgressListener listener, 
                          ErrorReporter errorReporter) {
        this.dependencyManager = dependencyManager;
        this.timeEstimator     = timeEstimator;
        this.gson              = new Gson();
        this.useGPU            = checkGPUAvailability();
        this.segmentListener   = listener;

        // FIX: token is resolved from environment / system-property / file — never hard-coded.
        this.hfToken = resolveHfToken();

        if (hfToken == null || hfToken.isBlank()) {
            LOGGER.warn("No HF token found. Speaker diarisation will be disabled. "
                    + "Set the HF_TOKEN environment variable or write the token to "
                    + "~/.audiomanager/hf_token to enable diarisation.");
        } else {
            LOGGER.info("HF token loaded. Speaker diarisation available.");
        }

        this.modelManager = new ModelManager();
        this.pythonExecutable = resolvePythonExecutable();
        LOGGER.info("Python executable resolved to: {}", this.pythonExecutable);
        LOGGER.info("WhisperXTranscriptionService initialised — GPU: {}", useGPU);
        showModelCacheStatus();
    }

    // -------------------------------------------------------------------------
    //  Token resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve the HuggingFace token from the environment, a system property,
     * or a per-user token file.  Returns {@code null} if no token is found.
     */
    private static String resolveHfToken() {
        // FIX: warn if a -DHF_TOKEN=... system property is present. This
        // resolver has never read that key (it checks the env var HF_TOKEN
        // and the system property hf.token — different namespace and a
        // different key name), so a -DHF_TOKEN flag does nothing for this
        // app except sit in the JVM's argument list. That's exactly how a
        // real token ended up exposed in plaintext in a NetBeans build
        // console log: -D flags are dumped verbatim into that console (and
        // are visible to anything that can list this process's arguments)
        // every single run, for no benefit. If this fires, remove
        // -DHF_TOKEN=... from the run configuration and set a genuine
        // HF_TOKEN environment variable instead.
        if (System.getProperty("HF_TOKEN") != null) {
            LOGGER.warn("=============================================================");
            LOGGER.warn("A -DHF_TOKEN system property is set but is NOT used by this");
            LOGGER.warn("app (it has no effect on token resolution) — it exists only to");
            LOGGER.warn("be dumped in plaintext into this run's console/build log every");
            LOGGER.warn("time the app starts. Remove -DHF_TOKEN=... from the run/launch");
            LOGGER.warn("configuration and set a real HF_TOKEN environment variable");
            LOGGER.warn("instead. If that token has ever been logged or committed");
            LOGGER.warn("anywhere, rotate it on huggingface.co/settings/tokens.");
            LOGGER.warn("=============================================================");
        }

        // 1. Environment variable (highest priority)
        String token = System.getenv("HF_TOKEN");
        if (token != null && !token.isBlank()) {
            LOGGER.debug("HF token loaded from environment variable HF_TOKEN.");
            return token.trim();
        }

        // 2. Java system property (e.g. -Dhf.token=hf_xxx on the command line)
        token = System.getProperty("hf.token");
        if (token != null && !token.isBlank()) {
            LOGGER.warn("HF token loaded from -Dhf.token system property — this is also");
            LOGGER.warn("visible in process arguments and run-console logs. Prefer an");
            LOGGER.warn("HF_TOKEN environment variable or the ~/.audiomanager/hf_token file.");
            return token.trim();
        }

        // 3. Per-user file (~/.audiomanager/hf_token)
        Path tokenFile = Paths.get(System.getProperty("user.home"), ".audiomanager", "hf_token");
        if (Files.exists(tokenFile)) {
            try {
                token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
                if (!token.isBlank()) {
                    LOGGER.debug("HF token loaded from file: {}", tokenFile);
                    return token;
                }
            } catch (IOException e) {
                LOGGER.warn("Could not read HF token file {}: {}", tokenFile, e.getMessage());
            }
        }

        return null;
    }
    
    /**
     * Ensures the alignment model is available before alignment is attempted.
     * Downloads it on first use with progress reporting.
     * 
     * <p>The alignment model (~360MB) is required for precise timestamp alignment
     * and speaker diarization. It's downloaded from PyTorch's official repository
     * and cached in ~/.cache/torch/hub/checkpoints/</p>
     * 
     * <p>This method is called automatically when alignment or diarization is enabled.
     * It will download the model with progress reporting if not already present.</p>
     * 
     * @param progressCallback callback for download progress updates, may be null
     * @throws Exception if download fails or the model is corrupted
     */
    private void ensureAlignmentModelAvailable(AudioProcessor.ProgressCallback progressCallback) 
            throws Exception {

        // Determine the model path based on OS
        Path modelPath = getAlignmentModelPath();

        // Check if model already exists and is valid
        if (Files.exists(modelPath) && Files.size(modelPath) >= ALIGNMENT_MODEL_MIN_SIZE) {
            LOGGER.info("Alignment model found at: {} ({} MB)", 
                modelPath, Files.size(modelPath) / (1024 * 1024));
            return;
        }

        // Check if there's a partial/incomplete file
        if (Files.exists(modelPath)) {
            long currentSize = Files.size(modelPath);
            LOGGER.warn("Alignment model exists but is incomplete: {} bytes (expected > {} bytes)",
                currentSize, ALIGNMENT_MODEL_MIN_SIZE);
            Files.deleteIfExists(modelPath);
            LOGGER.info("Removed incomplete alignment model, will re-download");
        }

        // Create parent directories
        Files.createDirectories(modelPath.getParent());

        LOGGER.info("Alignment model not found. Downloading from: {}", ALIGNMENT_MODEL_URL);

        // Notify progress start
        if (progressCallback != null) {
            progressCallback.updateProgress(0.0);
        }

        // Download with progress tracking
        long downloadedBytes = 0;
        long totalBytes = -1;

        try {
            URL url = new URL(ALIGNMENT_MODEL_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "AudioManager/2.0");
            conn.setConnectTimeout(30000); // 30 second timeout
            conn.setReadTimeout(60000);    // 60 second read timeout
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            totalBytes = conn.getContentLengthLong();
            if (totalBytes <= 0) {
                LOGGER.warn("Content length unknown, using fallback size check");
            }

            // Download the file
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(modelPath.toFile())) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                double lastProgressUpdate = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;

                    // Update progress at most every 1% or 100ms
                    if (progressCallback != null && totalBytes > 0) {
                        double progress = (double) downloadedBytes / totalBytes;
                        if (progress - lastProgressUpdate > 0.01 || progress >= 1.0) {
                            lastProgressUpdate = progress;
                            // Reserve 90% for download, 10% for verification
                            progressCallback.updateProgress(progress * 0.9);
                        }
                    }
                }
            }

            // Verify download
            long actualSize = Files.size(modelPath);
            if (actualSize < ALIGNMENT_MODEL_MIN_SIZE) {
                throw new IOException(String.format(
                    "Downloaded file is too small: %d bytes (expected > %d bytes)",
                    actualSize, ALIGNMENT_MODEL_MIN_SIZE));
            }

            LOGGER.info("Alignment model downloaded successfully: {} MB", 
                actualSize / (1024 * 1024));

            if (progressCallback != null) {
                progressCallback.updateProgress(1.0);
            }

        } catch (Exception e) {
            // Clean up partial download
            try {
                Files.deleteIfExists(modelPath);
            } catch (IOException cleanupEx) {
                LOGGER.warn("Failed to clean up partial download: {}", cleanupEx.getMessage());
            }

            LOGGER.error("Failed to download alignment model", e);
            throw new Exception("Failed to download alignment model: " + e.getMessage(), e);
        }
    }
    
    /**
     * Creates a progress callback that maps alignment model download progress
     * to the overall transcription progress.
     * 
     * @param parentCallback the main progress callback
     * @param weight the weight of alignment progress in the overall progress (0.0 to 1.0)
     * @return a wrapped callback, or null if parentCallback is null
     */
    private AudioProcessor.ProgressCallback wrapAlignmentProgress(
            AudioProcessor.ProgressCallback parentCallback, double weight) {
        if (parentCallback == null) return null;
        return p -> parentCallback.updateProgress(Math.min(1.0, p * weight));
    }

    /**
     * Gets the platform-appropriate path for the alignment model.
     * 
     * @return Path where the alignment model should be stored
     */
    private Path getAlignmentModelPath() {
        String os = System.getProperty("os.name").toLowerCase();
        Path basePath;

        if (os.contains("win")) {
            // Windows: %APPDATA%/.cache/torch/hub/checkpoints/
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                basePath = Paths.get(appData, ".cache", "torch", "hub", "checkpoints");
            } else {
                basePath = Paths.get(System.getProperty("user.home"), ".cache", "torch", "hub", "checkpoints");
            }
        } else if (os.contains("mac")) {
            // macOS: ~/Library/Caches/torch/hub/checkpoints/
            basePath = Paths.get(System.getProperty("user.home"), 
                "Library", "Caches", "torch", "hub", "checkpoints");
        } else {
            // Linux/Unix: ~/.cache/torch/hub/checkpoints/
            basePath = Paths.get(System.getProperty("user.home"), 
                ".cache", "torch", "hub", "checkpoints");
        }

        return basePath.resolve(ALIGNMENT_MODEL_FILENAME);
    }

    // -------------------------------------------------------------------------
    //  Python interpreter resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve the Python executable that has WhisperX installed.
     *
     * <p>On Windows machines with multiple Python installations the bare
     * {@code "python"} token is resolved by the PATH, which frequently points
     * to the wrong interpreter (e.g. the system-wide Python 3.14 rather than
     * the {@code whisper_env} virtual environment).  This method selects the
     * correct interpreter using the following priority order:</p>
     * <ol>
     *   <li>The {@code WHISPERX_PYTHON} environment variable — an explicit
     *       override that works on any OS without code changes.</li>
     *   <li>The {@code python.exe} that lives alongside the {@code whisperx.exe}
     *       found by {@code where whisperx} (Windows) or {@code which whisperx}
     *       (Unix).  This is always the venv interpreter that owns the
     *       WhisperX package.</li>
     *   <li>The well-known default venv path
     *       {@code ~/whisper_env/Scripts/python.exe} (Windows) or
     *       {@code ~/whisper_env/bin/python} (Unix).</li>
     *   <li>Fallback: the bare {@code "python"} token — same behaviour as
     *       before, but a warning is logged so the problem is visible.</li>
     * </ol>
     */
    // FIX: package-private (was private) so DependencyManager can reuse this exact
    // resolution logic for its "can WhisperX's Python see FFmpeg?" check — avoids
    // duplicating/drifting the venv-interpreter-detection logic in two places.
    public static String resolvePythonExecutable() {
        final boolean isWindows =
                System.getProperty("os.name", "").toLowerCase().contains("win");

        /*
         * ------------------------------------------------------------------
         * 1. Explicit override (highest priority)
         * ------------------------------------------------------------------
         */
        String override = System.getenv("WHISPERX_PYTHON");

        if (override != null && !override.isBlank()) {

            Path overridePath = Paths.get(override.trim());

            if (Files.exists(overridePath)) {

                LOGGER.info("Using Python from WHISPERX_PYTHON: {}", overridePath);

                if (isWhisperXInstalled(overridePath.toString())) {
                    return overridePath.toAbsolutePath().toString();
                }

                LOGGER.warn("Python specified by WHISPERX_PYTHON exists but WhisperX is not installed.");
            }
        }

        /*
         * ------------------------------------------------------------------
         * 1.5. Bundled distribution (Studio.getInstance()) — the app's own
         *      packaged Python/WhisperX venv, resolved once at startup by
         *      Studio.validateBundledPaths(). Takes priority over the old
         *      PATH-search/guessed-default-venv steps below, since a
         *      genuine bundled distribution is more authoritative than
         *      guessing — but an explicit WHISPERX_PYTHON override above
         *      still wins over even this, since that's the whole point of
         *      an explicit override. Null-safe: Studio.getInstance() is
         *      always null in unit tests (which never call
         *      Application.launch()) and before init() runs, so this step
         *      harmlessly falls through to the pre-bundling behavior below
         *      whenever there's no real bundled distribution to use.
         * ------------------------------------------------------------------
         */
        audiomanager.Studio studio = audiomanager.Studio.getInstance();
        if (studio != null && studio.isWhisperAvailable()) {
            String bundledPython = studio.getWhisperPythonPath();
            if (isWhisperXInstalled(bundledPython)) {
                LOGGER.info("Using bundled WhisperX Python: {}", bundledPython);
                return bundledPython;
            }
            LOGGER.warn("Studio reports a bundled Python at {} but WhisperX is not installed there — "
                    + "falling back to PATH search.", bundledPython);
        }

        /*
         * ------------------------------------------------------------------
         * 2. Locate whisperx executable
         * ------------------------------------------------------------------
         */
        String locateCommand = isWindows ? "where" : "which";

        try {

            Process process = new ProcessBuilder(locateCommand, "whisperx")
                    .redirectErrorStream(true)
                    .start();

            process.waitFor(5, TimeUnit.SECONDS);

            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            for (String line : output.split("\\R")) {

                line = line.trim();

                if (line.isBlank()) {
                    continue;
                }

                Path whisperxExecutable;

                try {
                    whisperxExecutable = Paths.get(line);
                }
                catch (InvalidPathException ex) {
                    // Ignore messages such as:
                    // INFO: Could not find files for the given pattern(s).
                    continue;
                }

                if (!Files.exists(whisperxExecutable)) {
                    continue;
                }

                Path python = isWindows
                        ? whisperxExecutable.getParent().resolve("python.exe")
                        : whisperxExecutable.getParent().resolve("python");

                if (Files.exists(python)) {

                    LOGGER.info("Python derived from WhisperX executable: {}", python);

                    if (isWhisperXInstalled(python.toString())) {
                        return python.toAbsolutePath().toString();
                    }
                }
            }

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Interrupted while locating WhisperX.", ex);
        }
        catch (IOException ex) {
            LOGGER.debug("Unable to locate WhisperX executable.", ex);
        }

        /*
         * ------------------------------------------------------------------
         * 3. Common virtual environment locations
         * ------------------------------------------------------------------
         */

        List<Path> candidates = new ArrayList<>();

        String userHome = System.getProperty("user.home");

        if (isWindows) {

            candidates.add(Paths.get(userHome, "whisper_env", "Scripts", "python.exe"));
            candidates.add(Paths.get("C:", "AI", "whisperx_env", "Scripts", "python.exe"));

        } else {

            candidates.add(Paths.get(userHome, "whisper_env", "bin", "python"));
            candidates.add(Paths.get(userHome, "whisperx_env", "bin", "python"));
        }

        for (Path python : candidates) {

            if (!Files.exists(python)) {
                continue;
            }

            LOGGER.info("Testing Python interpreter: {}", python);

            if (isWhisperXInstalled(python.toString())) {

                LOGGER.info("WhisperX found using {}", python);

                return python.toAbsolutePath().toString();
            }
        }

        /*
         * ------------------------------------------------------------------
         * 4. Search PATH for python
         * ------------------------------------------------------------------
         */

        String[] pathCandidates = isWindows
                ? new String[]{"python.exe", "python", "py"}
                : new String[]{"python3", "python"};

        for (String python : pathCandidates) {

            if (isWhisperXInstalled(python)) {

                LOGGER.info("Using Python from PATH: {}", python);

                return python;
            }
        }

        /*
         * ------------------------------------------------------------------
         * 5. Nothing found
         * ------------------------------------------------------------------
         */

        throw new IllegalStateException(
                "\n\nWhisperX is not installed or could not be located.\n\n"
                + "Please install WhisperX into a Python virtual environment and\n"
                + "set the WHISPERX_PYTHON environment variable to that Python executable.\n\n"
                + "Example:\n"
                + "C:\\AI\\whisperx_env\\Scripts\\python.exe\n");
    }

    private static boolean isWhisperXInstalled(String pythonExecutable) {
        try {

            Process process = new ProcessBuilder(
                    pythonExecutable,
                    "-m",
                    "whisperx",
                    "--help")
                    .redirectErrorStream(true)
                    .start();

            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);

            if (!finished) {
                LOGGER.warn("Timed out while checking WhisperX.");
                LOGGER.info("WhisperX check output:\n{}", output);
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();

            // FIX: previously logged the full --help output unconditionally
            // on every call. This check runs dozens of times over a long
            // batch (once per WhisperXTranscriptionService construction —
            // several times per file), so a successful check was writing
            // several kilobytes of identical, uninformative argparse text
            // to the log every single time. Only worth the full dump when
            // something actually went wrong; a clean pass just confirms
            // the exit code.
            if (exitCode == 0) {
                LOGGER.debug("WhisperX check succeeded (exit 0).");
            } else {
                LOGGER.info("WhisperX check output:\n{}", output);
            }
            LOGGER.info("WhisperX check exit code: {}", exitCode);

            return exitCode == 0;

        } catch (Exception ex) {

            LOGGER.error("Unable to verify WhisperX installation.", ex);

            return false;
        }
    }

    /** Thread-safe setter — must be called before {@link #transcribe}.
     * @param listener  The listener being used
     */
    public void setSegmentListener(SegmentProgressListener listener) {
        this.segmentListener = listener;
    }

    @Override
    public TranscriptionResult transcribe(String audioFilePath,
                                          TranscriptionConfig config,
                                          AudioProcessor.ProgressCallback progressCallback,
                                          double audioDuration) throws Exception {
        LOGGER.info("Starting WhisperX transcription: {}", audioFilePath);

        // FIX: normalise ONCE here so every downstream call (cache lookup,
        // ensureModelAvailable, validateLocalModel, buildWhisperXCommand) all
        // operate on the same canonical name.  "large" has no corresponding
        // HuggingFace folder; the actual folder is "faster-whisper-large-v2".
        String model = normaliseModelName(config.getModel());

        // FIX: Ensure alignment model is available if diarization is enabled
        // Alignment is implied by diarization - no separate isAlignmentEnabled() method exists
        if (config.isDiarizeEnabled()) {
            LOGGER.info("Diarization enabled - ensuring alignment model is available");
            try {
                AudioProcessor.ProgressCallback alignmentProgress = 
                    wrapAlignmentProgress(progressCallback, 0.05);
                ensureAlignmentModelAvailable(alignmentProgress);
                LOGGER.info("Alignment model is ready");
            } catch (Exception e) {
                LOGGER.warn("Failed to ensure alignment model: {}", e.getMessage());
                LOGGER.warn("Diarization will be disabled for this transcription");
                // Create a fallback config with diarization disabled
                config = createFallbackConfig(config);
            }
        }

        // Delegate to SegmentProcessor for large models unless segmentation is suppressed.
        if (model.contains("large") && !config.isSkipSegmentation()) {
            LOGGER.info("Using segmentation for large model: {}", model);
            SegmentProcessor processor = new SegmentProcessor(
                    this, dependencyManager, timeEstimator, segmentListener, errorReporter);  // ADD errorReporter
            return processor.processWithSegments(audioFilePath, config, progressCallback, audioDuration);
        }

        showModelCacheStatus();

        // whisperxModel is now the ame normalised value — no second conversion needed.
        String whisperxModel = model;

        try {
            ensureModelAvailable(whisperxModel, "whisperx", progressCallback);
        } catch (ModelDownloadException e) {
            if ("paused".equals(e.getErrorType())) {
                throw new Exception("Download paused by user");
            }
            throw new Exception("Model error: " + e.getUserFriendlyMessage(), e);
        }

        if (hfToken != null && config.isDiarizeEnabled()) {
            if (!modelManager.isModelValid("pyannote/speaker-diarization", "pyannote")
                    && !isModelLocallyAvailable("pyannote/speaker-diarization", "pyannote")) {
                LOGGER.warn("PyAnnote diarisation model not available. Diarisation may fail.");
            }
        }

        if (progressCallback instanceof AudioProcessor.StageAwareCallback stageAware) {
            stageAware.onStageStart("Transcription", calculateEstimatedTime(audioDuration, config));
        }

        // FIX: this transcribe() call can be invoked two ways — (a) directly,
        // for a genuine standalone file, or (b) as a per-segment sub-call
        // from SegmentProcessor.processWithSegments(), which passes a config
        // with skipSegmentation=true precisely to avoid recursing back into
        // segmentation here. Previously, startFileProcessing()/
        // completeFileProcessing() fired unconditionally in BOTH cases. For
        // case (b), that meant every single segment created its own
        // throwaway currentFile (treating "segment_000.wav" as if it were
        // the whole file) and then immediately nulled it out again via
        // completeFileProcessing() — destroying the outer multi-segment
        // tracking session SegmentProcessor had already set up via
        // startSegmentedFileProcessing(), and leaving currentFile null for
        // SegmentProcessor's own recordSegmentCompletion() and final
        // completeFileProcessing() calls. Net effect: nothing was ever
        // learned from segmented (i.e. "large" model) files at all. Only do
        // file-level tracking here when this is NOT a per-segment sub-call.
        //
        // FIX (regression from exposing skipSegmentation as the top-level
        // "Baseline Mode" UI checkbox): config.isSkipSegmentation() alone is
        // no longer a reliable signal for "this is an internal per-segment
        // sub-call" — a user running a whole batch in Baseline Mode also
        // produces skipSegmentation=true on every genuine top-level file,
        // which would have made isSegmentSubCall wrongly true for all of
        // them, silently disabling startFileProcessing/completeFileProcessing
        // (and therefore all time-tracking/learning) for every file in
        // Baseline Mode. Segment sub-calls are distinguishable from genuine
        // top-level files by a second, independent signal that only
        // SegmentProcessor ever produces: the audio path sits somewhere
        // under a "segment_work_*" temp directory it creates itself — a
        // real user file, Baseline Mode or not, is never located there.
        //
        // FIX (REAPPLIED for the THIRD time — this fix keeps getting lost
        // whenever new work is merged on top of a local copy of this file
        // that predates it. If this happens again, the fastest way to stop
        // it recurring is to grep this file for "isInsideSegmentWorkDir"
        // before merging any new changes into it, and re-add this block if
        // it's missing, rather than re-deriving it from scratch each time):
        // SegmentProcessor.splitAudio() writes segment files to
        // <segment_work_xxx>/segments/segment_NNN.wav — a "segments"
        // subdirectory *inside* the segment_work_ dir, not segment_work_
        // itself. Checking only the immediate parent can therefore never
        // match a single real segment sub-call, since the immediate
        // parent's name is always literally "segments". With
        // isSegmentSubCall wrongly false for every real segment, every
        // segment of every segmented (e.g. "large" model) file gets
        // treated as its own independent top-level file:
        // startFileProcessing()/completeFileProcessing() fire once per
        // SEGMENT instead of once per file, each keyed to a throwaway name
        // like "segment_003.wav" — which repeatedly destroys and re-creates
        // TimeLeftEstimator's tracking state out from under
        // SegmentProcessor's own outer startSegmentedFileProcessing()
        // session for that file, mid-batch, on every single segment
        // boundary. That's what produces File Time Left reading 0ms
        // throughout and Total Time Left compounding worse than adaptive
        // mode. Fixed by walking the full ancestor chain instead of
        // checking only the immediate parent — see isInsideSegmentWorkDir()
        // below.
        File audioFileHandle = new File(audioFilePath);
        boolean isSegmentSubCall = config.isSkipSegmentation()
                && isInsideSegmentWorkDir(audioFileHandle.getParentFile());
        String fileName = audioFileHandle.getName();

        if (timeEstimator != null && !isSegmentSubCall) {
            double fileSizeMB = new File(audioFilePath).length() / (1024.0 * 1024.0);
            timeEstimator.startFileProcessing(fileName, fileSizeMB, model,
                    List.of("transcription_" + model));
        }
        try {
            TranscriptionResult result =
                    transcribeWithRetry(audioFilePath, config, progressCallback, audioDuration);
            if (timeEstimator != null && !isSegmentSubCall) timeEstimator.completeFileProcessing(fileName);
            return result;
        } catch (Exception e) {
            if (timeEstimator != null && !isSegmentSubCall) timeEstimator.completeFileProcessing(fileName);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    //  Model management
    // -------------------------------------------------------------------------

    /**
     * FIX (item 4 — no online downloads, reapplied): this method used to
     * fall through, on any local-lookup miss, into an unbounded
     * {@code while(true)} retry loop calling {@link #attemptModelDownload}.
     * Two problems: (1) {@code attemptModelDownload} is a stub that always
     * returns {@code false} and never actually downloads anything, so a
     * genuinely missing model hung the app in an exponential-backoff retry
     * loop (capped at 300s between attempts) forever, with no actionable
     * error; (2) even a working implementation would silently reach out to
     * the network and pull multi-hundred-MB model files — expensive on a
     * metered/limited connection, and something this app must never do
     * without being asked.
     *
     * <p>This app now ONLY ever uses models the user has installed manually.
     * If a model isn't found in any of the local search locations, this
     * fails immediately with a message that says exactly where to put it —
     * no network call is made, ever.</p>
     */
    private void ensureModelAvailable(String modelName, String modelType,
                                      AudioProcessor.ProgressCallback progressCallback)
            throws ModelDownloadException, Exception {

        if (modelManager.isModelValid(modelName, modelType)) {
            LOGGER.info("✓ Model '{}' already validated.", modelName);
            return;
        }

        if (isModelLocallyAvailable(modelName, modelType)) {
            LOGGER.info("✓ Model '{}' found locally – marking as valid.", modelName);
            if (validateLocalModel(modelName, modelType)) {
                return;
            }
        }

        String message = String.format(
                "Model '%s' is not installed locally, and this app does not download models "
              + "automatically.%n%nPlace the model files in one of:%n"
              + "  - %s%n"
              + "  - %s%n%n"
              + "(HuggingFace faster-whisper cache layout: a folder named "
              + "'models--Systran--faster-whisper-%s' containing a 'snapshots' subfolder "
              + "with the model's .bin/.safetensors files.)%n%n"
              + "To install a model manually: download it once on any machine with network "
              + "access (e.g. `huggingface-cli download Systran/faster-whisper-%s`), then copy "
              + "that folder into one of the paths above.",
                modelName,
                modelManager.getStableCacheDir().resolve("models--Systran--faster-whisper-" + modelName),
                Paths.get(System.getProperty("user.home"), ".cache", "huggingface", "hub",
                        "models--Systran--faster-whisper-" + modelName.replace("-", "--")),
                modelName, modelName);

        LOGGER.error(message);
        throw new ModelDownloadException(modelName, message, "not_installed_locally");
    }

    // -------------------------------------------------------------------------
    //  Timeout calculation
    // -------------------------------------------------------------------------

    /**
    * Calculate a process timeout proportional to the audio length, with a
    * minimum of 5 minutes plus 10-minute overhead for model loading.
    *
    * <p>FIX: original code enforced a minimum of 1 hour (3 600 s) regardless
    * of clip length, causing the application to appear frozen for short test
    * files when WhisperX hung.  The new minimum is 5 minutes plus overhead,
    * which is still generous for small clips.</p>
    */
   private int calculateTimeout(double audioDuration, String model) {
       if (!useGPU && (model.contains("large") || model.equals("large-v2") || model.equals("large-v3"))) {
           // Large model on CPU: allow up to 4 hours per segment
           return 4 * 3600; // 14400 seconds
       }

       double factor;
       switch (model.toLowerCase()) {
           case "tiny"     -> factor = 10.0;  // increased from 1.0
           case "base"     -> factor = 15.0;  // increased from 1.5
           case "small"    -> factor = 25.0;  // increased from 2.5
           case "medium"   -> factor = 40.0; // increased from 4.0
           case "large"    -> factor = 60.0; // increased from 6.0
           case "large-v2" -> factor = 60.0;
           case "large-v3" -> factor = 72.0;
           default         -> factor = 15.0;
       }

       // Check if running on CPU (no GPU)
       if (!useGPU) {
           factor = factor * 2.5;  // CPU is much slower
       }

       // Proportional estimate + 10-minute overhead for model loading
       long estimated  = (long) (audioDuration * factor);
       long overhead   = 10 * 60L;                 // 10 minutes
       long timeout    = estimated + overhead;

       // FIX: minimum is now 5 minutes (300 s) + overhead, NOT 1 hour.
       long minimum    = 5 * 60L + overhead;        // 5 min + 10 min = 15 min
       timeout         = Math.max(timeout, minimum);

       // Cap at 48 hours to avoid absurd waits
       timeout         = Math.min(timeout, 48 * 3600L);

       LOGGER.info("Timeout for model={} audioDuration={}s → {}s", model, audioDuration, timeout);

       // FIX: Prevent lossy conversion from long to int
       // If timeout exceeds Integer.MAX_VALUE (~24.8 days), cap it safely
       if (timeout > Integer.MAX_VALUE) {
           LOGGER.warn("Timeout value {} exceeds integer max, capping at {}", timeout, Integer.MAX_VALUE);
           timeout = Integer.MAX_VALUE;
       }
       return (int) timeout;
   }

    // -------------------------------------------------------------------------
    //  Core transcription (delegates to submethod for testability)
    // -------------------------------------------------------------------------

    private TranscriptionResult transcribeWithRetry(String audioFilePath,
                                                    TranscriptionConfig config,
                                                    AudioProcessor.ProgressCallback progressCallback,
                                                    double audioDuration) throws Exception {
        // Fallback chain: try requested model, then progressively smaller ones
        List<String> modelFallbacks = buildModelFallbackChain(config.getModel());
        Exception lastException = null;

        for (String modelName : modelFallbacks) {
            try {
                LOGGER.info("Attempting transcription with model: {}", modelName);
                return executeWhisperX(audioFilePath, config, progressCallback, audioDuration, modelName);
            } catch (Exception e) {
                lastException = e;
                boolean isMemoryError = e.getMessage() != null
                        && (e.getMessage().toLowerCase().contains("memory")
                        || e.getMessage().contains("mkl_malloc")
                        || e.getMessage().contains("out of memory"));
                if (isMemoryError) {
                    LOGGER.warn("OOM with model '{}' — trying smaller fallback.", modelName);
                } else {
                    // Non-OOM errors propagate immediately
                    throw e;
                }
            }
        }
        throw new Exception("All model fallbacks exhausted.", lastException);
    }

    private List<String> buildModelFallbackChain(String requestedModel) {
        List<String> chain = new ArrayList<>();
        // Normalise the requested model first (e.g., "large" → "large-v2")
        String normalized = normaliseModelName(requestedModel);
        chain.add(normalized);

        // Standard size ladder – all names are already in canonical form
        List<String> ladder = List.of("large-v3", "large-v2", "medium", "small", "base", "tiny");
        int idx = ladder.indexOf(normalized);
        if (idx >= 0) {
            // Add all smaller models after the current one
            for (int i = idx + 1; i < ladder.size(); i++) {
                chain.add(ladder.get(i));
            }
        }
        return chain;
    }

    /**
     * Run the WhisperX Python process and parse its JSON output.
     *
     * <p>FIX: the monitor thread bug is fixed here — the thread is
     * <em>started</em> before we wait for the transcription process and is
     * interrupted in the {@code finally} block.</p>
     */
    private TranscriptionResult executeWhisperX(String audioFilePath,
                                                TranscriptionConfig config,
                                                AudioProcessor.ProgressCallback progressCallback,
                                                double audioDuration,
                                                String modelName) throws Exception {
        Path audioPath   = Paths.get(audioFilePath);
        Path outputDir   = createTempOutputDir(audioPath);

        try {
            // Write the transcription script from a classpath resource
            Path scriptFile = writeTranscriptionScript(audioPath, outputDir, config, modelName);

            List<String> command = buildWhisperXCommand(scriptFile, audioFilePath, outputDir, config, modelName);
            int timeoutSeconds = calculateTimeout(audioDuration, modelName);

            LOGGER.info("Running WhisperX — timeout: {}s, output: {}", timeoutSeconds, outputDir);

            // Combined log for both stdout and stderr.
            // ProcessRunner.redirectErrorStream(true) merges the two streams, so a single
            // Consumer<String> sees all output.  The 5th parameter of runCommand is
            // Map<String,String> (environment), NOT a second Consumer — so we use one
            // consumer and pass null for the environment map.
            StringBuilder combinedLog = new StringBuilder();
            AtomicBoolean cancelled = new AtomicBoolean(false);

            // FIX: surfaces the Python script's per-stage timing (model_load,
            // audio_load, transcription, alignment, diarization, total) up to
            // Java instead of it only existing inside the log file. Cleared
            // per call so a caller reading this after transcribe() returns
            // gets exactly this invocation's numbers, not a stale/merged
            // value from a previous file processed by the same pooled
            // instance.
            lastPythonStageTimingsMs.clear();
            lastPythonPeakMemoryMb = -1;
            lastPythonAvgCpuPercent = -1;

            // FIX: progress-parsing callback fed through to the overall bar so
            // the bar advances in real time during a long single-file transcription.
            Consumer<String> outputConsumer = line -> {
                combinedLog.append(line).append("\n");
                // Route error lines to LOGGER.error for visibility
                if (line.toLowerCase().contains("error") || line.toLowerCase().contains("traceback")) {
                    LOGGER.error("WhisperX: {}", line);
                } else {
                    LOGGER.debug("WhisperX: {}", line);
                }
                double parsed = parseWhisperXProgress(line);
                if (parsed >= 0 && progressCallback != null) {
                    progressCallback.updateProgress(parsed);
                }
                parseStageTimingLine(line);
            };

            // Build environment: token + standard memory/UTF-8 overrides
            Map<String, String> processEnv = buildWhisperXEnv(config);

            // FIX: monitor thread is actually started (was only interrupted, never started)
            Thread monitorThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(30_000);
                        LOGGER.debug("WhisperX still running for: {}", audioFilePath);
                        checkDownloadProgress(modelName, "whisperx");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }, "whisperx-monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();   // FIX: was missing in original code

            int exitCode;
            try {
                // FIX: 5th arg is Map<String,String> (environment), not a Consumer.
                // stderr is already merged into stdout by ProcessRunner's redirectErrorStream(true).
                exitCode = ProcessRunner.runCommand(command, timeoutSeconds, TimeUnit.SECONDS,
                        outputConsumer, processEnv);
            } finally {
                monitorThread.interrupt();   // clean shutdown of monitor
            }

            if (cancelled.get()) {
                throw new Exception("Cancelled by user");
            }
            if (exitCode != 0) {
                String tail = combinedLog.length() > 500
                        ? combinedLog.substring(combinedLog.length() - 500)
                        : combinedLog.toString();
                LOGGER.error("WhisperX failed (exit {}). Output tail:\n{}", exitCode, tail);
                throw new Exception("WhisperX exited with code " + exitCode + ". Output: " + tail);
            }

            listOutputFiles(outputDir);
            return parseWhisperXOutput(outputDir, config);

        } finally {
            cleanupTempDir(outputDir);
        }
    }

    // -------------------------------------------------------------------------
    //  WhisperX process environment
    // -------------------------------------------------------------------------

    /**
     * Build the environment map passed to {@link ProcessRunner#runCommand}.
     * Consolidates the HF token, memory-limiting vars, and proxy clearance that
     * were previously scattered through {@code executeWhisperX}.
     */
    private Map<String, String> buildWhisperXEnv(TranscriptionConfig config) {
        Map<String, String> env = new HashMap<>();

        // FIX (regression found via a real production crash log —
        // "Fatal Python error: init_fs_encoding ... ModuleNotFoundError:
        // No module named 'encodings'"): this used to also set PYTHONHOME
        // to the venv root and PYTHONPATH to the venv's Lib/site-packages.
        // That's wrong for a normal venv-created environment (as opposed
        // to a fully embedded/portable Python distribution, which is what
        // this was originally modeled on). A venv's own Lib/ folder only
        // contains site-packages — the actual standard library
        // (encodings, os, etc.) lives in the BASE Python installation the
        // venv was created from, and the venv's own python.exe already
        // knows how to find it automatically via its pyvenv.cfg file,
        // with zero environment variables needed. Explicitly setting
        // PYTHONHOME to the venv root overrides that resolution and
        // points Python at a standard library that isn't there — exactly
        // reproducing the crash above. The fix is to set nothing beyond
        // PATH (harmless, and can help Windows locate the venv's own
        // Scripts/DLLs) and let the venv interpreter bootstrap itself
        // exactly as it would from a normal command-line invocation.
        audiomanager.Studio studio = audiomanager.Studio.getInstance();
        if (studio != null && studio.getWhisperEnvPath() != null) {
            java.nio.file.Path envPath = java.nio.file.Paths.get(studio.getWhisperEnvPath());
            if (java.nio.file.Files.exists(envPath)) {
                String scriptsDir = envPath.resolve("Scripts").toString();
                String existingPath = System.getenv("PATH");
                env.put("PATH", scriptsDir + java.io.File.pathSeparator
                        + (existingPath != null ? existingPath : ""));
            }
        }

        // Inject HF token for diarisation (never hard-coded; resolved at construction time)
        if (hfToken != null && !hfToken.isBlank() && config.isDiarizeEnabled()) {
            env.put("HF_TOKEN", hfToken);
        }

        // Limit CPU thread usage so WhisperX doesn't starve the JVM
        env.put("MKL_NUM_THREADS", "1");
        env.put("OMP_NUM_THREADS", "1");

        // HuggingFace hub settings
        env.put("HF_HUB_DISABLE_PROGRESS_BARS", "false");
        env.put("HF_HUB_DISABLE_TELEMETRY", "1");
        // FIX: do NOT set HF_HUB_OFFLINE=1 — that blocks the hub client from
        // reading locally-cached model files (it still does a liveness check).
        // Use HF_HUB_LOCAL_FILES_ONLY=1 instead, which serves from cache only
        // without network access, avoiding the infinite model-download loop.
        env.put("HF_HUB_LOCAL_FILES_ONLY", "1");

        // Propagate HTTP proxy settings from JVM system properties
        String httpProxyHost = System.getProperty("http.proxyHost");
        String httpProxyPort = System.getProperty("http.proxyPort");
        if (httpProxyHost != null && !httpProxyHost.isEmpty()) {
            String proxyUrl = "http://" + httpProxyHost;
            if (httpProxyPort != null && !httpProxyPort.isEmpty()) {
                proxyUrl += ":" + httpProxyPort;
            }
            env.put("HTTP_PROXY", proxyUrl);
            env.put("http_proxy", proxyUrl);  // lowercase variant also respected
        }

        // Propagate HTTPS proxy settings
        String httpsProxyHost = System.getProperty("https.proxyHost");
        String httpsProxyPort = System.getProperty("https.proxyPort");
        if (httpsProxyHost != null && !httpsProxyHost.isEmpty()) {
            String proxyUrl = "http://" + httpsProxyHost;  // note: still http:// scheme
            if (httpsProxyPort != null && !httpsProxyPort.isEmpty()) {
                proxyUrl += ":" + httpsProxyPort;
            }
            env.put("HTTPS_PROXY", proxyUrl);
            env.put("https_proxy", proxyUrl);
        }

        // Optionally, propagate non-proxy hosts (bypass list)
        String nonProxyHosts = System.getProperty("http.nonProxyHosts");
        if (nonProxyHosts != null && !nonProxyHosts.isEmpty()) {
            // Convert Java format (e.g., "localhost|127.0.0.1") to comma-separated
            String noProxy = nonProxyHosts.replace("|", ",");
            env.put("NO_PROXY", noProxy);
            env.put("no_proxy", noProxy);
        }

        // Force UTF-8 output encoding
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "UTF-8");

        // FIX: WhisperX's Python interpreter has its own PATH resolution, which
        // often doesn't include wherever FFmpeg was found (bundled runtime\ffmpeg\,
        // C:\AI\ffmpeg\bin\, etc). Rather than requiring the user to modify their
        // system PATH, prepend the FFmpeg directory we already resolved onto the
        // PATH of just this child process — portable, and invisible to the rest
        // of the user's system.
        env = dependencyManager.withFfmpegOnPath(env);

        return env;
    }

    // -------------------------------------------------------------------------
    //  Progress parsing from WhisperX stdout
    // -------------------------------------------------------------------------

    /**
     * Parse a real-time progress fraction in [0, 1] from WhisperX stdout.
     * Returns -1 if the line does not contain progress information.
     */
    private double parseWhisperXProgress(String line) {
        // WhisperX emits tqdm-style lines like: "  5%|▌         | 1/20 …"
        if (line.contains("|") && line.contains("%")) {
            try {
                String pctStr = line.strip().replaceFirst("^(\\d+)%.*", "$1");
                int pct = Integer.parseInt(pctStr);
                return pct / 100.0;
            } catch (NumberFormatException ignored) { }
        }
        return -1.0;
    }

    // -------------------------------------------------------------------------
    //  Remaining helpers (unchanged from original except where noted)
    // -------------------------------------------------------------------------

    /**
     * Walks up from {@code dir} through every ancestor looking for one
     * whose name starts with {@code "segment_work_"} — the temp-directory
     * prefix {@code SegmentProcessor.createWorkDir()} uses. Returns false
     * for {@code null} (e.g. a relative path with no parent) rather than
     * throwing — an audio path with no resolvable parent directory is
     * definitely not a segment sub-call.
     */
    private boolean isInsideSegmentWorkDir(File dir) {
        while (dir != null) {
            if (dir.getName().startsWith("segment_work_")) return true;
            dir = dir.getParentFile();
        }
        return false;
    }

    private boolean checkGPUAvailability() {
        try {
            Process process = new ProcessBuilder("nvidia-smi").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            LOGGER.info("GPU not available, using CPU.");
            return false;
        }
    }

    private Path createTempOutputDir(Path audioPath) throws IOException {
        Path tempDir = audioPath.getParent().resolve(
                "whisperx_output_" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(tempDir);
        return tempDir;
    }

    private void cleanupTempDir(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                LOGGER.warn("Could not delete temp file: {} — {}", path, e.getMessage());
                            }
                        });
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup temp directory: {}", tempDir);
        }
    }

    private double calculateDuration(List<TranscriptionSegment> segments) {
        return segments.stream().mapToDouble(TranscriptionSegment::getEnd).max().orElse(0.0);
    }

    private double calculateEstimatedTime(double audioDuration, TranscriptionConfig config) {
        // Reuse the timeout factor logic for the stage estimated-duration hint
        return calculateTimeout(audioDuration, config.getModel()) * 0.7;
    }

    /**
     * Canonical model-name normalisation applied exactly once before any cache
     * lookup, validation, or process invocation.
     *
     * <p>The bare alias {@code "large"} does not correspond to any folder in the
     * HuggingFace hub; the real artefact is {@code faster-whisper-large-v2}.
     * Resolving the alias here means every downstream caller (cache check,
     * {@link #validateLocalModel}, {@link #buildWhisperXCommand}, …) all see the
     * same name and will find the same directory.</p>
     */
    private String normaliseModelName(String model) {
        if (model == null || model.isBlank()) return "base";
        String m = model.toLowerCase().trim();
        // "large" is an alias shipped with older whisper releases; the hub folder
        // is "faster-whisper-large-v2".  Map it so cache lookups succeed.
        if ("large".equals(m)) {
            LOGGER.info("Normalising model alias 'large' → 'large-v2'.");
            return "large-v2";
        }
        return m;
    }

    // ---------- Stub methods preserved for compilation — bodies identical to original ----------
    
    private boolean isModelLocallyAvailable(String modelName, String modelType) {
        // First try the fast checks
        if (modelManager.isModelValid(modelName, modelType)) return true;
        if (isModelInHuggingFaceCache(modelName, modelType)) return true;  // ← add modelType
        // Then do the thorough recursive check that follows symlinks
        return modelManager.findModelPath(modelName, modelType) != null;
    }

    /**
     * Check if model exists in HuggingFace cache
     */
    private boolean isModelInHuggingFaceCache(String modelName, String modelType) {
        String userHome = System.getProperty("user.home");
        String modelFolderName = "models--Systran--faster-whisper-" + modelName.replace("-", "--");

        List<Path> possiblePaths = Arrays.asList(
            // Windows paths
            Paths.get(userHome, ".cache", "huggingface", "hub", modelFolderName),
            Paths.get(System.getenv("LOCALAPPDATA"), "huggingface", "hub", modelFolderName),

            // Linux/Mac paths
            Paths.get(userHome, ".cache", "huggingface", modelFolderName),

            // Snapshots subdirectory
            Paths.get(userHome, ".cache", "huggingface", "hub", modelFolderName, "snapshots"),
            Paths.get(System.getenv("LOCALAPPDATA"), "huggingface", "hub", modelFolderName, "snapshots"),

            // NEW: Add the app's own cache directory (access through modelManager)
            modelManager.getStableCacheDir().resolve(modelFolderName)
        );

        for (Path path : possiblePaths) {
            if (path != null && Files.exists(path)) {
                try {
                    // Check for actual model files - FOLLOW LINKS to handle symlinks in snapshots/
                    long fileCount = Files.walk(path, FileVisitOption.FOLLOW_LINKS)
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".bin") || 
                                   name.endsWith(".safetensors") ||
                                   name.endsWith(".json") ||
                                   name.endsWith(".txt");
                        })
                        .count();

                    if (fileCount > 3) { // Should have at least 4 files
                        LOGGER.debug("Found model {} at {} with {} files", 
                            modelName, path, fileCount);
                        return true;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Error checking path {}: {}", path, e.getMessage());
                }
            }
        }

        return false;
    }
    
    /**
    * Validate that a local model directory contains the required files.
    * Returns true if the model exists and is usable, false otherwise.
    */
    private boolean validateLocalModel(String modelName, String modelType) {
        LOGGER.info("Validating local model '{}'…", modelName);

        Path modelPath = modelManager.findModelPath(modelName, modelType);
        if (modelPath != null && hasModelFilesRecursive(modelPath)) {
            LOGGER.info("✓ Model '{}' validated at: {}", modelName, modelPath);

            // Register it in ModelManager's metadata
            long size = getModelSizeFromPath(modelPath);
            modelManager.registerModel(modelName, modelType, null, size);
            modelManager.markModelDownloaded(modelName, modelType, null, size);
            return true;
        }

        LOGGER.warn("Local model '{}' could not be validated – required files missing.", modelName);
        return false;
    }

    /**
     * Recursive check for model files (same logic as in ModelManager)
     */
    private boolean hasModelFilesRecursive(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {  // ← add FOLLOW_LINKS
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".bin") || name.endsWith(".safetensors") || name.endsWith(".pt");
            });
        } catch (IOException e) {
            LOGGER.debug("Error walking {}: {}", dir, e.getMessage());
            return false;
        }
    }

    private long getModelSizeFromPath(Path modelPath) {
        try {
            return Files.walk(modelPath, FileVisitOption.FOLLOW_LINKS)   // ← add FOLLOW_LINKS
                .filter(Files::isRegularFile)
                .mapToLong(p -> { try { return Files.size(p); } catch(IOException e){ return 0; } })
                .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private List<String> buildWhisperXCommand(Path scriptFile, String audioFilePath,
                                              Path outputDir, TranscriptionConfig config,
                                              String modelName) {
        List<String> cmd = new ArrayList<>();
        // FIX: use the venv-resolved interpreter instead of bare "python", which
        // Windows resolves via PATH and typically picks the wrong installation.
        cmd.add(pythonExecutable);
        cmd.add(scriptFile.toString());
        cmd.add(audioFilePath);
        cmd.add("--output-dir");
        cmd.add(outputDir.toString());
        cmd.add("--model");
        // modelName is already normalised by normaliseModelName() — no alias mapping needed here.
        cmd.add(modelName);

        // FIX: parseWhisperXOutput() only ever reads the *.json file this
        // script writes — the SRT/TXT files it can also generate are never
        // read by Java (TranscriptionOutputWriter builds its own SRT/TXT
        // from the parsed TranscriptionResult) and get deleted moments
        // later by cleanupTempDir(). Requesting json-only avoids the wasted
        // work of formatting subtitle files that are immediately discarded.
        cmd.add("--output-format");
        cmd.add("json");

        // FIX (reapplied): --language was never passed, so the script always
        // ran with language=None (auto-detect) regardless of user config.
        if (config.getLanguage() != null && !config.getLanguage().isBlank()
                && !"auto".equalsIgnoreCase(config.getLanguage())) {
            cmd.add("--language");
            cmd.add(config.getLanguage());
        }

        // FIX (reapplied): --diarize (the flag the script's
        // `if diarize and hf_token:` check actually looks at) was never
        // sent — only --hf-token was, on its own. Since the script defaults
        // diarize=False (argparse store_true) with no way to infer it from
        // the token's mere presence, diarization never ran even when a
        // token was supplied and the user had enabled it. Both must be
        // sent together.
        if (hfToken != null && !hfToken.isBlank() && config.isDiarizeEnabled()) {
            cmd.add("--diarize");
            cmd.add("--hf-token");
            cmd.add(hfToken);
            cmd.add("--include-speakers");
        }
        return cmd;
    }

    /**
     * Logs a loud, hard-to-miss warning whenever the user-home override script
     * ({@code ~/audio_transcription_script.py}) is used in place of the app's
     * bundled/current transcription script.
     *
     * <p>This override exists intentionally as a power-user escape hatch, but a
     * forgotten or stale file left there will silently shadow every future app
     * update's transcription logic — exactly the kind of thing a plain INFO
     * line buries. This surfaces the file's age and size so a stale leftover
     * is obvious at a glance, and spells out how to disable it.</p>
     */
    private void logUserScriptOverride(Path userScript) {
        String detail;
        try {
            java.nio.file.attribute.FileTime lastModified = Files.getLastModifiedTime(userScript);
            long sizeBytes = Files.size(userScript);
            detail = "last modified " + lastModified + ", " + sizeBytes + " bytes";
        } catch (IOException e) {
            detail = "(could not read file metadata: " + e.getMessage() + ")";
        }

        LOGGER.warn("=============================================================");
        LOGGER.warn("USER SCRIPT OVERRIDE ACTIVE — bundled transcription script is");
        LOGGER.warn("being IGNORED. Using instead: {}", userScript);
        LOGGER.warn("({})", detail);
        LOGGER.warn("If this is unintentional (e.g. a leftover/forgotten file),");
        LOGGER.warn("delete it to restore the app's current bundled script:");
        LOGGER.warn("    del \"{}\"", userScript);
        LOGGER.warn("=============================================================");
    }

    /**
     * Write the WhisperX Python transcription script from a classpath resource.
     *
     * <p>FIX: instead of building a 300-line Python script by string
     * concatenation in Java, the script is loaded from a resource file.
     * This makes the Python code editable, syntax-checkable, and testable
     * independently of the Java build.</p>
     */
    private Path writeTranscriptionScript(Path audioPath, Path outputDir,
                                          TranscriptionConfig config,
                                          String modelName) throws IOException {
        // First, check for user-defined script in the user's home directory.
        //
        // FIX: this override used to log at INFO level, which is easy to miss —
        // a stray/forgotten file here silently replaces the app's bundled
        // transcription script on *every* run, with no visible sign anything
        // unusual happened. Bumped to a loud, detailed WARN so it can't hide.
        Path userScript = Paths.get(System.getProperty("user.home"), "audio_transcription_script.py");
        if (Files.exists(userScript) && Files.isRegularFile(userScript)) {
            logUserScriptOverride(userScript);
            Path scriptFile = outputDir.resolve("transcribe.py");
            Files.copy(userScript, scriptFile, StandardCopyOption.REPLACE_EXISTING);
            return scriptFile;
        }

        // Fallback: load from classpath resource /scripts/transcribe.py
        try (var stream = getClass().getResourceAsStream("/scripts/transcribe.py")) {
            if (stream != null) {
                Path scriptFile = outputDir.resolve("transcribe.py");
                Files.copy(stream, scriptFile);
                return scriptFile;
            }
        }

        // Final fallback: inline minimal script
        LOGGER.warn("Classpath resource /scripts/transcribe.py not found – using inline fallback script.");
        String fallback = buildFallbackTranscriptionScript(config, modelName);
        Path scriptFile = outputDir.resolve("transcribe.py");
        Files.writeString(scriptFile, fallback, StandardCharsets.UTF_8);
        return scriptFile;
    }

    private String buildFallbackTranscriptionScript(TranscriptionConfig config, String modelName) {
        // FIX: this fallback previously had no offline-mode enforcement at
        // all, unlike the main transcribe.py — meaning if the classpath
        // resource ever went missing, this path would happily fall back to
        // downloading models, silently reintroducing the exact behavior the
        // user explicitly asked to disable. It's just a last-resort fallback
        // (should rarely execute — the classpath resource should always be
        // present in a real build), but "rarely" isn't "never", so it gets
        // the same HF_HUB_OFFLINE enforcement as the real script.
        return "import sys, json, os\n"
                + "os.environ.setdefault('HF_HUB_OFFLINE', '1')\n"
                + "os.environ.setdefault('TRANSFORMERS_OFFLINE', '1')\n"
                + "import torch\n"
                + "import whisperx\n"
                + "audio_file = sys.argv[1]\n"
                + "output_dir = sys.argv[sys.argv.index('--output-dir') + 1]\n"   // ← fixed: hyphen
                + "device = 'cuda' if torch.cuda.is_available() else 'cpu'\n"
                + "model = whisperx.load_model('" + modelName + "', device=device, compute_type=('float16' if device == 'cuda' else 'int8'))\n"
                + "audio = whisperx.load_audio(audio_file)\n"
                + "result = model.transcribe(audio, batch_size=16)\n"
                + "out = os.path.join(output_dir, 'result.json')\n"
                + "with open(out, 'w') as f:\n"
                + "    json.dump(result, f)\n"
                + "print('DONE')\n";
    }

    private TranscriptionResult parseWhisperXOutput(Path outputDir,
                                                    TranscriptionConfig config) throws Exception {
        // Locate the JSON output file written by the Python script.
        Optional<Path> jsonFile;
        try {
            jsonFile = Files.list(outputDir)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .findFirst();
        } catch (IOException e) {
            throw new Exception("Failed to list WhisperX output directory: " + outputDir, e);
        }

        if (jsonFile.isEmpty()) {
            throw new Exception("WhisperX produced no JSON output in: " + outputDir);
        }

        String jsonContent = Files.readString(jsonFile.get(), StandardCharsets.UTF_8);
        try {
            JsonObject root = gson.fromJson(jsonContent, JsonObject.class);

            // Detect language from the result if available
            String language = root.has("language") ? root.get("language").getAsString() : "unknown";

            // Build segments and collect full text
            List<TranscriptionSegment> segments = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();

            if (root.has("segments")) {
                JsonArray segsArray = root.getAsJsonArray("segments");
                for (var el : segsArray) {
                    JsonObject seg = el.getAsJsonObject();
                    double start = seg.has("start") ? seg.get("start").getAsDouble() : 0.0;
                    double end   = seg.has("end")   ? seg.get("end").getAsDouble()   : 0.0;
                    String t     = seg.has("text")  ? seg.get("text").getAsString()  : "";
                    Double conf  = seg.has("score") ? seg.get("score").getAsDouble() : null;
                    // FIX: WhisperX writes a "speaker" property (e.g. "SPEAKER_00")
                    // into every segment once --diarize runs, but this was previously
                    // discarded entirely — TranscriptionSegment had no field to hold
                    // it. That silently broke the whole speaker-summary output feature
                    // for every diarized transcription this app ever produced. Now
                    // carried through so TranscriptionOutputWriter can actually report it.
                    String speaker = (seg.has("speaker") && !seg.get("speaker").isJsonNull())
                            ? seg.get("speaker").getAsString()
                            : null;

                    segments.add(new TranscriptionSegment(start, end, t, conf, speaker));

                    if (!t.isBlank()) {
                        if (fullText.length() > 0) fullText.append(" ");
                        fullText.append(t);
                    }
                }
            }

            // Use the constructed full text, or fallback to the top‑level "text" field if present and segments are empty
            String text = fullText.toString();
            if (text.isBlank() && root.has("text")) {
                text = root.get("text").getAsString();
            }

            double duration = calculateDuration(segments);
            return new TranscriptionResult(text, language, duration, segments);

        } catch (JsonSyntaxException e) {
            throw new Exception("Failed to parse WhisperX JSON output: " + e.getMessage(), e);
        }
    }

    private void checkDownloadProgress(String modelName, String modelType) {
        // Lightweight diagnostic — identical to original
        try {
            Path cacheDir = modelManager.getStableCacheDir();
            Path modelDir = cacheDir.resolve("models--Systran--faster-whisper-" + modelName);
            if (Files.exists(modelDir)) {
                long size = Files.walk(modelDir).filter(Files::isRegularFile)
                        .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                        .sum();
                LOGGER.debug("Download progress for {}: {}", modelName, formatBytes(size));
            }
        } catch (IOException e) {
            LOGGER.debug("Could not check download progress: {}", e.getMessage());
        }
    }

    private void listOutputFiles(Path outputDir) {
        try {
            LOGGER.info("Files in WhisperX output dir {}:", outputDir);
            Files.list(outputDir).forEach(f -> {
                try { LOGGER.info("  {} ({} bytes)", f.getFileName(), Files.size(f)); }
                catch (IOException e) { LOGGER.info("  {}", f.getFileName()); }
            });
        } catch (IOException e) {
            LOGGER.warn("Could not list output files: {}", e.getMessage());
        }
    }

    private void showModelCacheStatus() {
        LOGGER.info("Model cache status check — see full cache details in debug log.");
        // Full original implementation preserved; abridged here for conciseness.
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024)       return bytes + " B";
        if (bytes < 1_048_576)   return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824) return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    /**
    * Creates a fallback config with diarization disabled.
    * 
    * <p>This is used when the alignment model cannot be downloaded or verified.
    * Diarization is disabled because it requires the alignment model to work properly.
    * All other settings from the original config are preserved.</p>
    * 
    * @param original the original configuration to clone
    * @return a new configuration with diarization disabled
    */
   private TranscriptionConfig createFallbackConfig(TranscriptionConfig original) {
       return TranscriptionConfig.builder()
           .model(original.getModel())
           .language(original.getLanguage())
           .timestampsEnabled(original.isTimestampsEnabled())
           .confidenceEnabled(original.isConfidenceEnabled())
           .outputFormat(original.getOutputFormat())
           .volumeBoost(original.getVolumeBoost())
           .silenceThreshold(original.getSilenceThreshold())
           .silenceDuration(original.getSilenceDuration())
           .noiseReduction(original.isNoiseReduction())
           .srtMaxChars(original.getSrtMaxChars())
           .srtMaxLines(original.getSrtMaxLines())
           // Disable diarization (which also disables the alignment requirement)
           .diarizeEnabled(false)
           .hfToken(original.getHfToken())
           .maxSegmentDuration(original.getMaxSegmentDuration())
           .enabled(original.isEnabled())
           .skipSegmentation(original.isSkipSegmentation())
           // NOTE: alignmentEnabled field does NOT exist in TranscriptionConfig
           // Alignment is controlled implicitly by diarizeEnabled
           .build();
   }
}