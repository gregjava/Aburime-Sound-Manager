/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

/**
 * Callback interface for segment processing progress.
 *
 * <p>This interface is used by {@link SegmentProcessor} to notify listeners
 * when individual segments of a long audio file have been transcribed.</p>
 *
 * <p>This is particularly useful for:
 * <ul>
 *   <li>Updating UI progress bars during segmented transcription</li>
 *   <li>Saving intermediate state for crash recovery</li>
 *   <li>Logging progress for long-running operations</li>
 * </ul>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see SegmentProcessor
 * @see BatchProcessor
 */
public interface SegmentProgressListener {

    /**
     * Called when a segment has completed transcription.
     *
     * @param segmentIndex the zero-based index of the completed segment
     * @param totalSegments the total number of segments in the file
     */
    void onSegmentCompleted(int segmentIndex, int totalSegments);
}