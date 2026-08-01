/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.TranscriptionConfig;
import audiomanager.model.TranscriptionResult;

/**
 * Common interface for transcription services.
 */
public interface TranscriptionService {
    TranscriptionResult transcribe(String audioFilePath,
                                   TranscriptionConfig config,
                                   AudioProcessor.ProgressCallback progressCallback,
                                   double audioDuration) throws Exception;
}