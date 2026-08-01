/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.constants.AppConstants;
import audiomanager.exceptions.FfmpegException;
import audiomanager.model.ProcessingConfig;
import audiomanager.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles audio file processing using FFmpeg.
 *
 * <p><b>Thread-safety note:</b> Each call to {@link #processAudioToWav} or
 * {@link #processAudioWithVolumeOptimization} is fully stateless — audio
 * duration is returned from the method rather than cached as a field, so the
 * same {@code AudioProcessor} instance can be safely shared across parallel
 * worker threads.</p>
 */
public class AudioProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);

    /** Cached FFmpeg major version (lazy-initialised once, then reused). */
    private volatile int cachedFfmpegMajor = -1;

    private final DependencyManager dependencyManager;

    public AudioProcessor(DependencyManager dependencyManager) {
        this.dependencyManager = dependencyManager;
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Convert {@code inputFile} to 16 kHz / mono / PCM-s16le WAV suitable for
     * Whisper.  The audio duration (seconds) is returned via the
     * {@link ProcessingResult} wrapper so that callers running in parallel
     * threads do not share any mutable state.
     *
     * @param inputFile        source audio file
     * @param config           processing configuration
     * @param progressCallback optional progress listener (may be {@code null})
     * @return a {@link ProcessingResult} containing the temp WAV path and the
     *         measured audio duration in seconds
     * @throws Exception on FFmpeg failure or interruption
     */
    public ProcessingResult processAudioToWav(File inputFile,
                                              ProcessingConfig config,
                                              ProgressCallback progressCallback) throws Exception {
        LOGGER.info("Processing audio to WAV: {}", inputFile.getName());
        if (progressCallback instanceof StageAwareCallback stageAware) {
            stageAware.onStageStart("Conversion", 30.0);
        }

        // Always probe duration from the source file directly (stateless).
        double audioDuration = getDurationForFile(inputFile);
        LOGGER.debug("Audio duration: {} seconds", audioDuration);

        Path outputDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Files.createDirectories(outputDir);

        String stemName = getFileNameWithoutExtension(inputFile.getName());
        String outputFileName = String.format("%s_%s_whisper_temp.wav",
                stemName, UUID.randomUUID().toString().substring(0, 8));
        Path outputFile = outputDir.resolve(outputFileName);

        if (isAlreadyWav(inputFile)) {
            LOGGER.info("Input is already WAV — copying to temp location.");
            Files.copy(inputFile.toPath(), outputFile, StandardCopyOption.REPLACE_EXISTING);
            if (progressCallback != null) {
                progressCallback.updateProgress(1.0);
            }
            return new ProcessingResult(outputFile.toAbsolutePath().toString(), audioDuration);
        }

        List<String> command = buildWavConversionCommand(inputFile, outputFile.toFile(), config);
        LOGGER.debug("FFmpeg command: {}", String.join(" ", command));
        executeFFmpegWithProgress(command, progressCallback, audioDuration);
        LOGGER.info("WAV conversion complete: {}", outputFile.getFileName());
        return new ProcessingResult(outputFile.toAbsolutePath().toString(), audioDuration);
    }

    /**
     * Convert to final user-selected format.  Returns the output file and the
     * measured audio duration.
     */
    public ProcessingResult processAudioToFinal(File inputFile,
                                                ProcessingConfig config,
                                                ProgressCallback progressCallback) throws Exception {
        LOGGER.info("Processing audio to final format: {}", inputFile.getName());
        Path outputDir = Paths.get(config.getOutputDirectory());
        Files.createDirectories(outputDir);

        String stemName = getFileNameWithoutExtension(inputFile.getName());
        String extension = "." + config.getOutputFormat();
        Path outputFile = generateUniqueFilePath(outputDir, stemName, extension);

        double duration = getDurationForFile(inputFile);
        List<String> command = buildFinalConversionCommand(inputFile, outputFile.toFile(), config);
        LOGGER.debug("FFmpeg command: {}", String.join(" ", command));
        executeFFmpegWithProgress(command, progressCallback, duration);
        LOGGER.info("Final audio processing complete: {}", outputFile.getFileName());
        return new ProcessingResult(outputFile.toAbsolutePath().toString(), duration);
    }

    /**
     * Probe the exact playback duration of {@code inputFile} using
     * <em>FFprobe</em> (not FFmpeg).
     *
     * @param inputFile audio file to measure
     * @return duration in seconds, or {@code 0.0} if the probe fails
     */
    public double getDurationForFile(File inputFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                dependencyManager.getFFprobePath(),   // FIX: was incorrectly using getFFmpegPath()
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                inputFile.getAbsolutePath()
        );

        String output = ProcessRunner.readCommandOutput(command, 10, TimeUnit.SECONDS);
        if (output != null && !output.isBlank()) {
            try {
                return Double.parseDouble(output.trim());
            } catch (NumberFormatException e) {
                LOGGER.error("Failed to parse duration from FFprobe output: '{}' for file: {}",
                        output, inputFile.getName());
            }
        }
        LOGGER.error("FFprobe returned no duration output for file: {}", inputFile.getName());
        return 0.0;
    }

    /**
     * Two-pass volume optimisation: convert → analyse → conditionally amplify.
     * Returns the path to the (possibly amplified) WAV and the audio duration.
     */
    public ProcessingResult processAudioWithVolumeOptimization(File inputFile,
                                                               ProcessingConfig config,
                                                               ProgressCallback progressCallback)
            throws Exception {
        LOGGER.info("Processing audio with volume optimisation: {}", inputFile.getName());
        if (progressCallback != null) progressCallback.updateProgress(0.1);

        ProcessingResult wavResult = processAudioToWav(inputFile, config, progressCallback);
        String wavFile = wavResult.getOutputPath();
        double audioDuration = wavResult.getAudioDurationSeconds();

        if (progressCallback != null) progressCallback.updateProgress(0.4);

        LOGGER.info("Analysing volume levels…");
        VolumeAnalysis analysis = analyzeVolume(wavFile);
        LOGGER.info("Volume analysis: max={}dB, avg={}dB, recommended gain={}dB",
                analysis.getMaxVolume(), analysis.getAvgVolume(), analysis.getRecommendedGain());

        if (progressCallback instanceof StageAwareCallback stageAware) {
            String stageLabel = analysis.needsAmplification()
                    ? String.format("Amplifying Audio (+%.1fdB)", analysis.getRecommendedGain())
                    : "Volume OK";
            stageAware.onStageStart(stageLabel, 10.0);
        }

        String audioToProcess = wavFile;
        if (analysis.needsAmplification()) {
            // FIX: was using Python-style {:.1f} format in logger call — now uses SLF4J {}
            LOGGER.info("Amplifying audio by {}dB…",
                    String.format("%.1f", analysis.getRecommendedGain()));
            if (progressCallback != null) progressCallback.updateProgress(0.6);
            audioToProcess = amplifyAudio(wavFile, analysis.getRecommendedGain());
            VolumeAnalysis postAnalysis = analyzeVolume(audioToProcess);
            // FIX: was using Python-style {:.1f}
            LOGGER.info("Post-amplification max volume: {}dB",
                    String.format("%.1f", postAnalysis.getMaxVolume()));
        }

        if (progressCallback != null) progressCallback.updateProgress(1.0);
        return new ProcessingResult(audioToProcess, audioDuration);
    }

    /**
     * Analyse volume statistics using {@code ffmpeg -af volumedetect}.
     *
     * FIX: previously threw a generic {@code IOException} on FFmpeg failure,
     * forcing every caller (see {@code MainWindow.analyzeSelectedFileVolume})
     * to catch a bare {@code Exception} with no way to distinguish "FFmpeg
     * rejected this file" from any other failure. Now throws
     * {@link FfmpegException}, carrying the real exit code and stderr tail.
     */
    public VolumeAnalysis analyzeVolume(String audioFilePath) throws FfmpegException {
        File inputFile = new File(audioFilePath);
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("Audio file not found: " + audioFilePath);
        }
        List<String> command = Arrays.asList(
                dependencyManager.getFFmpegPath(),
                "-i", audioFilePath,
                "-af", "volumedetect",
                "-f", "null", "-"
        );
        StringBuilder output = new StringBuilder();
        int exitCode;
        try {
            exitCode = ProcessRunner.runCommand(command, 30, TimeUnit.SECONDS,
                    line -> output.append(line).append("\n"), null);
        } catch (Exception e) {
            throw new FfmpegException(
                "Failed to run ffmpeg volumedetect on " + audioFilePath,
                "Couldn't analyze the volume of '" + inputFile.getName() + "'. " +
                "The file may be corrupt or in an unsupported format.",
                e);
        }
        if (exitCode != 0) {
            throw new FfmpegException(
                "ffmpeg volumedetect exited " + exitCode + " for " + audioFilePath + ": " + output,
                "Couldn't analyze the volume of '" + inputFile.getName() + "'. " +
                "The file may be corrupt or in an unsupported format.",
                exitCode, tail(output.toString()));
        }
        return parseVolumeAnalysis(output.toString());
    }

    /**
     * Apply a fixed gain (dB) using FFmpeg and return the path to the
     * amplified file.
     *
     * FIX: same change as {@link #analyzeVolume} — throws {@link FfmpegException}
     * instead of a generic {@code IOException}/{@code Exception}.
     */
    public String amplifyAudio(String inputFilePath, double gainDb) throws FfmpegException {
        LOGGER.info("Amplifying audio by {}dB: {}", gainDb, inputFilePath);
        Path inputPath = Paths.get(inputFilePath);
        String fileName = inputPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        Path outputPath = inputPath.getParent().resolve(baseName + "_amplified.wav");

        List<String> command = Arrays.asList(
                dependencyManager.getFFmpegPath(), "-y",
                "-i", inputFilePath,
                "-af", String.format(Locale.US, "volume=%fdB", gainDb),
                "-c:a", "pcm_s16le", "-ar", "16000", "-ac", "1",
                outputPath.toString()
        );
        StringBuilder output = new StringBuilder();
        int exitCode;
        try {
            exitCode = ProcessRunner.runCommand(command, 60, TimeUnit.SECONDS,
                    line -> { LOGGER.debug("FFmpeg: {}", line); output.append(line).append("\n"); }, null);
        } catch (Exception e) {
            throw new FfmpegException(
                "Failed to run ffmpeg amplification on " + inputFilePath,
                "Couldn't amplify '" + fileName + "'. The file may be corrupt or in an unsupported format.",
                e);
        }
        if (exitCode != 0) {
            throw new FfmpegException(
                "ffmpeg amplify exited " + exitCode + " for " + inputFilePath + ": " + output,
                "Couldn't amplify '" + fileName + "'. The file may be corrupt or in an unsupported format.",
                exitCode, tail(output.toString()));
        }
        LOGGER.info("Amplified audio saved to: {}", outputPath);
        return outputPath.toString();
    }

    /** Last ~500 chars of FFmpeg output, for a "show details" expander — avoids dumping a huge log into a dialog. */
    private String tail(String s) {
        return s.length() > 500 ? "..." + s.substring(s.length() - 500) : s;
    }

    /** Format a {@link VolumeAnalysis} for human-readable display. */
    public String formatVolumeAnalysis(VolumeAnalysis analysis) {
        return String.format(
                "%n📊 Audio Volume Analysis:%n"
                        + "  Max Volume: %.1f dB%n"
                        + "  Avg Volume: %.1f dB%n"
                        + "  Min Volume: %.1f dB%n"
                        + "  Dynamic Range: %.1f dB%n"
                        + "%n💡 Recommendation: %s%s",
                analysis.getMaxVolume(), analysis.getAvgVolume(),
                analysis.getMinVolume(), analysis.getDynamicRange(),
                analysis.getRecommendation(),
                analysis.needsAmplification()
                        ? String.format("%n🔊 Recommended gain: %.1f dB (%.2fx)",
                        analysis.getRecommendedGain(), analysis.getAmplificationFactor())
                        : "");
    }

    // =========================================================================
    //  Private helpers
    // =========================================================================

    private boolean isAlreadyWav(File file) {
        return file.getName().toLowerCase().endsWith(".wav");
    }

    private List<String> buildWavConversionCommand(File input, File output, ProcessingConfig config) {
        List<String> command = new ArrayList<>();
        command.add(dependencyManager.getFFmpegPath());
        command.add("-y");
        command.add("-i");
        command.add(input.getAbsolutePath());

        String filter = buildAudioFilter(config);
        if (!filter.isEmpty()) {
            command.add("-af");
            command.add(filter);
        }

        // Whisper requirements: 16 kHz, mono, 16-bit PCM
        command.add("-ar");
        command.add(String.valueOf(AppConstants.WHISPER_SAMPLE_RATE));
        command.add("-ac");
        command.add(String.valueOf(AppConstants.WHISPER_CHANNELS));
        command.add("-c:a");
        command.add("pcm_s16le");
        command.add(output.getAbsolutePath());
        return command;
    }

    private List<String> buildFinalConversionCommand(File input, File output, ProcessingConfig config) {
        List<String> command = new ArrayList<>();
        command.add(dependencyManager.getFFmpegPath());
        command.add("-y");
        command.add("-i");
        command.add(input.getAbsolutePath());

        String format = config.getOutputFormat().toLowerCase();
        if (format.equals("mp3") || format.equals("ogg")) {
            command.add("-b:a");
            command.add(config.getBitrate());
        }
        if (!config.isTranscriptionEnabled() && config.getVolumeBoost() > 0) {
            command.add("-af");
            command.add(String.format(Locale.US, "volume=%.1fdB", config.getVolumeBoost()));
        }
        command.add(output.getAbsolutePath());
        return command;
    }

    /**
     * Build the FFmpeg audio filter chain.
     *
     * <p>Key fixes vs. original:</p>
     * <ul>
     *   <li>Noise reduction now uses {@code afftdn} (actual spectral denoiser,
     *       available since FFmpeg 4.0) with a version-gated fallback to the
     *       old bandpass chain.  The bandpass chain ({@code highpass,lowpass})
     *       was not noise reduction — it was frequency restriction.</li>
     *   <li>{@code loudnorm} and {@code volume} are now mutually exclusive:
     *       when normalisation is enabled the {@code volume} boost is skipped
     *       to prevent double-gain and clipping.</li>
     * </ul>
     */
    private String buildAudioFilter(ProcessingConfig config) {
        List<String> filters = new ArrayList<>();

        if (config.isRemoveSilence()) {
            filters.add(String.format(Locale.US,
                    "silenceremove=start_periods=1:start_silence=%.1f:start_threshold=%.1fdB",
                    config.getSilenceDuration(), config.getSilenceThreshold()));
        }

        if (config.isNoiseReduction()) {
            // FIX: use real spectral denoiser (afftdn) when FFmpeg >= 4.0;
            // fall back to bandpass only on ancient builds.
            if (getFfmpegMajorVersion() >= 4) {
                filters.add("afftdn=nf=-25");
            } else {
                LOGGER.warn("FFmpeg < 4.0 detected — falling back to bandpass noise filter.");
                filters.add("highpass=f=100,lowpass=f=8000");
            }
        }

        // FIX: loudnorm and volume are mutually exclusive — applying both
        // double-processes gain and can clip the output.
        if (config.isNormalize()) {
            filters.add("loudnorm=I=-16:TP=-1.5:LRA=11");
            // Skip the volume boost; loudnorm already targets -16 LUFS.
        } else if (config.getVolumeBoost() > 0) {
            filters.add(String.format(Locale.US, "volume=%.1fdB", config.getVolumeBoost()));
        }

        return String.join(",", filters);
    }

    /**
     * Lazy-resolve and cache the FFmpeg major version number.
     *
     * <p>FIX: original code did a literal string search for {@code "ffmpeg
     * version 4."} which returns {@code false} for v5, v6, v7, etc.</p>
     */
    private int getFfmpegMajorVersion() {
        if (cachedFfmpegMajor >= 0) return cachedFfmpegMajor;
        try {
            List<String> cmd = Arrays.asList(dependencyManager.getFFmpegPath(), "-version");
            String out = ProcessRunner.readCommandOutput(cmd, 10, TimeUnit.SECONDS);
            if (out != null) {
                // e.g. "ffmpeg version 6.1.1 ..."
                Matcher m = Pattern.compile("ffmpeg version (\\d+)").matcher(out);
                if (m.find()) {
                    cachedFfmpegMajor = Integer.parseInt(m.group(1));
                    LOGGER.info("Detected FFmpeg major version: {}", cachedFfmpegMajor);
                    return cachedFfmpegMajor;
                }
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("Could not determine FFmpeg version: {}", e.getMessage());
        }
        cachedFfmpegMajor = 0;
        return 0;
    }

    /**
     * Run an FFmpeg command and report progress via {@code callback}.
     *
     * <p>Key fixes vs. original:</p>
     * <ul>
     *   <li>Progress cap removed — the bar now reaches 1.0 as the process
     *       finishes instead of stalling at 0.99 before snapping to 100%.</li>
     *   <li>The {@code InterruptedException} path in the {@code finally} block
     *       no longer swallows the interrupt flag — {@link Thread#interrupt()}
     *       is re-set before the method returns.</li>
     * </ul>
     */
    private void executeFFmpegWithProgress(List<String> command,
                                           ProgressCallback callback,
                                           double duration) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = null;
        StringBuilder outputLog = new StringBuilder();
        boolean interrupted = false;

        try {
            process = builder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                double lastProgress = 0.0;
                while ((line = reader.readLine()) != null) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException("FFmpeg process interrupted");
                    }
                    outputLog.append(line).append("\n");
                    LOGGER.trace("FFmpeg: {}", line);

                    double currentTime = parseTimeFromFFmpegOutput(line);
                    if (duration > 0 && currentTime > 0 && callback != null) {
                        // FIX: removed the 0.99 cap so progress can reach 1.0 naturally
                        double progress = Math.min(1.0, currentTime / duration);
                        if (progress - lastProgress > 0.01) {
                            lastProgress = progress;
                            callback.updateProgress(progress);
                        }
                    }
                }
            }

            boolean exited = process.waitFor(AppConstants.FFMPEG_TIMEOUT_HOURS, TimeUnit.HOURS);
            if (!exited) {
                process.destroyForcibly();
                LOGGER.error("FFmpeg timeout. Output:\n{}", outputLog);
                throw new java.util.concurrent.TimeoutException("FFmpeg process timed out");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.error("FFmpeg failed (exit {}). Output:\n{}", exitCode, outputLog);
                throw new IOException("FFmpeg exited with error code: " + exitCode);
            }

            if (callback != null) callback.updateProgress(1.0);

        } catch (InterruptedException e) {
            interrupted = true;   // remember for finally block
            throw e;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
            // FIX: restore the interrupt flag if we consumed it above
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Parse {@code time=HH:MM:SS.ms} from an FFmpeg stderr line. */
    static double parseTimeFromFFmpegOutput(String line) {
        int timeIndex = line.indexOf("time=");
        if (timeIndex == -1) return 0.0;
        int start = timeIndex + 5;
        int end = line.indexOf(' ', start);
        if (end == -1) end = line.length();
        try {
            String timeStr = line.substring(start, end).trim();
            String[] parts = timeStr.split(":");
            if (parts.length == 3) {
                return Double.parseDouble(parts[0]) * 3600
                        + Double.parseDouble(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            }
        } catch (NumberFormatException | IndexOutOfBoundsException ignored) { }
        return 0.0;
    }

    private VolumeAnalysis parseVolumeAnalysis(String ffmpegOutput) {
        Pattern maxPat = Pattern.compile("max_volume: ([\\-\\d.]+) dB");
        Pattern meanPat = Pattern.compile("mean_volume: ([\\-\\d.]+) dB");
        Pattern minPat = Pattern.compile("min_volume: ([\\-\\d.]+) dB");

        double maxVol = parseFirst(maxPat, ffmpegOutput, -999.0);
        double avgVol = parseFirst(meanPat, ffmpegOutput, -999.0);
        double minVol = parseFirst(minPat, ffmpegOutput, 999.0);

        LOGGER.debug("Parsed volumes — max: {}dB, avg: {}dB, min: {}dB", maxVol, avgVol, minVol);
        return new VolumeAnalysis(maxVol, avgVol, minVol);
    }

    private double parseFirst(Pattern pattern, String text, double defaultValue) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }

    private Path generateUniqueFilePath(Path dir, String baseName, String extension) {
        Path path = dir.resolve(baseName + extension);
        int counter = 1;
        while (Files.exists(path)) {
            path = dir.resolve(baseName + "_" + counter++ + extension);
        }
        return path;
    }

    private String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    // =========================================================================
    //  Result wrapper — replaces the shared mutable audioDurationSeconds field
    // =========================================================================

    /**
     * Immutable result returned by processing methods.  Replaces the old
     * {@code audioDurationSeconds} instance field, eliminating the data race
     * that occurred when multiple worker threads called
     * {@link #processAudioToWav} concurrently on the same instance.
     */
    public static final class ProcessingResult {
        private final String outputPath;
        private final double audioDurationSeconds;

        public ProcessingResult(String outputPath, double audioDurationSeconds) {
            this.outputPath = outputPath;
            this.audioDurationSeconds = audioDurationSeconds;
        }

        /** Absolute path of the output (temp WAV or final format) file. */
        public String getOutputPath() { return outputPath; }

        /** Duration of the audio in seconds, as measured by FFprobe. */
        public double getAudioDurationSeconds() { return audioDurationSeconds; }
    }

    // =========================================================================
    //  Callbacks
    // =========================================================================

    /** Callback for coarse-grained progress updates (value in [0, 1]). */
    public interface ProgressCallback {
        void updateProgress(double progress);
    }

    /** Extended callback that also receives stage-start notifications. */
    public interface StageAwareCallback extends ProgressCallback {
        void onStageStart(String stageName, double estimatedDurationSeconds);
    }

    // =========================================================================
    //  VolumeAnalysis
    // =========================================================================

    /**
     * Holds FFmpeg volumedetect results and derives the recommended gain.
     */
    public static final class VolumeAnalysis {
        private static final double TARGET_MAX_DB      = -1.0;
        private static final double TARGET_RMS_DB      = -16.0;
        private static final double MIN_ACCEPTABLE_MAX = -10.0;
        private static final double MAX_AMPLIFICATION  = 20.0;   // 10×

        private final double maxVolume;
        private final double avgVolume;
        private final double minVolume;
        private final double dynamicRange;
        private final double recommendedGain;
        private final boolean needsAmplification;
        private final String recommendation;

        public VolumeAnalysis(double maxVolume, double avgVolume, double minVolume) {
            this.maxVolume    = maxVolume;
            this.avgVolume    = avgVolume;
            this.minVolume    = minVolume;
            this.dynamicRange = maxVolume - minVolume;

            double gain = 0.0;
            boolean needs;
            String rec;

            if (maxVolume < MIN_ACCEPTABLE_MAX) {
                gain  = Math.min(TARGET_MAX_DB - maxVolume, MAX_AMPLIFICATION);
                needs = true;
                rec   = String.format("Low max volume (%.1fdB). Recommended gain: %.1fdB", maxVolume, gain);
            } else if (avgVolume < TARGET_RMS_DB - 5) {
                gain  = Math.min(TARGET_RMS_DB - avgVolume, MAX_AMPLIFICATION);
                needs = true;
                rec   = String.format("Low average volume (%.1fdB). Recommended gain: %.1fdB", avgVolume, gain);
            } else if (dynamicRange > 40 && minVolume < -50) {
                gain  = Math.min(10.0, MAX_AMPLIFICATION);
                needs = true;
                rec   = String.format("High dynamic range (%.1fdB). Minimal gain: %.1fdB", dynamicRange, gain);
            } else {
                needs = false;
                rec   = "Volume levels are adequate. No amplification needed.";
            }

            this.recommendedGain     = Math.max(0.0, gain);
            this.needsAmplification  = needs;
            this.recommendation      = rec;
        }

        public double getMaxVolume()        { return maxVolume; }
        public double getAvgVolume()        { return avgVolume; }
        public double getMinVolume()        { return minVolume; }
        public double getDynamicRange()     { return dynamicRange; }
        public double getRecommendedGain()  { return recommendedGain; }
        public boolean needsAmplification() { return needsAmplification; }
        public String getRecommendation()   { return recommendation; }
        public double getAmplificationFactor() { return Math.pow(10, recommendedGain / 20.0); }

        @Override
        public String toString() {
            return String.format("VolumeAnalysis{max=%.1fdB, avg=%.1fdB, min=%.1fdB, gain=%.1fdB, needs=%s}",
                    maxVolume, avgVolume, minVolume, recommendedGain, needsAmplification);
        }
    }
}