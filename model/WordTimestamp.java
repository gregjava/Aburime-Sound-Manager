/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

public class WordTimestamp {
    private final String word;
    private final double start;
    private final double end;
    private final double confidence;
    
    public WordTimestamp(String word, double start, double end, double confidence) {
        this.word = word;
        this.start = start;
        this.end = end;
        this.confidence = confidence;
    }
    
    // Getters
    public String getWord() { return word; }
    public double getStart() { return start; }
    public double getEnd() { return end; }
    public double getConfidence() { return confidence; }
    
    @Override
    public String toString() {
        return String.format("%s [%.2f-%.2f] (%.2f)", word, start, end, confidence);
    }
}