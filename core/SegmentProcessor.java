/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.TranscriptionConfig;
import audiomanager.model.TranscriptionResult;
import audiomanager.model.TranscriptionSegment;
import audiomanager.util.TimeLeftEstimator;
import audiomanager.util.ProcessRunner;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Processes a long audio file by splitting it into segments, transcribing each
 * one, and merging the results.
 *
 * <p>This class handles audio files that are too long for direct transcription
 * by splitting them into manageable segments and processing each segment
 * independently. Key features include:
 * <ul>
 *   <li><b>Configurable segment duration:</b> Uses
 *       {@link TranscriptionConfig#getMaxSegmentDuration()} (default 30s)</li>
 *   <li><b>Resume capability:</b> Saves progress after each segment for crash
 *       recovery</li>
 *   <li><b>Segment retry:</b> Retries failed segments up to 3 times with
 *       exponential backoff</li>
 *   <li><b>Startup orphan sweep:</b> Cleans up orphaned work directories
 *       older than 24 hours</li>
 *   <li><b>Aggregated timing:</b> Summarises Python-side timings across all
 *       segments</li>
 *   <li><b>Self-correcting merge:</b> Anchors merged timestamps to actual
 *       segment endpoints</li>
 * </ul>
 *
 * <p><b>Temp directory management:</b> Work directories are created in the
 * system temp directory with the pattern {@code segment_work_*}. Orphaned
 * directories older than 24 hours are automatically swept on startup.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see WhisperXTranscriptionService
 * @see SegmentProgressListener
 */
public class SegmentProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentProcessor.class);

    /** Default segment length used when the config value is ≤ 0. */
    private static final int DEFAULT_SEGMENT_DURATION_SECONDS = 30;

    /** Max attempts (including the first) for a single segment before giving up. */
    private static final int MAX_RETRIES = 3;
    /** Base backoff between retry attempts; multiplied by the attempt number. */
    private static final long RETRY_DELAY_MS = 2000;

    /** Orphaned work directories older than this are swept on startup. */
    private static final long ORPHAN_MAX_AGE_MS = 24 * 60 * 60 * 1_000L; // 24 hours

    // Guard so the orphan sweep runs at most once per JVM session.
    private static volatile boolean orphanSweepDone = false;

    private final WhisperXTranscriptionService transcriptionService;
    private final DependencyManager dependencyManager;
    private final Gson gson;
    private final TimeLeftEstimator timeEstimator;
    private final SegmentProgressListener segmentListener;
    private ErrorReporter errorReporter = null;

    private Path workDir;

    // Aggregated timing data across all segments
    private final Map<String, Long> aggregatedStageTimingsMs = new LinkedHashMap<>();
    private double aggregatedPeakMemoryMb = -1;
    private double cpuPercentSum = 0;
    private int cpuPercentSampleCount = 0;

    // -------------------------------------------------------------------------
    //  Construction
    // -------------------------------------------------------------------------

    /**
     * Constructs a new SegmentProcessor.
     *
     * @param transcriptionService the transcription service for processing segments
     * @param dependencyManager the dependency manager for FFmpeg
     * @param timeEstimator the time estimator for progress tracking
     * @param listener the segment progress listener (may be {@code null})
     * @param errorReporter the error reporter for diagnostics (may be {@code null})
     */
    public SegmentProcessor(WhisperXTranscriptionService transcriptionService,
                            DependencyManager dependencyManager,
                            TimeLeftEstimator timeEstimator,
                            SegmentProgressListener listener, ErrorReporter errorReporter) {
        this.transcriptionService = transcriptionService;
        this.dependencyManager    = dependencyManager;
        this.timeEstimator        = timeEstimator;
        this.segmentListener      = listener;
        this.errorReporter = errorReporter;
        this.gson                 = new Gson();

        // Best-effort orphan sweep on first construction in this JVM session
        if (!orphanSweepDone) {
            orphanSweepDone = true;
            sweepOrphanedWorkDirs();
        }
    }

    /**
     * Returns the sum of Python-reported stage times across all segments.
     *
     * @return a map of stage names to total duration in milliseconds
     */
    public Map<String, Long> getAggregatedStageTimingsMs() {
        return new LinkedHashMap<>(aggregatedStageTimingsMs);
    }

    /**
     * Returns the highest single-segment peak memory usage observed.
     *
     * @return the peak memory in MB, or {@code -1} if unavailable
     */
    public double getAggregatedPeakMemoryMb() {
        return aggregatedPeakMemoryMb;
    }

    /**
     * Returns the mean of each segment's average CPU usage.
     *
     * @return the average CPU percentage, or {@code -1} if unavailable
     */
    public double getAggregatedAvgCpuPercent() {
        return cpuPercentSampleCount > 0 ? cpuPercentSum / cpuPercentSampleCount : -1;
    }

    /**
     * Resets aggregated timing data for a new file.
     */
    private void resetAggregates() {
        aggregatedStageTimingsMs.clear();
        aggregatedPeakMemoryMb = -1;
        cpuPercentSum = 0;
        cpuPercentSampleCount = 0;
    }

    /**
     * Accumulates timing data from the most recent segment.
     */
    private void accumulateSegmentTiming() {
        Map<String, Long> segmentStages = transcriptionService.getLastPythonStageTimingsMs();
        for (Map.Entry<String, Long> e : segmentStages.entrySet()) {
            aggregatedStageTimingsMs.merge(e.getKey(), e.getValue(), Long::sum);
        }
        double segPeakMb = transcriptionService.getLastPythonPeakMemoryMb();
        if (segPeakMb >= 0) {
            aggregatedPeakMemoryMb = Math.max(aggregatedPeakMemoryMb, segPeakMb);
        }
        double segAvgCpu = transcriptionService.getLastPythonAvgCpuPercent();
        if (segAvgCpu >= 0) {
            cpuPercentSum += segAvgCpu;
            cpuPercentSampleCount++;
        }
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Splits an audio file into segments, transcribes each one, and merges the results.
     *
     * <p>This method:
     * <ol>
     *   <li>Creates a work directory for this file</li>
     *   <li>Splits the audio into segments using FFmpeg</li>
     *   <li>Loads any previously completed segments for resume</li>
     *   <li>Transcribes each segment with retry logic</li>
     *   <li>Merges all segment results</li>
     *   <li>Cleans up temporary files</li>
     * </ol>
     *
     * @param audioFile the path to the source WAV file
     * @param config the transcription configuration
     * @param progressCallback the overall progress listener
     * @param audioDuration the total audio duration in seconds
     * @return the merged transcription result
     * @throws Exception if any unrecoverable error occurs
     */
    public TranscriptionResult processWithSegments(String audioFile,
                                                   TranscriptionConfig config,
                                                   AudioProcessor.ProgressCallback progressCallback,
                                                   double audioDuration) throws Exception {
        workDir = createWorkDir(audioFile);
        LOGGER.info("Segment work dir: {}", workDir);
        resetAggregates();

        int segmentDuration = config.getMaxSegmentDuration() > 0
                ? (int) config.getMaxSegmentDuration()
                : DEFAULT_SEGMENT_DURATION_SECONDS;
        LOGGER.info("Using segment duration: {}s", segmentDuration);

        // Step 1 — split
        List<Path> segmentFiles = splitAudio(audioFile, segmentDuration);
        int totalSegments = segmentFiles.size();
        String fileName = Paths.get(audioFile).getFileName().toString();

        if (timeEstimator != null) {
            double fileSizeMB = new File(audioFile).length() / (1024.0 * 1024.0);
            timeEstimator.startSegmentedFileProcessing(fileName, fileSizeMB,
                    config.getModel(), List.of("transcription_segment"), totalSegments);
        }
        LOGGER.info("Split into {} segments", totalSegments);

        // Step 2 — load resume state
        Path progressFile = workDir.resolve("progress.dat");
        Set<Integer> completedSegments = loadProgress(progressFile);

        // Step 3 — build per-segment config (disables nested segmentation)
        TranscriptionConfig segmentConfig = TranscriptionConfig.builder()
                .model(config.getModel())
                .language(config.getLanguage())
                .timestampsEnabled(config.isTimestampsEnabled())
                .confidenceEnabled(config.isConfidenceEnabled())
                .outputFormat(config.getOutputFormat())
                .volumeBoost(config.getVolumeBoost())
                .silenceThreshold(config.getSilenceThreshold())
                .silenceDuration(config.getSilenceDuration())
                .noiseReduction(config.isNoiseReduction())
                .srtMaxChars(config.getSrtMaxChars())
                .srtMaxLines(config.getSrtMaxLines())
                .diarizeEnabled(config.isDiarizeEnabled())
                .hfToken(config.getHfToken())
                .maxSegmentDuration(segmentDuration)
                .enabled(config.isEnabled())
                .skipSegmentation(true)   // prevent recursive segmentation
                .build();

        // Step 4 — transcribe each segment
        List<TranscriptionResult> segmentResults = new ArrayList<>();
        try {
            for (int i = 0; i < totalSegments; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("Segment processing interrupted at segment " + i);
                }

                Path segment = segmentFiles.get(i);

                if (completedSegments.contains(i)) {
                    LOGGER.info("Segment {} already done — loading cached result.", i);
                    segmentResults.add(loadSegmentResult(i));
                    if (progressCallback != null) {
                        progressCallback.updateProgress((double)(i + 1) / totalSegments);
                    }
                    continue;
                }

                LOGGER.info("Transcribing segment {}/{}", i + 1, totalSegments);
                final int idx = i;
                AudioProcessor.ProgressCallback segmentProgress = p -> {
                    if (progressCallback != null) {
                        double overall = (double) idx / totalSegments + (p / totalSegments);
                        progressCallback.updateProgress(overall);
                    }
                };

                long segStart = System.currentTimeMillis();
                TranscriptionResult result = transcribeSegmentWithRetry(
                        segment, i, totalSegments, segmentConfig, segmentProgress, segmentDuration);
                long segDurationMs = System.currentTimeMillis() - segStart;
                accumulateSegmentTiming();

                if (timeEstimator != null) timeEstimator.recordSegmentCompletion(fileName, segDurationMs);

                segmentResults.add(result);

                if (segmentListener != null) segmentListener.onSegmentCompleted(i, totalSegments);

                saveSegmentResult(i, result);
                markSegmentCompleted(progressFile, i);
            }
        } finally {
            if (timeEstimator != null) timeEstimator.completeFileProcessing(fileName);
        }

        // Step 5 — merge
        TranscriptionResult merged = mergeResults(segmentResults);

        // Step 6 — cleanup
        cleanup();

        return merged;
    }

    // -------------------------------------------------------------------------
    //  Private helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a work directory for the given audio file.
     */
    private Path createWorkDir(String audioFile) throws IOException {
        String baseName = Paths.get(audioFile).getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path dir = tempDir.resolve("segment_work_" + baseName + "_"
                + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Splits an audio file into segments using FFmpeg.
     */
    private List<Path> splitAudio(String audioFile, int segmentDuration) throws Exception {
        Path outputDir = workDir.resolve("segments");
        Files.createDirectories(outputDir);

        List<String> command = Arrays.asList(
                dependencyManager.getFFmpegPath(),
                "-i", audioFile,
                "-f", "segment",
                "-segment_time", String.valueOf(segmentDuration),
                "-c", "copy",
                outputDir.resolve("segment_%03d.wav").toString()
        );

        StringBuilder output = new StringBuilder();
        int exitCode = ProcessRunner.runCommand(command, 60, TimeUnit.SECONDS,
                line -> output.append(line).append("\n"), null);

        if (exitCode != 0) {
            throw new IOException("FFmpeg segment split failed: " + output);
        }

        List<Path> segments = Files.list(outputDir)
                .filter(p -> p.toString().endsWith(".wav"))
                .sorted()
                .collect(Collectors.toList());

        if (segments.isEmpty()) {
            throw new IOException("FFmpeg produced no segment files in: " + outputDir);
        }
        return segments;
    }

    /**
     * Loads progress from a progress file.
     */
    private Set<Integer> loadProgress(Path progressFile) throws IOException {
        Set<Integer> done = new HashSet<>();
        if (Files.exists(progressFile)) {
            for (String line : Files.readAllLines(progressFile)) {
                try { done.add(Integer.parseInt(line.trim())); }
                catch (NumberFormatException ignored) { }
            }
        }
        return done;
    }

    /**
     * Marks a segment as completed in the progress file.
     */
    private void markSegmentCompleted(Path progressFile, int index) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(progressFile,
                StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            writer.write(Integer.toString(index));
            writer.newLine();
        }
    }

    /**
     * Saves a segment result to disk.
     */
    private void saveSegmentResult(int index, TranscriptionResult result) throws IOException {
        Path resultFile = workDir.resolve("result_" + index + ".json");
        try (Writer writer = Files.newBufferedWriter(resultFile)) {
            gson.toJson(result, writer);
        }
    }

    /**
     * Loads a segment result from disk.
     */
    private TranscriptionResult loadSegmentResult(int index) throws IOException {
        Path resultFile = workDir.resolve("result_" + index + ".json");
        try (Reader reader = Files.newBufferedReader(resultFile)) {
            return gson.fromJson(reader, TranscriptionResult.class);
        }
    }

    /**
     * Transcribes a segment with retry logic.
     */
    private TranscriptionResult transcribeSegmentWithRetry(Path segment,
                                                            int index,
                                                            int totalSegments,
                                                            TranscriptionConfig segmentConfig,
                                                            AudioProcessor.ProgressCallback segmentProgress,
                                                            int segmentDuration) throws Exception {
        int retryCount = 0;
        while (true) {
            try {
                if (retryCount > 0) {
                    LOGGER.warn("Retrying segment {}/{} (attempt {}/{})",
                            index + 1, totalSegments, retryCount + 1, MAX_RETRIES);
                    Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
                }
                return transcriptionService.transcribe(
                        segment.toString(), segmentConfig, segmentProgress, segmentDuration);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                if (errorReporter != null && errorReporter.isEnabled()) {
                    errorReporter.reportError(e, "Segment transcription: " + segment + " (attempt " + retryCount + ")");
                }
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    LOGGER.error("Segment {}/{} failed after {} attempts: {}",
                            index + 1, totalSegments, MAX_RETRIES, e.getMessage());
                    throw e;
                }
                LOGGER.warn("Segment {}/{} failed (attempt {}/{}): {} - retrying...",
                        index + 1, totalSegments, retryCount, MAX_RETRIES, e.getMessage());
            }
        }
    }

    /**
     * Merges multiple segment results into a single transcription result.
     *
     * <p>This method anchors merged timestamps to the actual end of the
     * last real transcribed segment, providing self-correcting alignment
     * rather than summing segment durations that may drift.</p>
     */
    private TranscriptionResult mergeResults(List<TranscriptionResult> results) {
        if (results.isEmpty()) {
            return new TranscriptionResult("", "unknown", 0, Collections.emptyList());
        }

        StringBuilder fullText      = new StringBuilder();
        List<TranscriptionSegment> merged = new ArrayList<>();
        double timeOffset           = 0.0;
        String language             = results.get(0).getLanguage();

        for (TranscriptionResult res : results) {
            fullText.append(res.getText());
            boolean addedAny = false;
            for (TranscriptionSegment seg : res.getSegments()) {
                merged.add(new TranscriptionSegment(
                        seg.getStart() + timeOffset,
                        seg.getEnd()   + timeOffset,
                        seg.getText(),
                        seg.getConfidence()));
                addedAny = true;
            }
            if (addedAny) {
                timeOffset = merged.get(merged.size() - 1).getEnd();
            } else {
                timeOffset += res.getDuration();
            }
        }

        return new TranscriptionResult(fullText.toString(), language, timeOffset, merged);
    }

    /**
     * Deletes the work directory tree.
     *
     * <p>Failures are logged at {@code WARN} level to make orphaned temp
     * files visible for disk-space diagnosis.</p>
     */
    private void cleanup() {
        if (workDir == null || !Files.exists(workDir)) return;
        try {
            Files.walk(workDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LOGGER.warn("Could not delete temp file {} — {}",
                                    path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("Could not walk work dir for cleanup {}: {}", workDir, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    //  Startup orphan sweep
    // -------------------------------------------------------------------------

    /**
     * Scans the system temp directory for orphaned work directories older
     * than 24 hours and deletes them.
     *
     * <p>Called once per JVM at first {@link SegmentProcessor} construction.</p>
     */
    static void sweepOrphanedWorkDirs() {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        long cutoff  = System.currentTimeMillis() - ORPHAN_MAX_AGE_MS;
        try {
            Files.list(tempDir)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("segment_work_") || name.startsWith("whisperx_output_");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoff;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(SegmentProcessor::deleteTree);
        } catch (IOException e) {
            LOGGER.debug("Orphan sweep failed: {}", e.getMessage());
        }
    }

    private static void deleteTree(Path root) {
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOGGER.debug("Orphan sweep: could not delete {}: {}", p, e.getMessage());
                        }
                    });
            LOGGER.info("Orphan sweep: deleted {}", root);
        } catch (IOException e) {
            LOGGER.warn("Orphan sweep: could not walk {}: {}", root, e.getMessage());
        }
    }
}