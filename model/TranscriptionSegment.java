/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

/**
 * Represents a single segment of transcribed audio.
 *
 * <h2>Speaker diarization (fixed)</h2>
 * This class now implements {@link SpeakerAwareSegment} directly and carries
 * an actual {@code speaker} field. Previously it had no speaker field at
 * all: {@code WhisperXTranscriptionService.parseWhisperXOutput()} discarded
 * the {@code "speaker"} property that WhisperX writes into every segment
 * once diarization runs, so no {@code TranscriptionSegment} instance
 * produced by this app could ever report a speaker. That made the entire
 * speaker-summary feature in {@code TranscriptionOutputWriter} silently dead
 * code — {@code hasSpeaker()} could never return {@code true} for any real
 * segment, regardless of whether diarization was enabled in config. Fixed by
 * threading the speaker label through from JSON parsing to output.
 *
 * <p>{@code confidence} is now {@code private} (it was package-private
 * before, allowing accidental mutation from anywhere in
 * {@code audiomanager.model} without going through {@link #setConfidence}).</p>
 */
public class TranscriptionSegment implements SpeakerAwareSegment {
    private final double start;
    private final double end;
    private final String text;
    private Double confidence;
    private String speaker;

    /** Backward-compatible constructor for callers with no speaker info. */
    public TranscriptionSegment(double start, double end, String text, Double confidence) {
        this(start, end, text, confidence, null);
    }

    public TranscriptionSegment(double start, double end, String text, Double confidence, String speaker) {
        this.start = start;
        this.end = end;
        this.text = text;
        this.confidence = confidence;
        this.speaker = speaker;
    }

    // Getters
    public double getStart() { return start; }
    public double getEnd() { return end; }
    public String getText() { return text; }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Double getConfidence() {
        return confidence;
    }

    @Override
    public String getSpeaker() {
        return speaker;
    }

    /** Set once diarization assigns this segment a speaker label. */
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