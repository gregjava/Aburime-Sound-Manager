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
    //  Export — Word / PDF
    // -------------------------------------------------------------------------

    /**
     * Export a transcription as a genuine {@code .docx} file that Word opens
     * natively — not an HTML file renamed/mislabeled as "Word-compatible".
     *
     * <p>FIX: the previous version of this method wrote HTML and called it
     * a Word export. That's functional (Word *can* open HTML), but it's
     * misleading — a user who asks for "Word export" and gets a file that
     * isn't really a Word document will notice, e.g. if they try to edit
     * and re-save it, or inspect the file type. This writes real minimal
     * OOXML (the ZIP-based format Word actually uses) directly via
     * {@code java.util.zip}, so no new dependency (e.g. Apache POI) is
     * needed — a {@code .docx} only strictly requires three parts:
     * {@code [Content_Types].xml}, {@code _rels/.rels}, and
     * {@code word/document.xml}. This produces exactly those three,
     * respecting the same speaker/timestamp formatting as the SRT output,
     * with each segment as its own paragraph.</p>
     *
     * @param result     the transcription result to export
     * @param outputPath destination file path (should end in {@code .docx})
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

    private void writeZipEntry(java.util.zip.ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String docxParagraph(String text, boolean bold) {
        String run = bold
                ? "<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r>"
                : "<w:r><w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r>";
        return "<w:p>" + run + "</w:p>";
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Export the transcription as HTML — kept as its own method (rather than
     * folded into {@link #exportToWord}) since it's genuinely useful on its
     * own merits: any browser can open it, and it's the fallback
     * {@link #exportToPDF} prints from.
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
     * Export a transcription as a PDF, if a PDF-generation library is
     * available on the classpath at runtime; otherwise falls back to
     * {@link #exportToHtml}, which can be printed to PDF from any browser
     * or word processor's "Print to PDF" option.
     *
     * <p>This project doesn't currently depend on a PDF library (e.g.
     * Apache PDFBox/iText), so true native PDF generation isn't wired up
     * here — adding it would mean adding that dependency to the build
     * first. The HTML fallback keeps this method usable in the meantime
     * without introducing an untested/unavailable dependency.</p>
     *
     * @param result     the transcription result to export
     * @param outputPath desired {@code .pdf} destination path
     * @return the file actually written (HTML fallback unless/until a PDF
     *         library is added to the project)
     */
    public File exportToPDF(TranscriptionResult result, String outputPath) throws IOException {
        String htmlPath = outputPath.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? outputPath.substring(0, outputPath.length() - 4) + ".html"
                : outputPath + ".html";
        LOGGER.warn("No PDF library is configured in this build — writing an HTML file "
                + "instead, which can be printed to PDF from a browser or Word: {}", htmlPath);
        return exportToHtml(result, htmlPath);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatDuration(double seconds) {
        long totalSeconds = (long) seconds;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long secs = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%d:%02d", minutes, secs);
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