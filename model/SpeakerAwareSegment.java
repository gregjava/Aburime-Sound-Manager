/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

/**
 * Accessor interface for segments that carry speaker-diarization information.
 *
 * <h2>Why this lives in {@code audiomanager.model}, not {@code audiomanager.core}</h2>
 * It was previously nested inside {@code audiomanager.core.TranscriptionOutputWriter}
 * as {@code TranscriptionOutputWriter.SpeakerAwareSegment}. That forced any
 * model class wanting to implement it (i.e. {@link TranscriptionSegment}) to
 * depend upward on the core layer, inverting the intended
 * {@code core -> model} dependency direction. Moving the interface down into
 * {@code model} lets {@link TranscriptionSegment} implement it directly with
 * no upward dependency, while {@code audiomanager.core.TranscriptionOutputWriter}
 * depends on it the normal way.
 */
public interface SpeakerAwareSegment {

    /**
     * @return the diarized speaker label (e.g. {@code "SPEAKER_00"}), or
     *         {@code null}/blank if no speaker has been assigned to this
     *         segment (diarization disabled, or not yet run).
     */
    String getSpeaker();
}