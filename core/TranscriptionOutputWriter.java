/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.SpeakerAwareSegment;
import audiomanager.model.TranscriptionConfig;
import audiomanager.model.TranscriptionResult;
import audiomanager.model.TranscriptionSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Handles all transcription output writing — SRT, plain text, speaker summaries,
 * and confidence analysis.
 *
 * <p>Extracted from {@link BatchProcessor} to adhere to the Single Responsibility
 * Principle.  Previously the writing logic (~200 lines) lived directly in
 * {@code BatchProcessor}, mixing concerns and making the class impossible to test
 * independently.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TranscriptionOutputWriter writer = new TranscriptionOutputWriter();
 * File out = writer.save("interview.mp3", result, config, "/output/dir");
 * }</pre>
 */
public final class TranscriptionOutputWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionOutputWriter.class);

    /**
     * Write the transcription result to the output directory and return the
     * created file.
     *
     * @param originalFileName the original audio file name (used for naming)
     * @param result           the transcription result to serialise
     * @param config           transcription configuration (controls format)
     * @param outputDir        target directory path
     * @return the created output file
     * @throws IOException if the file cannot be written
     */
    public File save(String originalFileName,
                     TranscriptionResult result,
                     TranscriptionConfig config,
                     String outputDir) throws IOException {

        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        String baseName  = getFileNameWithoutExtension(originalFileName);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String threadId  = String.valueOf(Thread.currentThread().getId());
        String uniqueId  = baseName + "_" + timestamp + "_" + threadId;

        String ext      = config.isTimestampsEnabled() ? "srt" : "txt";
        Path outputFile = outputPath.resolve(uniqueId + "." + ext);

        LOGGER.info("Saving transcription to: {}", outputFile.toAbsolutePath());

        try (PrintWriter writer = new PrintWriter(outputFile.toFile(), StandardCharsets.UTF_8.name())) {
            if (config.isTimestampsEnabled()) {
                writeSrt(writer, result, config);
            } else {
                writePlainText(writer, result, config);
            }
        }

        LOGGER.info("Transcription saved: {}", outputFile.toAbsolutePath());
        return outputFile.toFile();
    }

    // -------------------------------------------------------------------------
    //  SRT output
    // -------------------------------------------------------------------------

    private void writeSrt(PrintWriter writer, TranscriptionResult result, TranscriptionConfig config) {
        int index = 1;
        for (TranscriptionSegment segment : result.getSegments()) {
            writer.println(index++);
            writer.println(formatTimestamp(segment.getStart()) + " --> " + formatTimestamp(segment.getEnd()));

            if (hasSpeaker(segment)) {
                writer.println("[" + getSpeaker(segment) + "]");
            }

            writer.println(segment.getText().trim());

            if (config.isConfidenceEnabled() && segment.getConfidence() != null) {
                writer.printf("(Confidence: %.2f)%n", segment.getConfidence());
            }
            writer.println();
        }

        if (hasMultipleSpeakers(result)) {
            writeSpeakerSummary(writer, result);
        }
    }

    // -------------------------------------------------------------------------
    //  Plain-text output
    // -------------------------------------------------------------------------

    private void writePlainText(PrintWriter writer, TranscriptionResult result, TranscriptionConfig config) {
        writer.print(result.getText());

        if (hasMultipleSpeakers(result)) {
            writeSpeakerSummary(writer, result);
        }
        if (config.isConfidenceEnabled()) {
            writeConfidenceAnalysis(writer, result);
        }
    }

    // -------------------------------------------------------------------------
    //  Speaker helpers
    // -------------------------------------------------------------------------

    /**
     * Test whether the segment carries speaker information.
     *
     * <p>FIX: previously fell back to reflection
     * ({@code getClass().getMethod("getSpeaker")}) for any segment that
     * wasn't an instance of the (formerly core-layer-only) marker interface —
     * a fallback that could never actually succeed, since no
     * {@code TranscriptionSegment} ever implemented it and the JSON parser
     * that builds segments discarded WhisperX's {@code "speaker"} field
     * before it ever reached this class. {@link TranscriptionSegment} now
     * implements {@link SpeakerAwareSegment} directly and the parser
     * populates it, so a plain interface check is correct and sufficient —
     * the reflection path added risk (swallowed exceptions, no compile-time
     * safety) without ever adding real capability.</p>
     */
    private boolean hasSpeaker(TranscriptionSegment segment) {
        String speaker = segment.getSpeaker();
        return speaker != null && !speaker.isBlank();
    }

    private String getSpeaker(TranscriptionSegment segment) {
        String speaker = segment.getSpeaker();
        return (speaker != null && !speaker.isBlank()) ? speaker : "SPEAKER_00";
    }

    private boolean hasMultipleSpeakers(TranscriptionResult result) {
        Set<String> speakers = new HashSet<>();
        for (TranscriptionSegment seg : result.getSegments()) {
            if (hasSpeaker(seg)) {
                speakers.add(getSpeaker(seg));
                if (speakers.size() > 1) return true;
            }
        }
        return false;
    }

    private void writeSpeakerSummary(PrintWriter writer, TranscriptionResult result) {
        Map<String, Double> speakerTimes = new LinkedHashMap<>();
        for (TranscriptionSegment seg : result.getSegments()) {
            if (hasSpeaker(seg)) {
                String speaker = getSpeaker(seg);
                double dur = seg.getEnd() - seg.getStart();
                speakerTimes.merge(speaker, dur, Double::sum);
            }
        }
        if (speakerTimes.size() > 1) {
            writer.println("\n\n--- Speaker Analysis ---");
            writer.println("Total Speakers: " + speakerTimes.size());
            speakerTimes.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .forEach(e -> {
                        double pct = (e.getValue() / result.getDuration()) * 100.0;
                        writer.printf("%s: %.1f seconds (%.1f%%)%n", e.getKey(), e.getValue(), pct);
                    });
        }
    }

    // -------------------------------------------------------------------------
    //  Confidence analysis
    // -------------------------------------------------------------------------

    private void writeConfidenceAnalysis(PrintWriter writer, TranscriptionResult result) {
        OptionalDouble avg = result.getSegments().stream()
                .filter(s -> s.getConfidence() != null)
                .mapToDouble(TranscriptionSegment::getConfidence)
                .average();

        if (avg.isPresent()) {
            writer.printf("%n%n--- Analysis ---%n");
            writer.printf("Average Confidence: %.2f%n", avg.getAsDouble());
            writer.printf("Language: %s%n", result.getLanguage());
        }
    }

    // -------------------------------------------------------------------------
    //  Timestamp formatting
    // -------------------------------------------------------------------------

    private String formatTimestamp(double seconds) {
        long totalMillis = (long)(seconds * 1000);
        long hours   = TimeUnit.MILLISECONDS.toHours(totalMillis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60;
        long secs    = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % 60;
        long millis  = totalMillis % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }

    // -------------------------------------------------------------------------
    //  Utilities
    // -------------------------------------------------------------------------

    private String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }
}