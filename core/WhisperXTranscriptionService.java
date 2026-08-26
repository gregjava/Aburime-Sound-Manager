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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WhisperX-backed transcription service with GPU acceleration support.
 *
 * <p>This is the core transcription service that uses WhisperX for speech-to-text
 * processing. Features include:
 * <ul>
 *   <li><b>GPU acceleration:</b> Automatically detects NVIDIA GPUs and uses CUDA</li>
 *   <li><b>Model caching:</b> Downloads and caches models locally</li>
 *   <li><b>Streaming for large files:</b> Splits files larger than 100MB into chunks</li>
 *   <li><b>Speaker diarisation:</b> Identifies different speakers when enabled</li>
 *   <li><b>Timing and resource reporting:</b> Captures per-stage timing and resource usage</li>
 *   <li><b>Retry with model fallback:</b> Tries smaller models on OOM errors</li>
 *   <li><b>Segmentation:</b> Splits long files into segments for processing</li>
 * </ul>
 *
 * <p><b>Token security:</b> The HuggingFace token is <b>never</b> embedded in source code.
 * It is read from (in priority order):
 * <ol>
 *   <li>The {@code HF_TOKEN} environment variable</li>
 *   <li>The {@code hf.token} Java system property</li>
 *   <li>The file {@code ~/.audiomanager/hf_token} (plain text, one line)</li>
 * </ol>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see TranscriptionService
 * @see ModelManager
 * @see SegmentProcessor
 * @see GpuConfig
 */
