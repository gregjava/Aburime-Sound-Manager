/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

/**
 * Represents a single segment of transcribed audio.
 *
 * <p>This class holds the data for a portion of the transcription, including:
 * <ul>
 *   <li><b>Timing:</b> Start and end time in seconds</li>
 *   <li><b>Text:</b> The transcribed text for this segment</li>
 *   <li><b>Confidence:</b> The model's confidence score (0.0 to 1.0)</li>
 *   <li><b>Speaker:</b> The speaker identifier if diarisation is enabled</li>
 * </ul>
 *
 * <p><b>Speaker diarisation (fixed):</b> This class now implements
 * {@link SpeakerAwareSegment} directly and carries an actual {@code speaker}
 * field. Previously it had no speaker field at all — any segment produced by
 * this app could never report a speaker. The speaker label is now threaded
 * through from JSON parsing to output.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see TranscriptionResult
 * @see SpeakerAwareSegment
 */
public class TranscriptionSegment implements SpeakerAwareSegment {
    private final double start;
    private final double end;
    private final String text;
    private Double confidence;
    private String speaker;

    /**
     * Constructs a new TranscriptionSegment without speaker information.
     *
     * @param start the start time in seconds
     * @param end the end time in seconds
     * @param text the transcribed text for this segment
     * @param confidence the confidence score (may be {@code null})
     */
    public TranscriptionSegment(double start, double end, String text, Double confidence) {
        this(start, end, text, confidence, null);
    }

    /**
     * Constructs a new TranscriptionSegment with speaker information.
     *
     * @param start the start time in seconds
     * @param end the end time in seconds
     * @param text the transcribed text for this segment
     * @param confidence the confidence score (may be {@code null})
     * @param speaker the speaker identifier (may be {@code null})
     */
    public TranscriptionSegment(double start, double end, String text, Double confidence, String speaker) {
        this.start = start;
        this.end = end;
        this.text = text;
        this.confidence = confidence;
        this.speaker = speaker;
    }

    /**
     * Returns the start time in seconds.
     *
     * @return the start time
     */
    public double getStart() { return start; }

    /**
     * Returns the end time in seconds.
     *
     * @return the end time
     */
    public double getEnd() { return end; }

    /**
     * Returns the transcribed text for this segment.
     *
     * @return the transcribed text
     */
    public String getText() { return text; }

    /**
     * Sets the confidence score for this segment.
     *
     * @param confidence the confidence score (0.0 to 1.0)
     */
    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    /**
     * Returns the confidence score for this segment.
     *
     * @return the confidence score, or {@code null} if not available
     */
    public Double getConfidence() {
        return confidence;
    }

    /**
     * Returns the speaker identifier for this segment.
     *
     * @return the speaker identifier, or {@code null} if not available
     */
    @Override
    public String getSpeaker() {
        return speaker;
    }

    /**
     * Sets the speaker identifier for this segment.
     *
     * <p>Called once when diarisation assigns this segment a speaker label.</p>
     *
     * @param speaker the speaker identifier
     */
    public void setSpeaker(String speaker) {
        this.speaker = speaker;
    }

    @Override
    public String toString() {
        return String.format("TranscriptionSegment{start=%.2f, end=%.2f, text='%s', confidence=%s, speaker=%s}",
                           start, end, text,
                           confidence != null ? String.format("%.2f", confidence) : "null",
                           speaker != null ? speaker : "null");
    }
}