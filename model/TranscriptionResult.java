/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import java.util.List;

/**
 * Result of a transcription operation.
 *
 * <p>This class holds the full transcription output, including:
 * <ul>
 *   <li><b>Full text:</b> The complete transcribed text as a single string</li>
 *   <li><b>Segments:</b> A list of timestamped {@link TranscriptionSegment} objects</li>
 *   <li><b>Language:</b> The detected or specified language</li>
 *   <li><b>Duration:</b> The total audio duration in seconds</li>
 * </ul>
 *
 * <p><b>Immutability:</b> This class is immutable once constructed. Use
 * {@link #withSegments(List)} to create a modified copy.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see TranscriptionSegment
 */
public class TranscriptionResult {
    private final String text;
    private final String language;
    private final double duration;
    private final List<TranscriptionSegment> segments;

    /**
     * Constructs a new TranscriptionResult.
     *
     * @param text the full transcribed text
     * @param language the language of the transcription
     * @param duration the audio duration in seconds
     * @param segments the list of transcription segments
     */
    public TranscriptionResult(String text, String language, double duration,
                              List<TranscriptionSegment> segments) {
        this.text = text;
        this.language = language;
        this.duration = duration;
        this.segments = segments;
    }

    /**
     * Returns the full transcribed text.
     *
     * @return the transcribed text
     */
    public String getText() { return text; }

    /**
     * Returns the language of the transcription.
     *
     * @return the language code
     */
    public String getLanguage() { return language; }

    /**
     * Returns the audio duration in seconds.
     *
     * @return the duration in seconds
     */
    public double getDuration() { return duration; }

    /**
     * Returns the list of transcription segments.
     *
     * @return the list of segments
     */
    public List<TranscriptionSegment> getSegments() { return segments; }

    /**
     * Returns a NEW TranscriptionResult with {@code newSegments} in place of
     * this one's segments.
     *
     * <p>Everything else (language, duration) is carried over unchanged.
     * This class has no setters (all fields are final), so any caller that
     * needs to modify segments and return a result — e.g. TranslationService,
     * which needs to hand back a translated copy without mutating the
     * original — needs this rather than a full manual reconstruction at
     * every call site.</p>
     *
     * @param newSegments the new list of segments
     * @return a new TranscriptionResult with the specified segments
     */
    public TranscriptionResult withSegments(List<TranscriptionSegment> newSegments) {
        return new TranscriptionResult(this.text, this.language, this.duration, newSegments);
    }

    @Override
    public String toString() {
        return String.format("TranscriptionResult{language='%s', duration=%.2f, segments=%d}",
                           language, duration, segments.size());
    }
}