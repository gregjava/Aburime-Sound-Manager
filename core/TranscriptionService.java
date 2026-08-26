/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.TranscriptionConfig;
import audiomanager.model.TranscriptionResult;

/**
 * Common interface for all transcription services.
 *
 * <p>This interface defines the contract for transcription implementations,
 * allowing the application to support multiple backends (Whisper, WhisperX,
 * etc.) through a uniform API.</p>
 *
 * <p><b>Implementations:</b>
 * <ul>
 *   <li>{@link WhisperXTranscriptionService} — Main implementation using WhisperX</li>
 *   <li>Future implementations may include cloud-based services</li>
 * </ul>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see WhisperXTranscriptionService
 * @see TranscriptionConfig
 * @see TranscriptionResult
 */
public interface TranscriptionService {

    /**
     * Transcribes an audio file to text.
     *
     * <p>This method performs the transcription of the given audio file
     * using the provided configuration. Progress can be reported via the
     * optional callback.</p>
     *
     * @param audioFilePath the path to the audio file to transcribe
     * @param config the transcription configuration (model, language, etc.)
     * @param progressCallback optional progress listener; may be {@code null}
     * @param audioDuration the total audio duration in seconds (for progress calculation)
     * @return the {@link TranscriptionResult} containing the transcribed text and segments
     * @throws Exception if transcription fails
     */
    TranscriptionResult transcribe(String audioFilePath,
                                   TranscriptionConfig config,
                                   AudioProcessor.ProgressCallback progressCallback,
                                   double audioDuration) throws Exception;
}