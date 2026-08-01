/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EnhancedTranscriptionResult extends TranscriptionResult {
    private final Map<String, List<TranscriptionSegment>> segmentsBySpeaker;
    private final int speakerCount;
    private final boolean hasSpeakerDiarization;
    
    public EnhancedTranscriptionResult(String text, String language, double duration, 
                                     List<TranscriptionSegment> segments) {
        super(text, language, duration, segments);
        this.segmentsBySpeaker = groupSegmentsBySpeaker(segments);
        this.speakerCount = segmentsBySpeaker.size();
        this.hasSpeakerDiarization = speakerCount > 1;
    }
    
    private Map<String, List<TranscriptionSegment>> groupSegmentsBySpeaker(List<TranscriptionSegment> segments) {
        return segments.stream()
            .filter(segment -> segment instanceof EnhancedTranscriptionSegment)
            .map(segment -> (EnhancedTranscriptionSegment) segment)
            .filter(segment -> segment.getSpeaker() != null && !segment.getSpeaker().isEmpty())
            .collect(Collectors.groupingBy(EnhancedTranscriptionSegment::getSpeaker, Collectors.mapping(
                Function.identity(),
                Collectors.toList()
            )
        ));
    }
    
    public Map<String, List<TranscriptionSegment>> getSegmentsBySpeaker() {
        return segmentsBySpeaker;
    }
    
    public int getSpeakerCount() {
        return speakerCount;
    }
    
    public boolean hasSpeakerDiarization() {
        return hasSpeakerDiarization;
    }
    
    public List<String> getSpeakers() {
        return new ArrayList<>(segmentsBySpeaker.keySet());
    }
    
    public String getTextForSpeaker(String speaker) {
        return segmentsBySpeaker.getOrDefault(speaker, new ArrayList<>()).stream()
            .map(TranscriptionSegment::getText)
            .collect(Collectors.joining(" "));
    }
    
    public double getSpeakingTimeForSpeaker(String speaker) {
        return segmentsBySpeaker.getOrDefault(speaker, new ArrayList<>()).stream()
            .mapToDouble(segment -> segment.getEnd() - segment.getStart())
            .sum();
    }
}