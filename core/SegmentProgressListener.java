/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

public interface SegmentProgressListener {
    void onSegmentCompleted(int segmentIndex, int totalSegments);
}