/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when a requested Whisper model cannot be located in any known cache location.
 *
 * <p>This exception indicates that the model is not present in any of the
 * following locations:
 * <ul>
 *   <li>The application's managed cache directory</li>
 *   <li>The HuggingFace cache directory</li>
 *   <li>Any other model search paths</li>
 * </ul>
 *
 * <p>This exception is recoverable: the user can pick a different
 * (already-downloaded) model, or trigger a download. The UI should offer
 * those actions rather than a bare error.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see AudioManagerException
 * @see ModelManager
 */
public class ModelNotFoundException extends AudioManagerException {

    private final String modelName;
    private final String modelType;

    /**
     * Constructs a new ModelNotFoundException.
     *
     * @param modelName the name of the model that was not found
     * @param modelType the type of the model (e.g., "whisper", "whisperx")
     * @param userMessage the user-friendly message for display
     */
    public ModelNotFoundException(String modelName, String modelType, String userMessage) {
        super("Model not found: " + modelName + " (type=" + modelType + ")", userMessage, true);
        this.modelName = modelName;
        this.modelType = modelType;
    }

    /**
     * Returns the name of the model that was not found.
     *
     * @return the model name
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Returns the type of the model that was not found.
     *
     * @return the model type
     */
    public String getModelType() {
        return modelType;
    }
}