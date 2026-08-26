/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when the WhisperX transcription step itself fails.
 *
 * <p>This exception covers failures that occur during the transcription
 * process after dependencies and models are successfully loaded. Common
 * causes include:
 * <ul>
 *   <li>The transcription subprocess errored</li>
 *   <li>The output was malformed or unparseable</li>
 *   <li>The transcription timed out</li>
 *   <li>The GPU ran out of memory</li>
 * </ul>
 *
 * <p>This exception is kept distinct from {@link FfmpegException} because
 * the remediation is different: a transcription failure might mean
 * "try a smaller model" or "disable diarisation", not "reinstall FFmpeg".</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see AudioManagerException
 * @see FfmpegException
 * @see WhisperXTranscriptionService
 */
public class TranscriptionException extends AudioManagerException {

    private final String modelName;

    /**
     * Constructs a new TranscriptionException with a model name and recoverability flag.
     *
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     * @param modelName the name of the model being used for transcription
     * @param recoverable {@code true} if the operation can be retried
     */
    public TranscriptionException(String technicalMessage, String userMessage,
                                   String modelName, boolean recoverable) {
        super(technicalMessage, userMessage, recoverable);
        this.modelName = modelName;
    }

    /**
     * Constructs a new TranscriptionException with a model name and cause.
     *
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     * @param modelName the name of the model being used for transcription
     * @param cause the underlying cause of this exception
     */
    public TranscriptionException(String technicalMessage, String userMessage,
                                   String modelName, Throwable cause) {
        super(technicalMessage, userMessage, true, cause);
        this.modelName = modelName;
    }

    /**
     * Returns the name of the model being used for transcription.
     *
     * @return the model name
     */
    public String getModelName() {
        return modelName;
    }
}