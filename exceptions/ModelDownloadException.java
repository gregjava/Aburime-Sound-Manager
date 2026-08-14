/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Custom exception for model download failures with helpful messages
 */
public class ModelDownloadException extends Exception {
    private final String modelName;
    private final String errorType;
    
    public ModelDownloadException(String modelName, String message, String errorType) {
        super(String.format("Failed to download model '%s': %s", modelName, message));
        this.modelName = modelName;
        this.errorType = errorType;
    }
    
    public ModelDownloadException(String modelName, String message, String errorType, Throwable cause) {
        super(String.format("Failed to download model '%s': %s", modelName, message), cause);
        this.modelName = modelName;
        this.errorType = errorType;
    }
    
    public String getModelName() {
        return modelName;
    }
    
    public String getErrorType() {
        return errorType;
    }
    
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