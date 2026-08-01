/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when {@code ModelManager} cannot locate a requested Whisper model
 * in any known cache location and download is not possible/permitted.
 *
 * <p>Recoverable: the user can pick a different (already-downloaded) model,
 * or trigger a download, so the UI should offer those actions rather than a
 * bare error.</p>
 */
public class ModelNotFoundException extends AudioManagerException {

    private final String modelName;
    private final String modelType;

    public ModelNotFoundException(String modelName, String modelType, String userMessage) {
        super("Model not found: " + modelName + " (type=" + modelType + ")", userMessage, true);
        this.modelName = modelName;
        this.modelType = modelType;
    }

    public String getModelName() { return modelName; }
    public String getModelType() { return modelType; }
}
