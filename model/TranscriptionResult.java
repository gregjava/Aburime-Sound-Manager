/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import java.util.List;

/**
 * Result of a transcription operation
 */
public class TranscriptionResult {
    private final String text;
    private final String language;
    private final double duration;
    private final List<TranscriptionSegment> segments;

    public TranscriptionResult(String text, String language, double duration, 
                              List<TranscriptionSegment> segments) {
        this.text = text;
        this.language = language;
        this.duration = duration;
        this.segments = segments;
    }

    // Getters
    public String getText() { return text; }
    public String getLanguage() { return language; }
    public double getDuration() { return duration; }
    public List<TranscriptionSegment> getSegments() { return segments; }

    /**
     * Returns a NEW TranscriptionResult with {@code newSegments} in place of
     * this one's segments — everything else (language, duration) carried
     * over unchanged. This class has no setters (all fields are final), so
     * any "modify and get a result back" caller — e.g. TranslationService,
     * which needs to hand back a translated copy without mutating the
     * original — needs this rather than a full manual reconstruction at
     * every call site.
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