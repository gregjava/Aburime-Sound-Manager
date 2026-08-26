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
 * <p>This class provides comprehensive output formatting for transcription results:
 * <ul>
 *   <li><b>SRT subtitles:</b> Timestamped subtitle format with speaker labels</li>
 *   <li><b>Plain text:</b> Raw text output with optional speaker summaries</li>
 *   <li><b>Speaker analysis:</b> Per-speaker duration summaries</li>
 *   <li><b>Confidence analysis:</b> Average confidence and language detection</li>
 *   <li><b>Word export:</b> Real {@code .docx} format (minimal OOXML)</li>
 *   <li><b>PDF export:</b> HTML fallback (printable to PDF from browser)</li>
 * </ul>
 *
 * <p><b>Thread-safety:</b> This class is stateless and thread-safe.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see TranscriptionResult
 * @see TranscriptionConfig
 */
public final class TranscriptionOutputWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionOutputWriter.class);

    // ========================================================================
    //  Main Save Method
    // ========================================================================

    /**
     * Writes the transcription result to the output directory.
     *
     * <p>The output format is determined by the configuration:
     * <ul>
     *   <li>If timestamps are enabled, writes SRT format</li>
     *   <li>Otherwise, writes plain text</li>
     * </ul>
     *
     * @param originalFileName the original audio file name (used for naming)
     * @param result the transcription result to serialise
     * @param config the transcription configuration (controls format)
     * @param outputDir the target directory path
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

    // ========================================================================
    //  SRT Output
    // ========================================================================

    /**
     * Writes the transcription in SRT subtitle format.
     *
     * @param writer the output writer
     * @param result the transcription result
     * @param config the transcription configuration
     */
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

    // ========================================================================
    //  Plain-Text Output
    // ========================================================================

    /**
     * Writes the transcription in plain text format.
     *
     * @param writer the output writer
     * @param result the transcription result
     * @param config the transcription configuration
     */
    private void writePlainText(PrintWriter writer, TranscriptionResult result, TranscriptionConfig config) {
        writer.print(result.getText());

        if (hasMultipleSpeakers(result)) {
            writeSpeakerSummary(writer, result);
        }
        if (config.isConfidenceEnabled()) {
            writeConfidenceAnalysis(writer, result);
        }
    }

    // ========================================================================
    //  Speaker Helpers
    // ========================================================================

    /**
     * Checks whether a segment carries speaker information.
     *
     * @param segment the segment to check
     * @return {@code true} if the segment has speaker information
     */
    private boolean hasSpeaker(TranscriptionSegment segment) {
        String speaker = segment.getSpeaker();
        return speaker != null && !speaker.isBlank();
    }

    /**
     * Returns the speaker name from a segment.
     *
     * @param segment the segment
     * @return the speaker name, or "SPEAKER_00" if not available
     */
    private String getSpeaker(TranscriptionSegment segment) {
        String speaker = segment.getSpeaker();
        return (speaker != null && !speaker.isBlank()) ? speaker : "SPEAKER_00";
    }

    /**
     * Checks whether a transcription has multiple speakers.
     *
     * @param result the transcription result
     * @return {@code true} if multiple speakers are detected
     */
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

    /**
     * Writes a speaker duration summary.
     *
     * @param writer the output writer
     * @param result the transcription result
     */
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

    // ========================================================================
    //  Confidence Analysis
    // ========================================================================

    /**
     * Writes confidence analysis for the transcription.
     *
     * @param writer the output writer
     * @param result the transcription result
     */
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

    // ========================================================================
    //  Export — Word / PDF
    // ========================================================================

    /**
     * Exports a transcription as a genuine {@code .docx} file that Word opens natively.
     *
     * <p>This method writes minimal OOXML (the ZIP-based format Word actually uses)
     * directly via {@code java.util.zip}, so no new dependency is needed.</p>
     *
     * @param result the transcription result to export
     * @param outputPath the destination file path (should end in {@code .docx})
     * @return the created file
     * @throws IOException if the file cannot be written
     */
    public File exportToWord(TranscriptionResult result, String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        StringBuilder body = new StringBuilder();
        body.append(docxParagraph("Transcription", true));
        body.append(docxParagraph("Language: " + nullToEmpty(result.getLanguage()), false));
        body.append(docxParagraph("Duration: " + formatDuration(result.getDuration()), false));

        for (TranscriptionSegment seg : result.getSegments()) {
            String prefix = "[" + formatTimestamp(seg.getStart()) + "] "
                    + (hasSpeaker(seg) ? getSpeaker(seg) + ": " : "");
            body.append(docxParagraph(prefix + nullToEmpty(seg.getText()).trim(), false));
        }

        String documentXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>";

        String contentTypesXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" "
                + "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";

        String relsXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" "
                + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
                + "Target=\"word/document.xml\"/></Relationships>";

        try (java.util.zip.ZipOutputStream zip =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            writeZipEntry(zip, "[Content_Types].xml", contentTypesXml);
            writeZipEntry(zip, "_rels/.rels", relsXml);
            writeZipEntry(zip, "word/document.xml", documentXml);
        }

        LOGGER.info("Exported .docx transcription to: {}", path.toAbsolutePath());
        return path.toFile();
    }

    /**
     * Exports the transcription as HTML for browser viewing.
     *
     * @param result the transcription result
     * @param outputPath the destination file path
     * @return the created file
     * @throws IOException if the file cannot be written
     */
    public File exportToHtml(TranscriptionResult result, String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        try (PrintWriter writer = new PrintWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
            writer.println("<title>Transcription</title></head><body>");
            writer.println("<h1>Transcription</h1>");
            writer.println("<p><b>Language:</b> " + escapeHtml(result.getLanguage()) + "</p>");
            writer.println("<p><b>Duration:</b> " + formatDuration(result.getDuration()) + "</p>");
            writer.println("<hr><div style='font-size:14px;line-height:1.6;'>");
            for (TranscriptionSegment seg : result.getSegments()) {
                writer.println("<p><b>[" + formatTimestamp(seg.getStart()) + "]</b> "
                        + (hasSpeaker(seg) ? "<i>" + escapeHtml(getSpeaker(seg)) + ":</i> " : "")
                        + escapeHtml(seg.getText()) + "</p>");
            }
            writer.println("</div></body></html>");
        }

        LOGGER.info("Exported HTML transcription to: {}", path.toAbsolutePath());
        return path.toFile();
    }

    /**
     * Exports a transcription as a PDF (fallback to HTML).
     *
     * <p>This project doesn't currently depend on a PDF library, so this
     * method writes HTML which can be printed to PDF from any browser.</p>
     *
     * @param result the transcription result to export
     * @param outputPath the desired {@code .pdf} destination path
     * @return the file actually written (HTML fallback)
     * @throws IOException if the file cannot be written
     */
    public File exportToPDF(TranscriptionResult result, String outputPath) throws IOException {
        String htmlPath = outputPath.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? outputPath.substring(0, outputPath.length() - 4) + ".html"
                : outputPath + ".html";
        LOGGER.warn("No PDF library is configured in this build — writing an HTML file "
                + "instead, which can be printed to PDF from a browser or Word: {}", htmlPath);
        return exportToHtml(result, htmlPath);
    }

    // ========================================================================
    //  Private Helpers
    // ========================================================================

    /**
     * Writes a zip entry with the given content.
     */
    private void writeZipEntry(java.util.zip.ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Creates a Word paragraph XML element.
     */
    private String docxParagraph(String text, boolean bold) {
        String run = bold
                ? "<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r>"
                : "<w:r><w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r>";
        return "<w:p>" + run + "</w:p>";
    }

    /**
     * Escapes XML special characters.
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Escapes HTML special characters.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Returns an empty string if the input is null.
     */
    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Formats a duration in seconds to a human-readable string.
     */
    private String formatDuration(double seconds) {
        long totalSeconds = (long) seconds;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long secs = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%d:%02d", minutes, secs);
    }

    /**
     * Formats a timestamp in SRT format (HH:MM:SS,mmm).
     */
    private String formatTimestamp(double seconds) {
        long totalMillis = (long)(seconds * 1000);
        long hours   = TimeUnit.MILLISECONDS.toHours(totalMillis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60;
        long secs    = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % 60;
        long millis  = totalMillis % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }

    /**
     * Returns the file name without its extension.
     */
    private String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }
}