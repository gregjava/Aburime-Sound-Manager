/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

/**
 * Enhanced progress listener interface with stage-based tracking.
 *
 * <p>This interface extends basic progress reporting with stage-level
 * granularity, allowing listeners to know not only the overall progress
 * but also which processing stage is currently active.</p>
 *
 * <p><b>Stage names include:</b>
 * <ul>
 *   <li>{@code Conversion} — Audio format conversion</li>
 *   <li>{@code Transcription} — Main transcription process</li>
 *   <li>{@code Alignment} — Word-level timestamp alignment</li>
 *   <li>{@code Diarisation} — Speaker diarisation</li>
 *   <li>{@code Saving} — Output file writing</li>
 * </ul>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see AudioProcessor.StageAwareCallback
 * @see WhisperXTranscriptionService
 */
public interface TranscriptionProgressListener {

    /**
     * Called when a new processing stage begins.
     *
     * @param stageName the name of the stage (e.g., "Conversion", "Transcription")
     * @param estimatedDurationSeconds the estimated duration of this stage in seconds
     */
    void onStageStart(String stageName, double estimatedDurationSeconds);

    /**
     * Called frequently to report progress within the current stage.
     *
     * @param stageProgressFraction the progress of the current stage (0.0 to 1.0)
     */
    void onStageProgress(double stageProgressFraction);
}