public class WhisperXTranscriptionService implements TranscriptionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WhisperXTranscriptionService.class);

    // Configuration constants
    private static final long LARGE_FILE_THRESHOLD_MB = 100;
    private static final int CHUNK_DURATION_SECONDS = 30;
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB
    private static final int MAX_RETRIES = 3;

    private final DependencyManager dependencyManager;
    private final TimeLeftEstimator timeEstimator;
    private final ErrorReporter errorReporter;
    private final AtomicLong totalSegmentsProcessed = new AtomicLong(0);
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);

    // Per-stage timing data from the most recent transcription
    private final Map<String, Long> lastPythonStageTimingsMs = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile double lastPythonPeakMemoryMb = -1;
    private volatile double lastPythonAvgCpuPercent = -1;

    private static final java.util.regex.Pattern STAGE_TIMING_PATTERN =
            java.util.regex.Pattern.compile("STAGE_TIMING:([a-zA-Z_]+):(-?[0-9.]+)");
    private static final String ALIGNMENT_MODEL_URL =
        "https://download.pytorch.org/torchaudio/models/wav2vec2_fairseq_base_ls960_asr_ls960.pth";
    private static final long ALIGNMENT_MODEL_MIN_SIZE = 300_000_000L; // 300 MB minimum
    private static final String ALIGNMENT_MODEL_FILENAME =
        "wav2vec2_fairseq_base_ls960_asr_ls960.pth";

    // GPU Support
    private final GpuConfig gpuConfig = GpuConfig.getInstance();
    private volatile boolean gpuInitialized = false;

    private final Gson gson;
    private final boolean useGPU;
    private final String hfToken;

    private final ModelManager modelManager;
    private volatile SegmentProgressListener segmentListener;
    private final String pythonExecutable;

    // ========================================================================
    //  Constructors
    // ========================================================================

    /**
     * Constructs a new WhisperXTranscriptionService.
     *
     * @param dependencyManager the dependency manager for resolving executables
     * @param errorReporter the error reporter for diagnostics (may be {@code null})
     */
    public WhisperXTranscriptionService(DependencyManager dependencyManager,
                          ErrorReporter errorReporter) {
        this(dependencyManager, null, errorReporter);
    }

    /**
     * Constructs a new WhisperXTranscriptionService.
     *
     * @param dependencyManager the dependency manager for resolving executables
     * @param timeEstimator the time estimator for progress tracking (may be {@code null})
     * @param errorReporter the error reporter for diagnostics (may be {@code null})
     */
    public WhisperXTranscriptionService(DependencyManager dependencyManager,
                                        TimeLeftEstimator timeEstimator,
                          ErrorReporter errorReporter) {
        this(dependencyManager, timeEstimator, null, errorReporter);
    }

    /**
     * Constructs a new WhisperXTranscriptionService.
     *
     * @param dependencyManager the dependency manager for resolving executables
     * @param timeEstimator the time estimator for progress tracking (may be {@code null})
     * @param listener the segment progress listener (may be {@code null})
     * @param errorReporter the error reporter for diagnostics (may be {@code null})
     */
    public WhisperXTranscriptionService(DependencyManager dependencyManager,
                                        TimeLeftEstimator timeEstimator,
                                        SegmentProgressListener listener,
                          ErrorReporter errorReporter) {
        this.dependencyManager = dependencyManager;
        this.timeEstimator     = timeEstimator;
        this.gson              = new Gson();
        this.segmentListener   = listener;
        this.errorReporter     = errorReporter;

        // Initialize GPU detection
        initGpu();

        // Determine if GPU should be used
        this.useGPU = isGpuEnabled();

        // Resolve HF token (never hard-coded)
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
        LOGGER.info("WhisperXTranscriptionService initialised — GPU: {}", useGPU ? "ENABLED" : "DISABLED");
        showModelCacheStatus();
    }

    // ========================================================================
    //  GPU Methods
    // ========================================================================

    /**
     * Initializes GPU detection and configuration.
     */
    private synchronized void initGpu() {
        if (gpuInitialized) {
            return;
        }
        gpuConfig.detectGpu();
        gpuInitialized = true;

        if (gpuConfig.isGpuAvailable()) {
            LOGGER.info("✅ GPU detected: {}", gpuConfig.getGpuSummary());
        } else {
            LOGGER.info("ℹ️ No GPU detected — running on CPU mode");
        }
    }

    /**
     * Returns the device string for WhisperX ("cuda" or "cpu").
     *
     * @return "cuda" if GPU is available and enabled, otherwise "cpu"
     */
    private String getDeviceString() {
        initGpu();
        boolean userEnabled = java.util.prefs.Preferences.userNodeForPackage(WhisperXTranscriptionService.class)
            .getBoolean("gpu.enabled", true);
        if (gpuConfig.isGpuAvailable() && userEnabled) {
            return "cuda";
        }
        return "cpu";
    }

    /**
     * Returns the compute type for WhisperX.
     *
     * @return "float16" for CUDA, "int8" for CPU
     */
    private String getComputeType() {
        String device = getDeviceString();
        return "cuda".equals(device) ? "float16" : "int8";
    }

    /**
     * Returns whether GPU acceleration is enabled and available.
     *
     * @return {@code true} if GPU is enabled
     */
    public boolean isGpuEnabled() {
        initGpu();
        boolean userEnabled = java.util.prefs.Preferences.userNodeForPackage(WhisperXTranscriptionService.class)
            .getBoolean("gpu.enabled", true);
        return gpuConfig.isGpuAvailable() && userEnabled;
    }

    /**
     * Returns a summary of GPU configuration for display.
     *
     * @return a human-readable GPU summary
     */
    public String getGpuSummary() {
        initGpu();
        return gpuConfig.getGpuSummary();
    }

    /**
     * Toggles GPU acceleration on/off.
     *
     * @param enabled {@code true} to enable GPU acceleration
     */
    public void setGpuEnabled(boolean enabled) {
        java.util.prefs.Preferences.userNodeForPackage(WhisperXTranscriptionService.class)
            .putBoolean("gpu.enabled", enabled);
        LOGGER.info("GPU acceleration {}", enabled ? "enabled" : "disabled");
    }

    // ========================================================================
    //  Timing Data Access
    // ========================================================================

    /**
     * Returns stage timings from the most recent transcription.
     *
     * @return a map of stage names to durations in milliseconds
     */
    public Map<String, Long> getLastPythonStageTimingsMs() {
        return new java.util.LinkedHashMap<>(lastPythonStageTimingsMs);
    }

    /**
     * Returns the peak memory usage from the most recent transcription.
     *
     * @return the peak memory in MB, or {@code -1} if unavailable
     */
    public double getLastPythonPeakMemoryMb() {
        return lastPythonPeakMemoryMb;
    }

    /**
     * Returns the average CPU usage from the most recent transcription.
     *
     * @return the average CPU percentage, or {@code -1} if unavailable
     */
    public double getLastPythonAvgCpuPercent() {
        return lastPythonAvgCpuPercent;
    }

    /**
     * Sets the segment progress listener.
     *
     * @param listener the segment progress listener
     */
    public void setSegmentListener(SegmentProgressListener listener) {
        this.segmentListener = listener;
    }

    // ========================================================================
    //  Main Transcription Method
    // ========================================================================

    /**
     * Transcribes an audio file using WhisperX.
     *
     * <p>This method handles:
     * <ul>
     *   <li>Large files (>100MB) via streaming/chunking</li>
     *   <li>Large models (large-v2, large-v3) via segmentation</li>
     *   <li>Diarisation via alignment model download</li>
     *   <li>Retry with model fallback on OOM errors</li>
     * </ul>
     *
     * @param audioFilePath the path to the audio file
     * @param config the transcription configuration
     * @param progressCallback the progress callback (may be {@code null})
     * @param audioDuration the total audio duration in seconds
     * @return the transcription result
     * @throws Exception if transcription fails
     */
    @Override
    public TranscriptionResult transcribe(String audioFilePath,
                                          TranscriptionConfig config,
                                          AudioProcessor.ProgressCallback progressCallback,
                                          double audioDuration) throws Exception {
        LOGGER.info("Starting WhisperX transcription: {}", audioFilePath);

        initGpu();

        String model = normaliseModelName(config.getModel());
        File audioFile = new File(audioFilePath);
        long fileSizeMB = audioFile.length() / (1024 * 1024);

        boolean useStreaming = fileSizeMB > LARGE_FILE_THRESHOLD_MB;

        if (useStreaming && !config.isSkipSegmentation()) {
            LOGGER.info("Using streaming transcription for large file: {} ({} MB)",
                       audioFile.getName(), fileSizeMB);
            return transcribeWithStreaming(audioFile, config);
        }

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
                config = createFallbackConfig(config);
            }
        }

        if (model.contains("large") && !config.isSkipSegmentation()) {
            LOGGER.info("Using segmentation for large model: {}", model);
            SegmentProcessor processor = new SegmentProcessor(
                    this, dependencyManager, timeEstimator, segmentListener, errorReporter);
            return processor.processWithSegments(audioFilePath, config, progressCallback, audioDuration);
        }

        showModelCacheStatus();

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

        File audioFileHandle = new File(audioFilePath);
        boolean isSegmentSubCall = config.isSkipSegmentation()
                && isInsideSegmentWorkDir(audioFileHandle.getParentFile());
        String fileName = audioFileHandle.getName();

        if (timeEstimator != null && !isSegmentSubCall) {
            double fileSizeMB2 = audioFileHandle.length() / (1024.0 * 1024.0);
            timeEstimator.startFileProcessing(fileName, fileSizeMB2, model,
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

    // ========================================================================
    //  Public Methods
    // ========================================================================

    /**
     * Returns the performance statistics for the service.
     *
     * @return a {@link PerformanceStats} object
     */
    public PerformanceStats getPerformanceStats() {
        return new PerformanceStats(
            totalSegmentsProcessed.get(),
            totalProcessingTimeMs.get()
        );
    }

    /**
     * Returns a detailed performance report.
     *
     * @return a formatted performance report string
     */
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== WhisperX Performance Report ===\n");
        report.append("Total segments processed: ").append(totalSegmentsProcessed.get()).append("\n");
        report.append("Total processing time: ").append(formatDuration(totalProcessingTimeMs.get())).append("\n");
        report.append("GPU enabled: ").append(isGpuEnabled()).append("\n");

        if (totalSegmentsProcessed.get() > 0) {
            double avgTime = (double) totalProcessingTimeMs.get() / totalSegmentsProcessed.get();
            report.append("Average time per segment: ").append(formatDuration((long) avgTime)).append("\n");
        }

        if (gpuConfig.isGpuAvailable()) {
            report.append("GPU: ").append(gpuConfig.getGpuName()).append("\n");
            report.append("GPU Memory: ").append(gpuConfig.getGpuMemoryMB()).append(" MB\n");
        }

        return report.toString();
    }

    // ========================================================================
    //  Inner Class: PerformanceStats
    // ========================================================================

    /**
     * Performance statistics record.
     */
    public static class PerformanceStats {
        public final long segmentsProcessed;
        public final long totalProcessingTimeMs;

        /**
         * Constructs a new PerformanceStats.
         *
         * @param segments the number of segments processed
         * @param time the total processing time in milliseconds
         */
        public PerformanceStats(long segments, long time) {
            this.segmentsProcessed = segments;
            this.totalProcessingTimeMs = time;
        }

        /**
         * Returns the average time per segment.
         *
         * @return the average time in milliseconds
         */
        public double getAverageTimePerSegmentMs() {
            return segmentsProcessed > 0 ? (double) totalProcessingTimeMs / segmentsProcessed : 0;
        }
    }

    // ========================================================================
    //  Private Helpers (Documented)
    // ========================================================================

    /**
     * Wraps a callback for alignment model download progress.
     *
     * @param parentCallback the parent callback
     * @param weight the weight of this stage in overall progress
     * @return the wrapped callback
     */
    private AudioProcessor.ProgressCallback wrapAlignmentProgress(
            AudioProcessor.ProgressCallback parentCallback, double weight) {
        if (parentCallback == null) return null;
        return p -> parentCallback.updateProgress(Math.min(1.0, p * weight));
    }

    /**
     * Ensures the alignment model is available for diarisation.
     *
     * @param progressCallback the progress callback (may be {@code null})
     * @throws Exception if the model cannot be downloaded
     */
    private void ensureAlignmentModelAvailable(AudioProcessor.ProgressCallback progressCallback)
            throws Exception {
        Path modelPath = getAlignmentModelPath();

        if (Files.exists(modelPath) && Files.size(modelPath) >= ALIGNMENT_MODEL_MIN_SIZE) {
            LOGGER.info("Alignment model found at: {} ({} MB)",
                modelPath, Files.size(modelPath) / (1024 * 1024));
            return;
        }

        if (Files.exists(modelPath)) {
            long currentSize = Files.size(modelPath);
            LOGGER.warn("Alignment model exists but is incomplete: {} bytes (expected > {} bytes)",
                currentSize, ALIGNMENT_MODEL_MIN_SIZE);
            Files.deleteIfExists(modelPath);
            LOGGER.info("Removed incomplete alignment model, will re-download");
        }

        Files.createDirectories(modelPath.getParent());

        LOGGER.info("Alignment model not found. Downloading from: {}", ALIGNMENT_MODEL_URL);

        if (progressCallback != null) {
            progressCallback.updateProgress(0.0);
        }

        long downloadedBytes = 0;
        long totalBytes = -1;

        try {
            URL url = new URL(ALIGNMENT_MODEL_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "AudioManager/2.0");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            totalBytes = conn.getContentLengthLong();
            if (totalBytes <= 0) {
                LOGGER.warn("Content length unknown, using fallback size check");
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(modelPath.toFile())) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                double lastProgressUpdate = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;

                    if (progressCallback != null && totalBytes > 0) {
                        double progress = (double) downloadedBytes / totalBytes;
                        if (progress - lastProgressUpdate > 0.01 || progress >= 1.0) {
                            lastProgressUpdate = progress;
                            progressCallback.updateProgress(progress * 0.9);
                        }
                    }
                }
            }

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
     * Resolves the HuggingFace token from environment, system property, or file.
     *
     * @return the token, or {@code null} if not found
     */
    private static String resolveHfToken() {
        String token = System.getenv("HF_TOKEN");
        if (token != null && !token.isBlank()) {
            LOGGER.debug("HF token loaded from environment variable HF_TOKEN.");
            return token.trim();
        }

        token = System.getProperty("hf.token");
        if (token != null && !token.isBlank()) {
            LOGGER.warn("HF token loaded from -Dhf.token system property — this is also");
            LOGGER.warn("visible in process arguments and run-console logs. Prefer an");
            LOGGER.warn("HF_TOKEN environment variable or the ~/.audiomanager/hf_token file.");
            return token.trim();
        }

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
     * Resolves the Python executable for WhisperX.
     *
     * @return the Python executable path
     */
    public static String resolvePythonExecutable() {
        final boolean isWindows =
                System.getProperty("os.name", "").toLowerCase().contains("win");

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

        audiomanager.Studio studio = audiomanager.Studio.getInstance();
        if (studio != null && studio.isWhisperAvailable()) {
            String bundledPython = studio.getWhisperPythonPath();
            if (isWhisperXInstalled(bundledPython)) {
                LOGGER.info("Using bundled WhisperX Python: {}", bundledPython);
                return bundledPython;
            }
        }

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
                if (line.isBlank()) continue;

                Path whisperxExecutable;
                try {
                    whisperxExecutable = Paths.get(line);
                } catch (InvalidPathException ex) {
                    continue;
                }

                if (!Files.exists(whisperxExecutable)) continue;

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
        } catch (IOException ex) {
            LOGGER.debug("Unable to locate WhisperX executable.", ex);
        }

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
            if (!Files.exists(python)) continue;
            LOGGER.info("Testing Python interpreter: {}", python);
            if (isWhisperXInstalled(python.toString())) {
                LOGGER.info("WhisperX found using {}", python);
                return python.toAbsolutePath().toString();
            }
        }

        String[] pathCandidates = isWindows
                ? new String[]{"python.exe", "python", "py"}
                : new String[]{"python3", "python"};

        for (String python : pathCandidates) {
            if (isWhisperXInstalled(python)) {
                LOGGER.info("Using Python from PATH: {}", python);
                return python;
            }
        }

        throw new IllegalStateException(
                "\n\nWhisperX is not installed or could not be located.\n\n"
                + "Please install WhisperX into a Python virtual environment and\n"
                + "set the WHISPERX_PYTHON environment variable to that Python executable.\n\n"
                + "Example:\n"
                + "C:\\AI\\whisperx_env\\Scripts\\python.exe\n");
    }

    /**
     * Checks if WhisperX is installed for a given Python interpreter.
     *
     * @param pythonExecutable the Python executable path
     * @return {@code true} if WhisperX is installed
     */
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

    // ========================================================================
    //  Private Helper Methods
    // ========================================================================

    /**
     * Ensures a model is available in the cache.
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

    /**
     * Checks if a model is available locally.
     */
    private boolean isModelLocallyAvailable(String modelName, String modelType) {
        if (modelManager.isModelValid(modelName, modelType)) return true;
        if (isModelInHuggingFaceCache(modelName, modelType)) return true;
        return modelManager.findModelPath(modelName, modelType) != null;
    }

    /**
     * Checks if a model exists in the HuggingFace cache.
     */
    private boolean isModelInHuggingFaceCache(String modelName, String modelType) {
        return HuggingFaceCacheResolver.resolve(modelName).isPresent();
    }

    /**
     * Validates a locally available model.
     */
    private boolean validateLocalModel(String modelName, String modelType) {
        LOGGER.info("Validating local model '{}'…", modelName);

        Path modelPath = modelManager.findModelPath(modelName, modelType);
        if (modelPath != null && hasModelFilesRecursive(modelPath)) {
            LOGGER.info("✓ Model '{}' validated at: {}", modelName, modelPath);

            long size = getModelSizeFromPath(modelPath);
            modelManager.registerModel(modelName, modelType, null, size);
            modelManager.markModelDownloaded(modelName, modelType, null, size);
            return true;
        }

        LOGGER.warn("Local model '{}' could not be validated – required files missing.", modelName);
        return false;
    }

    /**
     * Checks if a directory contains model files.
     */
    private boolean hasModelFilesRecursive(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".bin") || name.endsWith(".safetensors") || name.endsWith(".pt");
            });
        } catch (IOException e) {
            LOGGER.debug("Error walking {}: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * Gets the size of a model directory.
     */
    private long getModelSizeFromPath(Path modelPath) {
        try {
            return Files.walk(modelPath, FileVisitOption.FOLLOW_LINKS)
                .filter(Files::isRegularFile)
                .mapToLong(p -> { try { return Files.size(p); } catch(IOException e){ return 0; } })
                .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Returns the path to the alignment model.
     */
    private Path getAlignmentModelPath() {
        String os = System.getProperty("os.name").toLowerCase();
        Path basePath;

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                basePath = Paths.get(appData, ".cache", "torch", "hub", "checkpoints");
            } else {
                basePath = Paths.get(System.getProperty("user.home"), ".cache", "torch", "hub", "checkpoints");
            }
        } else if (os.contains("mac")) {
            basePath = Paths.get(System.getProperty("user.home"),
                "Library", "Caches", "torch", "hub", "checkpoints");
        } else {
            basePath = Paths.get(System.getProperty("user.home"),
                ".cache", "torch", "hub", "checkpoints");
        }

        return basePath.resolve(ALIGNMENT_MODEL_FILENAME);
    }

    /**
     * Parses stage timing lines from the Python script output.
     */
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

    /**
     * Shows model cache status.
     */
    private void showModelCacheStatus() {
        LOGGER.info("Model cache status check — see full cache details in debug log.");
    }

    /**
     * Normalises the model name.
     */
    private String normaliseModelName(String model) {
        if (model == null || model.isBlank()) return "base";
        String m = model.toLowerCase().trim();
        if ("large".equals(m)) {
            LOGGER.info("Normalising model alias 'large' → 'large-v2'.");
            return "large-v2";
        }
        return m;
    }

    /**
     * Creates a fallback configuration with diarisation disabled.
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
            .diarizeEnabled(false)
            .hfToken(original.getHfToken())
            .maxSegmentDuration(original.getMaxSegmentDuration())
            .enabled(original.isEnabled())
            .skipSegmentation(original.isSkipSegmentation())
            .build();
    }

    /**
     * Checks if a file is inside a segment work directory.
     */
    private boolean isInsideSegmentWorkDir(File dir) {
        while (dir != null) {
            if (dir.getName().startsWith("segment_work_")) return true;
            dir = dir.getParentFile();
        }
        return false;
    }

    /**
     * Calculates the estimated time for transcription.
     */
    private double calculateEstimatedTime(double audioDuration, TranscriptionConfig config) {
        return calculateTimeout(audioDuration, config.getModel()) * 0.7;
    }

    /**
     * Calculates the timeout for a transcription.
     */
    private int calculateTimeout(double audioDuration, String model) {
        boolean gpuMode = isGpuEnabled();
        double gpuSpeedup = gpuMode ? 0.4 : 1.0;

        if (!useGPU && (model.contains("large") || model.equals("large-v2") || model.equals("large-v3"))) {
            return (int) (4 * 3600 * gpuSpeedup);
        }

        double factor;
        switch (model.toLowerCase()) {
            case "tiny"     -> factor = 10.0;
            case "base"     -> factor = 15.0;
            case "small"    -> factor = 25.0;
            case "medium"   -> factor = 40.0;
            case "large"    -> factor = 60.0;
            case "large-v2" -> factor = 60.0;
            case "large-v3" -> factor = 72.0;
            default         -> factor = 15.0;
        }

        if (!useGPU) {
            factor = factor * 2.5;
        }

        long estimated  = (long) (audioDuration * factor * gpuSpeedup);
        long overhead   = 10 * 60L;
        long timeout    = estimated + overhead;
        long minimum    = 5 * 60L + overhead;
        timeout         = Math.max(timeout, minimum);
        timeout         = Math.min(timeout, 48 * 3600L);

        LOGGER.info("Timeout for model={} audioDuration={}s → {}s (GPU: {})",
            model, audioDuration, timeout, gpuMode);

        if (timeout > Integer.MAX_VALUE) {
            LOGGER.warn("Timeout value {} exceeds integer max, capping at {}", timeout, Integer.MAX_VALUE);
            timeout = Integer.MAX_VALUE;
        }
        return (int) timeout;
    }

    /**
     * Transcribes with retry and model fallback.
     */
    private TranscriptionResult transcribeWithRetry(String audioFilePath,
                                                    TranscriptionConfig config,
                                                    AudioProcessor.ProgressCallback progressCallback,
                                                    double audioDuration) throws Exception {
        List<String> modelFallbacks = buildModelFallbackChain(config.getModel());
        Exception lastException = null;

        for (String modelName : modelFallbacks) {
            try {
                LOGGER.info("Attempting transcription with model: {} (GPU: {})",
                    modelName, isGpuEnabled() ? "enabled" : "disabled");
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
                    throw e;
                }
            }
        }
        throw new Exception("All model fallbacks exhausted.", lastException);
    }

    /**
     * Builds the model fallback chain.
     */
    private List<String> buildModelFallbackChain(String requestedModel) {
        List<String> chain = new ArrayList<>();
        String normalized = normaliseModelName(requestedModel);
        chain.add(normalized);

        List<String> ladder = List.of("large-v3", "large-v2", "medium", "small", "base", "tiny");
        int idx = ladder.indexOf(normalized);
        if (idx >= 0) {
            for (int i = idx + 1; i < ladder.size(); i++) {
                chain.add(ladder.get(i));
            }
        }
        return chain;
    }

    /**
     * Executes WhisperX on a file.
     */
    private TranscriptionResult executeWhisperX(String audioFilePath,
                                                TranscriptionConfig config,
                                                AudioProcessor.ProgressCallback progressCallback,
                                                double audioDuration,
                                                String modelName) throws Exception {
        Path audioPath   = Paths.get(audioFilePath);
        Path outputDir   = createTempOutputDir(audioPath);

        try {
            Path scriptFile = writeTranscriptionScript(audioPath, outputDir, config, modelName);
            List<String> command = buildWhisperXCommand(scriptFile, audioFilePath, outputDir, config, modelName);
            int timeoutSeconds = calculateTimeout(audioDuration, modelName);

            LOGGER.info("Running WhisperX — timeout: {}s, output: {}, GPU: {}",
                timeoutSeconds, outputDir, isGpuEnabled() ? "enabled" : "disabled");

            StringBuilder combinedLog = new StringBuilder();
            AtomicBoolean cancelled = new AtomicBoolean(false);

            lastPythonStageTimingsMs.clear();
            lastPythonPeakMemoryMb = -1;
            lastPythonAvgCpuPercent = -1;

            Consumer<String> outputConsumer = line -> {
                combinedLog.append(line).append("\n");
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

            Map<String, String> processEnv = buildWhisperXEnv(config);

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
            monitorThread.start();

            int exitCode;
            try {
                exitCode = ProcessRunner.runCommand(command, timeoutSeconds, TimeUnit.SECONDS,
                        outputConsumer, processEnv);
            } finally {
                monitorThread.interrupt();
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

    /**
     * Builds the WhisperX command.
     */
    private List<String> buildWhisperXCommand(Path scriptFile, String audioFilePath,
                                              Path outputDir, TranscriptionConfig config,
                                              String modelName) {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExecutable);
        cmd.add(scriptFile.toString());
        cmd.add(audioFilePath);
        cmd.add("--output-dir");
        cmd.add(outputDir.toString());
        cmd.add("--model");
        cmd.add(modelName);
        cmd.add("--output-format");
        cmd.add("json");

        if (config.getLanguage() != null && !config.getLanguage().isBlank()
                && !"auto".equalsIgnoreCase(config.getLanguage())) {
            cmd.add("--language");
            cmd.add(config.getLanguage());
        }

        if (hfToken != null && !hfToken.isBlank() && config.isDiarizeEnabled()) {
            cmd.add("--diarize");
            cmd.add("--hf-token");
            cmd.add(hfToken);
            cmd.add("--include-speakers");
        }

        LOGGER.info("WhisperX command built with GPU: {}", isGpuEnabled() ? "enabled" : "disabled");

        return cmd;
    }

    /**
     * Writes the transcription script.
     */
    private Path writeTranscriptionScript(Path audioPath, Path outputDir,
                                          TranscriptionConfig config,
                                          String modelName) throws IOException {
        Path userScript = Paths.get(System.getProperty("user.home"), "audio_transcription_script.py");
        if (Files.exists(userScript) && Files.isRegularFile(userScript)) {
            logUserScriptOverride(userScript);
            Path scriptFile = outputDir.resolve("transcribe.py");
            Files.copy(userScript, scriptFile, StandardCopyOption.REPLACE_EXISTING);
            return scriptFile;
        }

        try (var stream = getClass().getResourceAsStream("/scripts/transcribe.py")) {
            if (stream != null) {
                Path scriptFile = outputDir.resolve("transcribe.py");
                Files.copy(stream, scriptFile);
                return scriptFile;
            }
        }

        LOGGER.warn("Classpath resource /scripts/transcribe.py not found – using inline fallback script.");
        String fallback = buildFallbackTranscriptionScript(config, modelName);
        Path scriptFile = outputDir.resolve("transcribe.py");
        Files.writeString(scriptFile, fallback, StandardCharsets.UTF_8);
        return scriptFile;
    }

    /**
     * Builds a fallback transcription script.
     */
    private String buildFallbackTranscriptionScript(TranscriptionConfig config, String modelName) {
        String device = getDeviceString();
        String computeType = getComputeType();
        String modelArg = modelName;

        return "import sys, json, os\n"
                + "os.environ.setdefault('HF_HUB_OFFLINE', '1')\n"
                + "os.environ.setdefault('TRANSFORMERS_OFFLINE', '1')\n"
                + "import torch\n"
                + "import whisperx\n"
                + "audio_file = sys.argv[1]\n"
                + "output_dir = sys.argv[sys.argv.index('--output-dir') + 1]\n"
                + "device = '" + device + "'\n"
                + "compute_type = '" + computeType + "'\n"
                + "print(f'Using device: {device}, compute_type: {compute_type}', file=sys.stderr)\n"
                + "model = whisperx.load_model('" + modelArg + "', device=device, compute_type=compute_type)\n"
                + "audio = whisperx.load_audio(audio_file)\n"
                + "result = model.transcribe(audio, batch_size=16)\n"
                + "out = os.path.join(output_dir, 'result.json')\n"
                + "with open(out, 'w') as f:\n"
                + "    json.dump(result, f)\n"
                + "print('DONE')\n";
    }

    /**
     * Logs a warning about user script override.
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
     * Creates a temporary output directory.
     */
    private Path createTempOutputDir(Path audioPath) throws IOException {
        Path tempDir = audioPath.getParent().resolve(
                "whisperx_output_" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(tempDir);
        return tempDir;
    }

    /**
     * Cleans up a temporary output directory.
     */
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

    /**
     * Builds the environment for WhisperX.
     */
    private Map<String, String> buildWhisperXEnv(TranscriptionConfig config) {
        Map<String, String> env = new HashMap<>();

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

        if (hfToken != null && !hfToken.isBlank() && config.isDiarizeEnabled()) {
            env.put("HF_TOKEN", hfToken);
        }

        if (isGpuEnabled()) {
            env.put("CUDA_VISIBLE_DEVICES", "0");
            env.put("TF_CPP_MIN_LOG_LEVEL", "2");
            env.put("PYTORCH_CUDA_ALLOC_CONF", "max_split_size_mb:128");
            LOGGER.debug("GPU environment variables set for WhisperX");
        }

        env.put("MKL_NUM_THREADS", "1");
        env.put("OMP_NUM_THREADS", "1");

        env.put("HF_HUB_DISABLE_PROGRESS_BARS", "false");
        env.put("HF_HUB_DISABLE_TELEMETRY", "1");
        env.put("HF_HUB_LOCAL_FILES_ONLY", "1");

        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "UTF-8");

        env = dependencyManager.withFfmpegOnPath(env);

        return env;
    }

    /**
     * Parses progress from WhisperX output.
     */
    private double parseWhisperXProgress(String line) {
        if (line.contains("|") && line.contains("%")) {
            try {
                String pctStr = line.strip().replaceFirst("^(\\d+)%.*", "$1");
                int pct = Integer.parseInt(pctStr);
                return pct / 100.0;
            } catch (NumberFormatException ignored) { }
        }
        return -1.0;
    }

    /**
     * Parses WhisperX output JSON.
     */
    private TranscriptionResult parseWhisperXOutput(Path outputDir,
                                                    TranscriptionConfig config) throws Exception {
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

            String language = root.has("language") ? root.get("language").getAsString() : "unknown";

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

    /**
     * Checks download progress.
     */
    private void checkDownloadProgress(String modelName, String modelType) {
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

    /**
     * Lists output files in a directory.
     */
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

    /**
     * Calculates the duration from segments.
     */
    private double calculateDuration(List<TranscriptionSegment> segments) {
        return segments.stream().mapToDouble(TranscriptionSegment::getEnd).max().orElse(0.0);
    }

    /**
     * Formats bytes to a human-readable string.
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1_024)       return bytes + " B";
        if (bytes < 1_048_576)   return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824) return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     */
    private String formatDuration(long millis) {
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m " + seconds + "s";
    }

    /**
     * Transcribes with streaming for large files.
     */
    private TranscriptionResult transcribeWithStreaming(File audioFile, TranscriptionConfig config) throws Exception {
        LOGGER.info("Using streaming transcription for large file: {}", audioFile.getName());

        Path tempDir = Files.createTempDirectory("whisper_chunks_");
        tempDir.toFile().deleteOnExit();

        try {
            double totalDuration = getAudioDuration(audioFile);
            if (totalDuration <= 0) {
                throw new IOException("Could not determine audio duration for: " + audioFile.getName());
            }

            int chunkDuration = CHUNK_DURATION_SECONDS;
            int totalChunks = (int) Math.ceil(totalDuration / chunkDuration);

            if (totalChunks > 50) {
                chunkDuration = (int) Math.ceil(totalDuration / 50);
                totalChunks = 50;
                LOGGER.info("Adjusting chunk size to {} seconds to limit to {} chunks", chunkDuration, totalChunks);
            }

            final int finalChunkDuration = chunkDuration;

            LOGGER.info("Splitting into {} chunks of {} seconds each", totalChunks, finalChunkDuration);

            List<File> chunks = splitAudioIntoChunks(audioFile, tempDir, finalChunkDuration);

            if (chunks.isEmpty()) {
                throw new IOException("No chunks were created from the audio file");
            }

            LOGGER.info("Successfully created {} chunks", chunks.size());

            int maxConcurrentChunks = Math.min(Runtime.getRuntime().availableProcessors(), 4);
            if (isGpuEnabled()) {
                maxConcurrentChunks = Math.min(maxConcurrentChunks, 2);
            }

            LOGGER.info("Processing chunks with {} concurrent workers", maxConcurrentChunks);

            List<TranscriptionResult> chunkResults = Collections.synchronizedList(new ArrayList<>());
            List<String> errors = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<TranscriptionResult>> futures = new ArrayList<>();

            for (int idx = 0; idx < chunks.size(); idx++) {
                final File chunk = chunks.get(idx);
                final int chunkIndex = idx;
                final int totalChunksCount = chunks.size();
                final int durationPerChunk = finalChunkDuration;

                CompletableFuture<TranscriptionResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        LOGGER.debug("Processing chunk {}/{}", chunkIndex + 1, totalChunksCount);
                        TranscriptionConfig chunkConfig = createChunkConfig(config);
                        return executeWhisperX(
                            chunk.getAbsolutePath(),
                            chunkConfig,
                            null,
                            durationPerChunk,
                            config.getModel()
                        );
                    } catch (Exception e) {
                        LOGGER.error("Chunk {} failed: {}", chunkIndex + 1, e.getMessage());
                        return new TranscriptionResult(
                            "",
                            "unknown",
                            0,
                            new ArrayList<>()
                        );
                    }
                });
                futures.add(future);

                if (futures.size() >= maxConcurrentChunks) {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    for (CompletableFuture<TranscriptionResult> f : futures) {
                        try {
                            TranscriptionResult result = f.get();
                            if (result != null && result.getText() != null && !result.getText().isBlank()) {
                                chunkResults.add(result);
                            }
                        } catch (Exception e) {
                            errors.add("Chunk processing error: " + e.getMessage());
                        }
                    }
                    futures.clear();
                }
            }

            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                for (CompletableFuture<TranscriptionResult> f : futures) {
                    try {
                        TranscriptionResult result = f.get();
                        if (result != null && result.getText() != null && !result.getText().isBlank()) {
                            chunkResults.add(result);
                        }
                    } catch (Exception e) {
                        errors.add("Chunk processing error: " + e.getMessage());
                    }
                }
            }

            if (!errors.isEmpty()) {
                LOGGER.warn("Some chunks had errors: {}", String.join("; ", errors));
            }

            return combineChunkResults(chunkResults, tempDir, config, audioFile);

        } finally {
            cleanupTempFiles(tempDir);
        }
    }

    /**
     * Creates a configuration for a chunk.
     */
    private TranscriptionConfig createChunkConfig(TranscriptionConfig parent) {
        return TranscriptionConfig.builder()
            .model(parent.getModel())
            .language(parent.getLanguage())
            .timestampsEnabled(parent.isTimestampsEnabled())
            .confidenceEnabled(parent.isConfidenceEnabled())
            .outputFormat(parent.getOutputFormat())
            .volumeBoost(parent.getVolumeBoost())
            .silenceThreshold(parent.getSilenceThreshold())
            .silenceDuration(parent.getSilenceDuration())
            .noiseReduction(parent.isNoiseReduction())
            .srtMaxChars(parent.getSrtMaxChars())
            .srtMaxLines(parent.getSrtMaxLines())
            .diarizeEnabled(false)
            .hfToken(parent.getHfToken())
            .maxSegmentDuration(0)
            .enabled(parent.isEnabled())
            .skipSegmentation(true)
            .build();
    }

    /**
     * Splits audio into chunks using FFmpeg.
     */
    private List<File> splitAudioIntoChunks(File audioFile, Path tempDir, int durationSeconds) throws Exception {
        List<File> chunks = new ArrayList<>();
        String ffmpegPath = dependencyManager.getFFmpegPath();

        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            throw new IOException("FFmpeg path not resolved");
        }

        double totalDuration = getAudioDuration(audioFile);
        int totalChunks = (int) Math.ceil(totalDuration / durationSeconds);

        for (int i = 0; i < totalChunks; i++) {
            double startTime = i * durationSeconds;
            String chunkName = String.format("chunk_%04d_%s", i + 1, audioFile.getName());
            File chunkFile = tempDir.resolve(chunkName).toFile();

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-i");
            command.add(audioFile.getAbsolutePath());
            command.add("-ss");
            command.add(String.valueOf(startTime));
            command.add("-t");
            command.add(String.valueOf(durationSeconds));
            command.add("-c");
            command.add("copy");
            command.add(chunkFile.getAbsolutePath());
            command.add("-y");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            LOGGER.debug("Splitting chunk {}/{}: start={}s, duration={}s",
                        i + 1, totalChunks, startTime, durationSeconds);

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                LOGGER.warn("FFmpeg chunk split failed for chunk {}: {}", i + 1, output);
                continue;
            }

            if (chunkFile.exists() && chunkFile.length() > 0) {
                chunks.add(chunkFile);
                LOGGER.debug("Created chunk {}: {} ({} bytes)",
                            i + 1, chunkFile.getName(), chunkFile.length());
            } else {
                LOGGER.warn("Chunk {} not created or empty", i + 1);
            }
        }

        return chunks;
    }

    /**
     * Gets the audio duration using FFprobe.
     */
    private double getAudioDuration(File audioFile) throws Exception {
        String ffprobePath = dependencyManager.getFFprobePath();

        if (ffprobePath == null || ffprobePath.isBlank()) {
            throw new IOException("FFprobe path not resolved");
        }

        ProcessBuilder pb = new ProcessBuilder(
            ffprobePath,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            audioFile.getAbsolutePath()
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();

        if (exitCode == 0 && !output.isBlank()) {
            return Double.parseDouble(output);
        }
        return 0;
    }

    /**
     * Combines chunk results into a single transcription.
     */
    private TranscriptionResult combineChunkResults(List<TranscriptionResult> chunkResults,
                                                    Path tempDir,
                                                    TranscriptionConfig config,
                                                    File originalFile) {
        if (chunkResults.isEmpty()) {
            return new TranscriptionResult("", "unknown", 0, new ArrayList<>());
        }

        StringBuilder combinedText = new StringBuilder();
        List<TranscriptionSegment> combinedSegments = new ArrayList<>();

        double timeOffset = 0.0;
        int segmentCount = 0;

        for (int i = 0; i < chunkResults.size(); i++) {
            TranscriptionResult chunk = chunkResults.get(i);

            if (chunk.getText() == null || chunk.getText().isBlank()) {
                continue;
            }

            if (combinedText.length() > 0) {
                combinedText.append("\n\n");
            }
            combinedText.append(chunk.getText());

            if (chunk.getSegments() != null) {
                for (TranscriptionSegment seg : chunk.getSegments()) {
                    TranscriptionSegment adjusted = new TranscriptionSegment(
                        seg.getStart() + timeOffset,
                        seg.getEnd() + timeOffset,
                        seg.getText(),
                        seg.getConfidence(),
                        seg.getSpeaker()
                    );
                    combinedSegments.add(adjusted);
                    segmentCount++;
                }
            }

            timeOffset += CHUNK_DURATION_SECONDS;
        }

        String language = chunkResults.stream()
            .filter(r -> r.getLanguage() != null && !"unknown".equals(r.getLanguage()))
            .map(TranscriptionResult::getLanguage)
            .findFirst()
            .orElse("unknown");

        LOGGER.info("Combined {} segments from {} chunks", segmentCount, chunkResults.size());

        return new TranscriptionResult(
            combinedText.toString(),
            language,
            calculateDuration(combinedSegments),
            combinedSegments
        );
    }

    /**
     * Cleans up temporary files.
     */
    private void cleanupTempFiles(Path tempDir) {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }

        try {
            Files.walk(tempDir)
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        LOGGER.trace("Could not delete: {}", path);
                    }
                });
            LOGGER.debug("Cleaned up temporary directory: {}", tempDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup temp directory: {}", e.getMessage());
        }
    }
}