/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

/**
 * Enhanced progress listener interface from v2.5 with stage-based tracking
 */
public interface TranscriptionProgressListener {
    /**
     * Called when a new processing stage begins (v2.5 sophisticated tracking)
     * @param stageName The name of the stage (e.g., "Conversion", "Transcription")
     * @param estimatedDurationSeconds The estimated duration of this stage only
     */
    void onStageStart(String stageName, double estimatedDurationSeconds);

    /**
     * Called frequently to report progress within the current stage
     * @param stageProgressFraction The progress of the current stage (0.0 to 1.0)
     */
    void onStageProgress(double stageProgressFraction);
}