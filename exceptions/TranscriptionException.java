/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when the WhisperX transcription step itself fails — model loaded
 * fine, FFmpeg preprocessing succeeded, but the transcription subprocess
 * errored, produced malformed output, or timed out.
 *
 * <p>Kept distinct from {@link FfmpegException} because the remediation is
 * different: a transcription failure might mean "try a smaller model" or
 * "disable diarization", not "reinstall FFmpeg".</p>
 */
public class TranscriptionException extends AudioManagerException {

    private final String modelName;

    public TranscriptionException(String technicalMessage, String userMessage,
                                   String modelName, boolean recoverable) {
        super(technicalMessage, userMessage, recoverable);
        this.modelName = modelName;
    }

    public TranscriptionException(String technicalMessage, String userMessage,
                                   String modelName, Throwable cause) {
        super(technicalMessage, userMessage, true, cause);
        this.modelName = modelName;
    }

    public String getModelName() {
        return modelName;
    }
}
