/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Custom exception for model download failures with helpful messages.
 *
 * <p>This exception is thrown when a Whisper model cannot be downloaded
 * from HuggingFace or other model repositories. It provides category-specific
 * user-friendly messages based on the type of error encountered.</p>
 *
 * <p><b>Error types:</b>
 * <ul>
 *   <li>{@code dns}/{@code network} - Network connectivity issues</li>
 *   <li>{@code proxy} - Proxy configuration issues</li>
 *   <li>{@code timeout} - Download timeout</li>
 *   <li>{@code auth} - Authentication issues (invalid HF_TOKEN)</li>
 *   <li>{@code corrupted} - Corrupted download (checksum mismatch)</li>
 *   <li>{@code not_installed_locally} - Model not found locally</li>
 * </ul>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see ModelManager
 */
public class ModelDownloadException extends Exception {
    private final String modelName;
    private final String errorType;

    /**
     * Constructs a new ModelDownloadException.
     *
     * @param modelName the name of the model that failed to download
     * @param message the error message
     * @param errorType the type of error (e.g., "network", "timeout", "auth")
     */
    public ModelDownloadException(String modelName, String message, String errorType) {
        super(String.format("Failed to download model '%s': %s", modelName, message));
        this.modelName = modelName;
        this.errorType = errorType;
    }

    /**
     * Constructs a new ModelDownloadException with a cause.
     *
     * @param modelName the name of the model that failed to download
     * @param message the error message
     * @param errorType the type of error
     * @param cause the underlying cause
     */
    public ModelDownloadException(String modelName, String message, String errorType, Throwable cause) {
        super(String.format("Failed to download model '%s': %s", modelName, message), cause);
        this.modelName = modelName;
        this.errorType = errorType;
    }

    /**
     * Returns the name of the model that failed to download.
     *
     * @return the model name
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Returns the type of error that occurred.
     *
     * @return the error type
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * Returns a user-friendly message specific to the error type.
     *
     * <p>This method provides tailored messages with actionable advice
     * based on the category of error encountered.</p>
     *
     * @return a user-friendly error message
     */
    public String getUserFriendlyMessage() {
        switch (errorType.toLowerCase()) {
            case "dns":
            case "network":
                return String.format(
                    "❌ Model '%s' download failed due to network issue.\n" +
                    "   • Check your internet connection\n" +
                    "   • Try disabling VPN/firewall temporarily\n" +
                    "   • Verify DNS settings (huggingface.co not resolving)\n" +
                    "   Error: %s",
                    modelName, getMessage()
                );

            case "proxy":
                return String.format(
                    "❌ Model '%s' download failed due to proxy issue.\n" +
                    "   • Check your proxy settings\n" +
                    "   • Try disabling proxy temporarily\n" +
                    "   • Ensure proxy server is reachable\n" +
                    "   Error: %s",
                    modelName, getMessage()
                );

            case "timeout":
                return String.format(
                    "❌ Model '%s' download timed out.\n" +
                    "   • Your connection might be slow\n" +
                    "   • Server might be busy\n" +
                    "   • Try again later\n" +
                    "   • Use a different model (tiny/base instead of small)\n" +
                    "   Error: %s",
                    modelName, getMessage()
                );

            case "auth":
                return String.format(
                    "❌ Model '%s' download failed due to authentication issue.\n" +
                    "   Please check your HF_TOKEN or API credentials.\n" +
                    "   Error: %s",
                    modelName, getMessage()
                );

            case "corrupted":
                return String.format(
                    "❌ Model '%s' appears to be corrupted.\n" +
                    "   The cache will be cleared and you can try again.\n" +
                    "   Error: %s",
                    modelName, getMessage()
                );

            default:
                return String.format(
                    "❌ Failed to download model '%s'.\n" +
                    "   Error: %s\n" +
                    "   Please check your network connection and try again.",
                    modelName, getMessage()
                );
        }
    }
}