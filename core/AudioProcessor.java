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
 * <p>This class provides comprehensive audio processing capabilities including:
 * <ul>
 *   <li>Conversion to Whisper-compatible WAV format (16kHz, mono, 16-bit PCM)</li>
 *   <li>Volume analysis and optimization with dynamic amplification</li>
 *   <li>Audio filtering (noise reduction, silence removal, loudness normalization)</li>
 *   <li>Duration probing using FFprobe</li>
 *   <li>Progress reporting during long-running operations</li>
 * </ul>
 *
 * <p><b>Thread-safety note:</b> Each call to {@link #processAudioToWav} or
 * {@link #processAudioWithVolumeOptimization} is fully stateless — audio
 * duration is returned via the {@link ProcessingResult} wrapper rather than
 * cached as a field. The same {@code AudioProcessor} instance can therefore
 * be safely shared across parallel worker threads without synchronization.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see ProcessingResult
 * @see VolumeAnalysis
 */
public class AudioProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioProcessor.class);

    /** Cached FFmpeg major version (lazy-initialised once, then reused). */
    private volatile int cachedFfmpegMajor = -1;
    private final DependencyManager dependencyManager;
    private ErrorReporter errorReporter;

    /**
     * Constructs a new AudioProcessor with the specified dependencies.
     *
     * @param dependencyManager the dependency manager for resolving FFmpeg/FFprobe paths
     * @param errorReporter the error reporter for logging and reporting errors; may be {@code null}
     */
    public AudioProcessor(DependencyManager dependencyManager, ErrorReporter errorReporter) {
        this.dependencyManager = dependencyManager;
        this.errorReporter = errorReporter;
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Converts an audio file to Whisper-compatible WAV format (16 kHz, mono, PCM-s16le).
     *
     * <p>This method performs the following operations:
     * <ol>
     *   <li>Probes the audio duration using FFprobe</li>
     *   <li>If the input is already a WAV file, copies it to a temp location</li>
     *   <li>Otherwise, converts using FFmpeg with the configured audio filters</li>
     *   <li>Reports progress via the provided callback</li>
     * </ol>
     *
     * @param inputFile        the source audio file to process
     * @param config           the processing configuration (filters, sample rate, etc.)
     * @param progressCallback optional progress listener; may be {@code null}
     * @return a {@link ProcessingResult} containing the temporary WAV path and audio duration
     * @throws Exception if FFmpeg fails or the operation is interrupted
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
     * Converts an audio file to the user's selected final format (MP3, OGG, etc.).
     *
     * <p>This method applies the final conversion with the specified output format,
     * bitrate, and volume settings from the configuration.</p>
     *
     * @param inputFile        the source audio file
     * @param config           the processing configuration (output format, bitrate, etc.)
     * @param progressCallback optional progress listener; may be {@code null}
     * @return a {@link ProcessingResult} containing the output file path and audio duration
     * @throws Exception if FFmpeg fails or the operation is interrupted
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
     * Probes the exact playback duration of an audio file using FFprobe.
     *
     * <p>This method uses FFprobe (not FFmpeg) to extract the duration metadata
     * from the audio file, providing accurate duration information.</p>
     *
     * @param inputFile the audio file to measure
     * @return the duration in seconds, or {@code 0.0} if the probe fails
     * @throws IOException if the FFprobe process cannot be started
     * @throws InterruptedException if the process is interrupted
     */
    public double getDurationForFile(File inputFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                dependencyManager.getFFprobePath(),
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
     * Performs two-pass volume optimisation: convert → analyse → conditionally amplify.
     *
     * <p>This method:
     * <ol>
     *   <li>Converts the audio to WAV format</li>
     *   <li>Analyses volume levels using FFmpeg's volumedetect</li>
     *   <li>Applies amplification if needed based on the analysis</li>
     *   <li>Returns the path to the (possibly amplified) WAV file</li>
     * </ol>
     *
     * @param inputFile        the source audio file
     * @param config           the processing configuration
     * @param progressCallback optional progress listener; may be {@code null}
     * @return a {@link ProcessingResult} containing the processed WAV path and audio duration
     * @throws Exception if any processing step fails
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
            LOGGER.info("Amplifying audio by {}dB…",
                    String.format("%.1f", analysis.getRecommendedGain()));
            if (progressCallback != null) progressCallback.updateProgress(0.6);
            audioToProcess = amplifyAudio(wavFile, analysis.getRecommendedGain());
            VolumeAnalysis postAnalysis = analyzeVolume(audioToProcess);
            LOGGER.info("Post-amplification max volume: {}dB",
                    String.format("%.1f", postAnalysis.getMaxVolume()));
        }

        if (progressCallback != null) progressCallback.updateProgress(1.0);
        return new ProcessingResult(audioToProcess, audioDuration);
    }

    /**
     * Analyses volume statistics using FFmpeg's {@code volumedetect} filter.
     *
     * <p>This method runs FFmpeg with the volumedetect audio filter and parses
     * the output to extract max, mean, and min volume levels in decibels.</p>
     *
     * @param audioFilePath the path to the audio file to analyse
     * @return a {@link VolumeAnalysis} object containing the volume statistics
     * @throws FfmpegException if FFmpeg fails (exit code non-zero or command timeout)
     * @throws IllegalArgumentException if the audio file does not exist
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
        int exitCode = 0;
        try {
            exitCode = ProcessRunner.runCommand(command, 30, TimeUnit.SECONDS,
                    line -> output.append(line).append("\n"), null);
        } catch (Exception e) {
            if (errorReporter != null && errorReporter.isEnabled()) {
                errorReporter.reportError(e, "Volume analysis: " + audioFilePath);
            }
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
     * Applies a fixed gain (dB) to an audio file using FFmpeg.
     *
     * <p>This method amplifies the audio by the specified gain and returns
     * the path to the amplified file. The output is saved as a WAV file
     * with the suffix "_amplified".</p>
     *
     * @param inputFilePath the path to the audio file to amplify
     * @param gainDb the gain in decibels to apply (positive values amplify)
     * @return the path to the amplified audio file
     * @throws FfmpegException if FFmpeg fails (exit code non-zero or command timeout)
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
            if (errorReporter != null && errorReporter.isEnabled()) {
                errorReporter.reportError(e, "Audio amplification: " + inputFilePath);
            }
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

    /**
     * Returns the last ~500 characters of FFmpeg output for display in error dialogs.
     *
     * @param s the full output string
     * @return the last 500 characters, prefixed with "..." if truncated
     */
    private String tail(String s) {
        return s.length() > 500 ? "..." + s.substring(s.length() - 500) : s;
    }

    /**
     * Formats a {@link VolumeAnalysis} for human-readable display.
     *
     * @param analysis the volume analysis to format
     * @return a formatted string containing the analysis results and recommendations
     */
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

    /**
     * Checks if the input file is already a WAV file based on its extension.
     *
     * @param file the file to check
     * @return {@code true} if the file has a ".wav" extension (case-insensitive)
     */
    private boolean isAlreadyWav(File file) {
        return file.getName().toLowerCase().endsWith(".wav");
    }

    /**
     * Builds the FFmpeg command for converting audio to Whisper-compatible WAV.
     *
     * @param input the input file
     * @param output the output file
     * @param config the processing configuration
     * @return a list of command arguments for FFmpeg
     */
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

    /**
     * Builds the FFmpeg command for converting audio to the user's final format.
     *
     * @param input the input file
     * @param output the output file
     * @param config the processing configuration
     * @return a list of command arguments for FFmpeg
     */
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
     * Builds the FFmpeg audio filter chain from the processing configuration.
     *
     * <p>Supported filters:
     * <ul>
     *   <li><b>Silence removal:</b> {@code silenceremove} with configurable threshold</li>
     *   <li><b>Noise reduction:</b> {@code afftdn} (FFmpeg ≥ 4.0) or bandpass fallback</li>
     *   <li><b>Loudness normalization:</b> {@code loudnorm} targeting -16 LUFS</li>
     *   <li><b>Volume boost:</b> {@code volume} gain (only when normalization is disabled)</li>
     * </ul>
     *
     * @param config the processing configuration
     * @return a comma-separated filter string, or an empty string if no filters are configured
     */
    private String buildAudioFilter(ProcessingConfig config) {
        List<String> filters = new ArrayList<>();

        if (config.isRemoveSilence()) {
            filters.add(String.format(Locale.US,
                    "silenceremove=start_periods=1:start_silence=%.1f:start_threshold=%.1fdB",
                    config.getSilenceDuration(), config.getSilenceThreshold()));
        }

        if (config.isNoiseReduction()) {
            if (getFfmpegMajorVersion() >= 4) {
                filters.add("afftdn=nf=-25");
            } else {
                LOGGER.warn("FFmpeg < 4.0 detected — falling back to bandpass noise filter.");
                filters.add("highpass=f=100,lowpass=f=8000");
            }
        }

        // loudnorm and volume are mutually exclusive — applying both double-processes gain
        if (config.isNormalize()) {
            filters.add("loudnorm=I=-16:TP=-1.5:LRA=11");
        } else if (config.getVolumeBoost() > 0) {
            filters.add(String.format(Locale.US, "volume=%.1fdB", config.getVolumeBoost()));
        }

        return String.join(",", filters);
    }

    /**
     * Lazy-resolves and caches the FFmpeg major version number.
     *
     * <p>This method runs {@code ffmpeg -version} once and caches the result
     * for subsequent calls.</p>
     *
     * @return the FFmpeg major version number, or {@code 0} if it cannot be determined
     */
    private int getFfmpegMajorVersion() {
        if (cachedFfmpegMajor >= 0) return cachedFfmpegMajor;
        try {
            List<String> cmd = Arrays.asList(dependencyManager.getFFmpegPath(), "-version");
            String out = ProcessRunner.readCommandOutput(cmd, 10, TimeUnit.SECONDS);
            if (out != null) {
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
     * Executes an FFmpeg command with progress reporting and timeout handling.
     *
     * <p>This method parses FFmpeg's stderr output to extract the current
     * processing time and reports progress to the callback.</p>
     *
     * @param command the FFmpeg command to execute
     * @param callback the progress callback; may be {@code null}
     * @param duration the total audio duration in seconds for progress calculation
     * @throws Exception if FFmpeg fails, times out, or the operation is interrupted
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
                throw new FfmpegException(
                        "FFmpeg process timed out after " + AppConstants.FFMPEG_TIMEOUT_HOURS + "h",
                        "Processing this file is taking far longer than expected and was stopped. "
                                + "The file may be unusually long, corrupt, or in a format that's slow to decode.",
                        -1, tail(outputLog.toString()));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.error("FFmpeg failed (exit {}). Output:\n{}", exitCode, outputLog);
                throw new FfmpegException(
                        "FFmpeg exited with error code: " + exitCode,
                        "FFmpeg couldn't process this file (exit code " + exitCode + "). "
                                + "It may be corrupt or in an unsupported format.",
                        exitCode, tail(outputLog.toString()));
            }

            if (callback != null) callback.updateProgress(1.0);

        } catch (InterruptedException e) {
            interrupted = true;
            if (errorReporter != null && errorReporter.isEnabled()) {
                errorReporter.reportError(e, "FFmpeg execution interrupted: " + String.join(" ", command));
            }
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
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Parses the {@code time=HH:MM:SS.ms} string from an FFmpeg stderr line.
     *
     * @param line the FFmpeg output line to parse
     * @return the time in seconds, or {@code 0.0} if the line does not contain a valid time
     */
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

    /**
     * Parses FFmpeg volumedetect output into a {@link VolumeAnalysis} object.
     *
     * @param ffmpegOutput the FFmpeg output containing volumedetect results
     * @return a {@link VolumeAnalysis} object with the parsed statistics
     */
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

    /**
     * Parses a pattern from text and returns the first matching value.
     *
     * @param pattern the pattern to match
     * @param text the text to search
     * @param defaultValue the default value if the pattern is not found
     * @return the parsed value or the default value
     */
    private double parseFirst(Pattern pattern, String text, double defaultValue) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }

    /**
     * Generates a unique file path by appending a counter if the file already exists.
     *
     * @param dir the target directory
     * @param baseName the base file name without extension
     * @param extension the file extension including the dot
     * @return a unique path that does not already exist
     */
    private Path generateUniqueFilePath(Path dir, String baseName, String extension) {
        Path path = dir.resolve(baseName + extension);
        int counter = 1;
        while (Files.exists(path)) {
            path = dir.resolve(baseName + "_" + counter++ + extension);
        }
        return path;
    }

    /**
     * Extracts the file name without its extension.
     *
     * @param fileName the full file name
     * @return the file name without the extension
     */
    private String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    // =========================================================================
    //  Result wrapper — replaces the shared mutable audioDurationSeconds field
    // =========================================================================

    /**
     * Immutable result returned by audio processing methods.
     *
     * <p>This class replaces the old {@code audioDurationSeconds} instance field,
     * eliminating the data race that occurred when multiple worker threads called
     * {@link #processAudioToWav} concurrently on the same instance.</p>
     */
    public static final class ProcessingResult {
        private final String outputPath;
        private final double audioDurationSeconds;

        /**
         * Constructs a new processing result.
         *
         * @param outputPath the absolute path of the output file
         * @param audioDurationSeconds the duration of the audio in seconds
         */
        public ProcessingResult(String outputPath, double audioDurationSeconds) {
            this.outputPath = outputPath;
            this.audioDurationSeconds = audioDurationSeconds;
        }

        /**
         * Returns the absolute path of the output file.
         *
         * @return the output file path
         */
        public String getOutputPath() { return outputPath; }

        /**
         * Returns the duration of the audio in seconds.
         *
         * @return the audio duration in seconds
         */
        public double getAudioDurationSeconds() { return audioDurationSeconds; }
    }

    // =========================================================================
    //  Callbacks
    // =========================================================================

    /**
     * Callback for coarse-grained progress updates.
     *
     * <p>Implementations should update the UI or other progress consumers
     * with the provided progress value (in the range [0, 1]).</p>
     */
    public interface ProgressCallback {
        /**
         * Called to report progress of an ongoing operation.
         *
         * @param progress the current progress value, between 0.0 and 1.0 inclusive
         */
        void updateProgress(double progress);
    }

    /**
     * Extended callback that also receives stage-start notifications.
     *
     * <p>This interface adds the ability to notify consumers when a new
     * processing stage begins, along with an estimated duration.</p>
     */
    public interface StageAwareCallback extends ProgressCallback {
        /**
         * Called when a new processing stage begins.
         *
         * @param stageName the name of the stage (e.g., "Conversion", "Transcription")
         * @param estimatedDurationSeconds the estimated duration of this stage in seconds
         */
        void onStageStart(String stageName, double estimatedDurationSeconds);
    }

    // =========================================================================
    //  VolumeAnalysis
    // =========================================================================

    /**
     * Holds FFmpeg volumedetect results and derives the recommended gain.
     *
     * <p>This class analyses volume statistics and determines whether amplification
     * is needed based on configurable thresholds.</p>
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

        /**
         * Constructs a new volume analysis from FFmpeg volumedetect results.
         *
         * @param maxVolume the maximum volume in dB
         * @param avgVolume the average (RMS) volume in dB
         * @param minVolume the minimum volume in dB
         */
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

        /**
         * Returns the maximum volume in dB.
         *
         * @return the maximum volume
         */
        public double getMaxVolume()        { return maxVolume; }

        /**
         * Returns the average (RMS) volume in dB.
         *
         * @return the average volume
         */
        public double getAvgVolume()        { return avgVolume; }

        /**
         * Returns the minimum volume in dB.
         *
         * @return the minimum volume
         */
        public double getMinVolume()        { return minVolume; }

        /**
         * Returns the dynamic range in dB (max - min).
         *
         * @return the dynamic range
         */
        public double getDynamicRange()     { return dynamicRange; }

        /**
         * Returns the recommended gain in dB.
         *
         * @return the recommended gain
         */
        public double getRecommendedGain()  { return recommendedGain; }

        /**
         * Indicates whether amplification is needed.
         *
         * @return {@code true} if amplification is recommended
         */
        public boolean needsAmplification() { return needsAmplification; }

        /**
         * Returns the recommendation text.
         *
         * @return a human-readable recommendation
         */
        public String getRecommendation()   { return recommendation; }

        /**
         * Returns the amplification factor (linear, not dB).
         *
         * @return the amplification factor
         */
        public double getAmplificationFactor() { return Math.pow(10, recommendedGain / 20.0); }

        @Override
        public String toString() {
            return String.format("VolumeAnalysis{max=%.1fdB, avg=%.1fdB, min=%.1fdB, gain=%.1fdB, needs=%s}",
                    maxVolume, avgVolume, minVolume, recommendedGain, needsAmplification);
        }
    }
}