/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import java.util.ArrayList;
import java.util.List;

public class EnhancedTranscriptionSegment extends TranscriptionSegment {
    private String speaker;
    private List<WordTimestamp> wordTimestamps;
    
    public EnhancedTranscriptionSegment(double start, double end, String text, Double confidence) {
        super(start, end, text, confidence);
        this.wordTimestamps = new ArrayList<>();
    }
    
    // Getters and setters
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
    
    public List<WordTimestamp> getWordTimestamps() { return wordTimestamps; }
    public void setWordTimestamps(List<WordTimestamp> wordTimestamps) { 
        this.wordTimestamps = wordTimestamps; 
    }
    
    public void addWordTimestamp(WordTimestamp wordTimestamp) {
        this.wordTimestamps.add(wordTimestamp);
    }
}