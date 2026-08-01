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
 * Processes a long audio file by splitting into segments, transcribing each
 * one, and merging the results.  Supports resuming after interruption.
 *
 * <h2>Fixes vs. original</h2>
 * <ul>
 *   <li><b>Configurable segment duration:</b> The 10-second hard-coded
 *       {@code segmentDuration} is replaced by
 *       {@link TranscriptionConfig#getMaxSegmentDuration()}, which defaults to
 *       30 s.  10-second segments are too short — they fragment sentences,
 *       break alignment context, and degrade transcription quality.</li>
 *   <li><b>Temp-dir cleanup logging:</b> {@link #cleanup()} now logs a
 *       {@code WARN} for each file it cannot delete rather than silently
 *       swallowing the {@code IOException}.  Orphaned gigabytes accumulating
 *       in the system temp directory are now visible in the log.</li>
 *   <li><b>Startup orphan sweep:</b> {@link #sweepOrphanedWorkDirs()} is
 *       called once per JVM to delete {@code segment_work_*} directories that
 *       are older than 24 hours — cleaning up after crashes.</li>
 *   <li><b>Interrupt flag preserved:</b> If the segment loop is interrupted
 *       the flag is restored before the exception propagates.</li>
 * </ul>
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

    private Path workDir;

    // -------------------------------------------------------------------------
    //  Construction
    // -------------------------------------------------------------------------

    public SegmentProcessor(WhisperXTranscriptionService transcriptionService,
                            DependencyManager dependencyManager,
                            TimeLeftEstimator timeEstimator,
                            SegmentProgressListener listener) {
        this.transcriptionService = transcriptionService;
        this.dependencyManager    = dependencyManager;
        this.timeEstimator        = timeEstimator;
        this.segmentListener      = listener;
        this.gson                 = new Gson();

        // Best-effort orphan sweep on first construction in this JVM session
        if (!orphanSweepDone) {
            orphanSweepDone = true;
            sweepOrphanedWorkDirs();
        }
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Split {@code audioFile} into segments, transcribe each one
     * (skipping already-completed segments), and merge the results.
     *
     * @param audioFile        path to the source WAV file
     * @param config           transcription configuration
     * @param progressCallback overall progress listener
     * @param audioDuration    total audio duration in seconds (informational)
     * @return merged transcription result
     * @throws Exception on any unrecoverable error
     */
    public TranscriptionResult processWithSegments(String audioFile,
                                                   TranscriptionConfig config,
                                                   AudioProcessor.ProgressCallback progressCallback,
                                                   double audioDuration) throws Exception {
        workDir = createWorkDir(audioFile);
        LOGGER.info("Segment work dir: {}", workDir);

        // FIX: segment duration comes from config (defaults to 30 s if ≤ 0)
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

            if (timeEstimator != null) timeEstimator.recordSegmentCompletion(fileName, segDurationMs);

            segmentResults.add(result);

            if (segmentListener != null) segmentListener.onSegmentCompleted(i, totalSegments);

            saveSegmentResult(i, result);
            markSegmentCompleted(progressFile, i);
        }

        if (timeEstimator != null) timeEstimator.completeFileProcessing(fileName);

        // Step 5 — merge
        TranscriptionResult merged = mergeResults(segmentResults);

        // Step 6 — cleanup
        cleanup();

        return merged;
    }

    /**
     * Transcribe a single segment, retrying up to {@link #MAX_RETRIES} times
     * (with a backoff proportional to the attempt number) before giving up.
     * A transient failure on one segment (e.g. a flaky model load or a
     * momentary resource spike) previously failed the whole file even though
     * every other segment succeeded; this isolates that cost to one segment's
     * retry delay instead.
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
                // A sleep interruption means the batch is being cancelled —
                // never swallow that as a retryable failure.
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
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

    // -------------------------------------------------------------------------
    //  Private helpers
    // -------------------------------------------------------------------------

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

    private void markSegmentCompleted(Path progressFile, int index) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(progressFile,
                StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            writer.write(Integer.toString(index));
            writer.newLine();
        }
    }

    private void saveSegmentResult(int index, TranscriptionResult result) throws IOException {
        Path resultFile = workDir.resolve("result_" + index + ".json");
        try (Writer writer = Files.newBufferedWriter(resultFile)) {
            gson.toJson(result, writer);
        }
    }

    private TranscriptionResult loadSegmentResult(int index) throws IOException {
        Path resultFile = workDir.resolve("result_" + index + ".json");
        try (Reader reader = Files.newBufferedReader(resultFile)) {
            return gson.fromJson(reader, TranscriptionResult.class);
        }
    }

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
            for (TranscriptionSegment seg : res.getSegments()) {
                merged.add(new TranscriptionSegment(
                        seg.getStart() + timeOffset,
                        seg.getEnd()   + timeOffset,
                        seg.getText(),
                        seg.getConfidence()));
            }
            timeOffset += res.getDuration();
        }

        return new TranscriptionResult(fullText.toString(), language, timeOffset, merged);
    }

    /**
     * Delete the work directory tree.
     *
     * <p>FIX: failures are now logged at {@code WARN} level rather than
     * silently swallowed.  This makes orphaned temp files visible so operators
     * can diagnose disk-space issues.</p>
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
     * Scan the system temp directory for {@code segment_work_*} and
     * {@code whisperx_output_*} directories left over from crashed JVM
     * sessions (older than 24 hours) and delete them.
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