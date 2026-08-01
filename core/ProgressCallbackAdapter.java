/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

/**
 * Enhanced adapter to convert between different progress callback interfaces (v2.5)
 */
public class ProgressCallbackAdapter implements AudioProcessor.ProgressCallback {
    private final AudioProcessor.StageAwareCallback stageAwareCallback;
    
    public ProgressCallbackAdapter(AudioProcessor.StageAwareCallback stageAwareCallback) {
        this.stageAwareCallback = stageAwareCallback;
    }
    
    @Override
    public void updateProgress(double progress) {
        stageAwareCallback.updateProgress(progress);
    }
    
    /**
     * Create a StageAwareCallback adapter for methods that only need basic progress
     * @param callback
     * @return 
     */
    public static AudioProcessor.StageAwareCallback createBasicStageAware(final AudioProcessor.ProgressCallback callback) {
        return new AudioProcessor.StageAwareCallback() {
            @Override
            public void updateProgress(double progress) {
                callback.updateProgress(progress);
            }
            
            @Override
            public void onStageStart(String stageName, double estimatedDurationSeconds) {
                // Default implementation for basic callbacks
            }
        };
    }

    /**
     * Adapter to convert StageAwareCallback to TranscriptionProgressListener
     * @param callback A Stage-Aware callback in AudioProcessor.
     * @return The method returns a TranscriptionProgressListener object that handles progress during transcription.
     */
    public static TranscriptionProgressListener toTranscriptionListener(final AudioProcessor.StageAwareCallback callback) {
        return new TranscriptionProgressListener() {
            @Override
            public void onStageStart(String stageName, double estimatedDurationSeconds) {
                callback.onStageStart(stageName, estimatedDurationSeconds);
            }

            @Override
            public void onStageProgress(double stageProgressFraction) {
                callback.updateProgress(stageProgressFraction);
            }
        };
    }
}