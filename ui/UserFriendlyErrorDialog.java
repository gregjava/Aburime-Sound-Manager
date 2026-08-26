/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.function.Consumer;

/**
 * User-friendly error dialog with actionable guidance.
 * 
 * <p>Provides clear, non-technical error messages with step-by-step
 * fix instructions, visual cues, and links to relevant documentation.</p>
 * 
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * // Show an FFmpeg missing error
 * UserFriendlyErrorDialog.forFFmpegMissing().showAndWait();
 * 
 * // Show a custom error
 * new UserFriendlyErrorDialog.Builder()
 *     .title("Custom Error")
 *     .userMessage("Something went wrong...")
 *     .fixInstructions("Step 1: Do this...")
 *     .showAndWait();
 * }</pre>
 * 
 * <p><b>Thread-safety:</b> This class is designed to be created and shown
 * on the JavaFX Application Thread.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see OnboardingWizard
 * @see DocumentationLauncher
 */
public class UserFriendlyErrorDialog {

    private final Stage stage;
    private final String title;
    private final String header;
    private final String userMessage;
    private final String technicalDetails;
    private final String fixInstructions;
    private final String troubleshootingLink;
    private final Runnable onFixAction;
    private boolean closed = false;

    /**
     * Builder for UserFriendlyErrorDialog.
     */
    public static class Builder {
        private String title = "Something Went Wrong";
        private String header = "We encountered an issue";
        private String userMessage = "An error occurred while processing your request.";
        private String technicalDetails = null;
        private String fixInstructions = null;
        private String troubleshootingLink = null;
        private Runnable onFixAction = null;

        /**
         * Sets the dialog title.
         *
         * @param title the dialog title
         * @return this builder
         */
        public Builder title(String title) { this.title = title; return this; }

        /**
         * Sets the dialog header.
         *
         * @param header the dialog header
         * @return this builder
         */
        public Builder header(String header) { this.header = header; return this; }

        /**
         * Sets the user-friendly error message.
         *
         * @param message the user-friendly message
         * @return this builder
         */
        public Builder userMessage(String message) { this.userMessage = message; return this; }

        /**
         * Sets the technical details (stack trace) for advanced users.
         *
         * @param details the technical details
         * @return this builder
         */
        public Builder technicalDetails(String details) { this.technicalDetails = details; return this; }

        /**
         * Sets the step-by-step fix instructions.
         *
         * @param instructions the fix instructions
         * @return this builder
         */
        public Builder fixInstructions(String instructions) { this.fixInstructions = instructions; return this; }

        /**
         * Sets the link to the troubleshooting guide section.
         *
         * @param link the troubleshooting link (e.g., "TROUBLESHOOTING.md#err-012")
         * @return this builder
         */
        public Builder troubleshootingLink(String link) { this.troubleshootingLink = link; return this; }

        /**
         * Sets the "Fix It Now" action.
         *
         * @param action the action to run when "Fix It Now" is clicked
         * @return this builder
         */
        public Builder onFixAction(Runnable action) { this.onFixAction = action; return this; }

        /**
         * Builds the UserFriendlyErrorDialog.
         *
         * @return a new UserFriendlyErrorDialog instance
         */
        public UserFriendlyErrorDialog build() {
            return new UserFriendlyErrorDialog(this);
        }

        /**
         * Builds and shows the dialog.
         *
         * @return this builder (for chaining)
         */
        public Builder showAndWait() {
            build().showAndWait();
            return this;
        }

        /**
         * Builds and shows the dialog (non-blocking).
         *
         * @return this builder (for chaining)
         */
        public Builder show() {
            build().show();
            return this;
        }
    }

    private UserFriendlyErrorDialog(Builder builder) {
        this.stage = new Stage();
        this.stage.initStyle(StageStyle.UTILITY);
        this.stage.initModality(Modality.APPLICATION_MODAL);
        this.stage.setResizable(true);
        this.stage.setMinWidth(550);
        this.stage.setMinHeight(400);
        this.title = builder.title;
        this.header = builder.header;
        this.userMessage = builder.userMessage;
        this.technicalDetails = builder.technicalDetails;
        this.fixInstructions = builder.fixInstructions;
        this.troubleshootingLink = builder.troubleshootingLink;
        this.onFixAction = builder.onFixAction;

        buildUI();
    }

    private void buildUI() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #f8f9fa;");
        root.getStyleClass().add("theme-fix-surface-alt");

        // ===== Header with icon =====
        HBox headerBox = new HBox(12);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        Label iconLabel = new Label("❌");
        iconLabel.setStyle("-fx-font-size: 32px;");

        VBox headerText = new VBox(4);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        titleLabel.getStyleClass().add("panel-heading");

        Label headerLabel = new Label(header);
        headerLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #555;");
        headerLabel.setWrapText(true);

        headerText.getChildren().addAll(titleLabel, headerLabel);
        headerBox.getChildren().addAll(iconLabel, headerText);
        HBox.setHgrow(headerText, Priority.ALWAYS);

        // ===== User-friendly message =====
        TextArea userMessageArea = new TextArea(userMessage);
        userMessageArea.setEditable(false);
        userMessageArea.setWrapText(true);
        userMessageArea.setPrefHeight(Math.min(80, userMessage.split("\n").length * 20 + 20));
        userMessageArea.setStyle(
            "-fx-background-color: #fff3cd; " +
            "-fx-border-color: #ffc107; " +
            "-fx-border-radius: 4; " +
            "-fx-background-radius: 4; " +
            "-fx-font-size: 13px; " +
            "-fx-text-fill: #856404; " +
            "-fx-padding: 10;"
        );
        userMessageArea.getStyleClass().add("theme-fix-surface-alt");

        // ===== Fix instructions (if provided) =====
        VBox fixBox = null;
        if (fixInstructions != null && !fixInstructions.isEmpty()) {
            fixBox = new VBox(8);
            fixBox.setPadding(new Insets(10));
            fixBox.setStyle(
                "-fx-background-color: #d1ecf1; " +
                "-fx-border-color: #bee5eb; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4;"
            );
            fixBox.getStyleClass().add("theme-fix-surface-alt");

            Label fixLabel = new Label("💡 How to fix this:");
            fixLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #0c5460;");

            TextArea fixArea = new TextArea(fixInstructions);
            fixArea.setEditable(false);
            fixArea.setWrapText(true);
            fixArea.setPrefHeight(Math.min(120, fixInstructions.split("\n").length * 18 + 20));
            fixArea.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-border-color: transparent; " +
                "-fx-font-size: 12px; " +
                "-fx-text-fill: #0c5460;"
            );
            fixArea.getStyleClass().add("theme-fix-surface-alt");

            fixBox.getChildren().addAll(fixLabel, fixArea);
        }

        // ===== Technical details (expandable) =====
        TitledPane techPane = null;
        if (technicalDetails != null && !technicalDetails.isEmpty()) {
            TextArea techArea = new TextArea(technicalDetails);
            techArea.setEditable(false);
            techArea.setWrapText(true);
            techArea.setPrefHeight(120);
            techArea.setStyle(
                "-fx-font-family: 'Consolas', 'Monaco', monospace; " +
                "-fx-font-size: 12px; " +
                "-fx-background-color: #1e1e1e; " +
                "-fx-text-fill: #d4d4d4;"
            );
            techArea.getStyleClass().add("theme-fix-surface-alt");

            techPane = new TitledPane("🔍 Technical Details (for advanced users)", techArea);
            techPane.setCollapsible(true);
            techPane.setExpanded(false);
            techPane.getStyleClass().add("theme-fix-surface-alt");
        }

        // ===== Buttons =====
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(5, 0, 0, 0));

        // "Fix It" button (if action provided)
        if (onFixAction != null) {
            Button fixButton = new Button("🔧 Fix It Now");
            fixButton.setStyle(
                "-fx-background-color: #28a745; " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: bold; " +
                "-fx-padding: 8 20; " +
                "-fx-background-radius: 4;"
            );
            fixButton.getStyleClass().add("action-btn-start");
            fixButton.setOnAction(e -> {
                onFixAction.run();
                stage.close();
            });
            buttonBox.getChildren().add(fixButton);
        }

        // "Open Troubleshooting" button (if link provided)
        if (troubleshootingLink != null && !troubleshootingLink.isEmpty()) {
            Button helpButton = new Button("📖 Open Troubleshooting Guide");
            helpButton.setStyle(
                "-fx-background-color: #17a2b8; " +
                "-fx-text-fill: white; " +
                "-fx-padding: 8 20; " +
                "-fx-background-radius: 4;"
            );
            helpButton.getStyleClass().add("action-btn-output-dir");
            helpButton.setOnAction(e -> {
                new DocumentationLauncher().open(troubleshootingLink);
                stage.close();
            });
            buttonBox.getChildren().add(helpButton);
        }

        Button closeButton = new Button("Close");
        closeButton.setStyle("-fx-padding: 8 20; -fx-background-radius: 4;");
        closeButton.setOnAction(e -> stage.close());
        buttonBox.getChildren().add(closeButton);

        // ===== Build the layout =====
        root.getChildren().add(headerBox);
        root.getChildren().add(userMessageArea);
        if (fixBox != null) root.getChildren().add(fixBox);
        if (techPane != null) root.getChildren().add(techPane);
        root.getChildren().add(buttonBox);

        // ===== Scene =====
        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle(title);
        ThemeManager.applyCurrentThemeToDialog(null, scene);
    }

    /**
     * Shows the dialog and waits for it to be closed.
     */
    public void showAndWait() {
        stage.showAndWait();
    }

    /**
     * Shows the dialog (non-blocking).
     */
    public void show() {
        stage.show();
    }

    /**
     * Closes the dialog.
     */
    public void close() {
        stage.close();
    }

    /**
     * Returns whether the dialog has been closed.
     *
     * @return {@code true} if the dialog is closed
     */
    public boolean isClosed() {
        return closed;
    }

    // ========================================================================
    //  Factory methods for common errors
    // ========================================================================

    /**
     * Creates a dialog for FFmpeg not found error.
     *
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forFFmpegMissing() {
        return new Builder()
            .title("FFmpeg Not Found")
            .header("🎵 Audio processing requires FFmpeg")
            .userMessage(
                "AudioManager needs FFmpeg to process audio files, but it wasn't found on your system.\n\n" +
                "Without FFmpeg, you won't be able to transcribe or convert audio files."
            )
            .fixInstructions(
                "1. Download FFmpeg from: https://ffmpeg.org/download.html\n" +
                "2. Extract the files to: C:\\AI\\ffmpeg\\\n" +
                "3. Make sure ffmpeg.exe and ffprobe.exe are in: C:\\AI\\ffmpeg\\bin\\\n" +
                "4. Restart AudioManager\n\n" +
                "Or run the Setup Wizard (Help → Run Setup Wizard) for guided installation."
            )
            .troubleshootingLink("TROUBLESHOOTING.md")
            .onFixAction(() -> {
                // Launch Setup Wizard
                OnboardingWizard wizard = new OnboardingWizard();
                wizard.showAndWait();
            })
            .build();
    }

    /**
     * Creates a dialog for Python version incompatibility.
     *
     * @param detectedVersion the detected Python version (e.g., "3.13.0")
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forPythonVersionError(String detectedVersion) {
        return new Builder()
            .title("Python Version Incompatible")
            .header("🐍 WhisperX requires Python 3.10, 3.11, or 3.12")
            .userMessage(
                "You have Python " + detectedVersion + " installed, but WhisperX does not work with this version.\n\n" +
                "This is a known compatibility issue with the latest Python versions."
            )
            .fixInstructions(
                "1. Download Python 3.12 from: https://www.python.org/downloads/\n" +
                "2. Install Python 3.12 (you can have multiple versions)\n" +
                "3. During installation, check 'Add Python to PATH'\n" +
                "4. Create a new virtual environment:\n" +
                "   C:\\Python312\\python.exe -m venv whisperx_env\n" +
                "5. Activate and install WhisperX:\n" +
                "   whisperx_env\\Scripts\\activate\n" +
                "   pip install whisperx\n" +
                "6. Restart AudioManager"
            )
            .troubleshootingLink("TROUBLESHOOTING.md#err-012")
            .onFixAction(() -> {
                // Launch Setup Wizard
                OnboardingWizard wizard = new OnboardingWizard();
                wizard.showAndWait();
            })
            .build();
    }

    /**
     * Creates a dialog for TorchCodec DLL error.
     *
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forTorchCodecError() {
        return new Builder()
            .title("TorchCodec DLL Error")
            .header("🔧 Missing FFmpeg DLLs for TorchCodec")
            .userMessage(
                "The transcription engine (TorchCodec) is missing required DLL files.\n\n" +
                "This is a common issue on Windows that can be fixed by installing the correct version."
            )
            .fixInstructions(
                "1. Open Command Prompt as Administrator\n" +
                "2. Activate your WhisperX environment:\n" +
                "   whisperx_env\\Scripts\\activate\n" +
                "3. Uninstall and reinstall TorchCodec:\n" +
                "   pip uninstall torchcodec -y\n" +
                "   pip install torchcodec==0.7.0\n" +
                "4. Copy FFmpeg DLLs to TorchCodec folder:\n" +
                "   copy C:\\AI\\ffmpeg\\bin\\*.dll \n" +
                "   C:\\Users\\YourUsername\\AppData\\Local\\Programs\\Python\\Python312\\Lib\\site-packages\\torchcodec\\\n" +
                "5. Restart AudioManager"
            )
            .troubleshootingLink("TROUBLESHOOTING.md#err-013")
            .build();
    }

    /**
     * Creates a dialog for WhisperX not found.
     *
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forWhisperXMissing() {
        return new Builder()
            .title("WhisperX Not Found")
            .header("🎤 Transcription engine is missing")
            .userMessage(
                "AudioManager needs WhisperX to transcribe audio, but it wasn't found.\n\n" +
                "WhisperX is the speech recognition engine that powers transcription."
            )
            .fixInstructions(
                "1. Open Command Prompt\n" +
                "2. Create a virtual environment:\n" +
                "   python -m venv whisperx_env\n" +
                "3. Activate it:\n" +
                "   whisperx_env\\Scripts\\activate\n" +
                "4. Install WhisperX:\n" +
                "   pip install whisperx\n" +
                "5. Restart AudioManager"
            )
            .troubleshootingLink("TROUBLESHOOTING.md#err-002")
            .onFixAction(() -> {
                // Launch Setup Wizard
                OnboardingWizard wizard = new OnboardingWizard();
                wizard.showAndWait();
            })
            .build();
    }

    /**
     * Creates a dialog for general transcription failure.
     *
     * @param fileName the name of the file that failed
     * @param cause the exception that caused the failure
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forTranscriptionFailure(String fileName, Throwable cause) {
        String message = "Failed to transcribe: " + fileName + "\n\n";

        String causeMessage = cause != null ? cause.getMessage() : null;
        if (causeMessage != null) {
            String lowerMsg = causeMessage.toLowerCase();
            if (lowerMsg.contains("memory") || lowerMsg.contains("out of memory")) {
                return forOutOfMemory(fileName);
            } else if (lowerMsg.contains("timeout") || lowerMsg.contains("timed out")) {
                return forTimeout(fileName);
            } else if (lowerMsg.contains("ffmpeg") || lowerMsg.contains("ffprobe")) {
                return forFFmpegMissing();
            } else if (lowerMsg.contains("python") || lowerMsg.contains("whisperx")) {
                return forWhisperXMissing();
            }
        }

        message += "The transcription process encountered an error. This could be due to:\n" +
                   "• A corrupt or unsupported audio file\n" +
                   "• Insufficient system resources\n" +
                   "• A temporary issue with the transcription engine\n\n" +
                   "Try processing the file again or use a different model.";

        return new Builder()
            .title("Transcription Failed")
            .header("❌ Could not transcribe: " + fileName)
            .userMessage(message)
            .technicalDetails(cause != null ? cause.toString() : "Unknown error")
            .troubleshootingLink("TROUBLESHOOTING.md#transcription-issues")
            .build();
    }

    /**
     * Creates a dialog for out of memory error.
     *
     * @param fileName the name of the file being processed
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forOutOfMemory(String fileName) {
        return new Builder()
            .title("Out of Memory")
            .header("💾 Not enough memory to process: " + fileName)
            .userMessage(
                "Your computer doesn't have enough available memory to transcribe this file.\n\n" +
                "This usually happens with large files or when using large models."
            )
            .fixInstructions(
                "1. Use a smaller model (try 'small' or 'medium' instead of 'large')\n" +
                "2. Reduce the number of parallel files (try 1 instead of 2-4)\n" +
                "3. Close other applications to free up memory\n" +
                "4. Enable GPU acceleration (if you have a compatible GPU)\n\n" +
                "If the file is very large (>1 hour), try splitting it into smaller chunks."
            )
            .troubleshootingLink("TROUBLESHOOTING.md#err-003")
            .build();
    }

    /**
     * Creates a dialog for timeout error.
     *
     * @param fileName the name of the file being processed
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forTimeout(String fileName) {
        return new Builder()
            .title("Processing Timeout")
            .header("⏱️ " + fileName + " took too long to process")
            .userMessage(
                "The file is taking longer than expected to transcribe.\n\n" +
                "This can happen with very large files or when using large models on slower hardware."
            )
            .fixInstructions(
                "1. Use a smaller model (try 'small' instead of 'large')\n" +
                "2. Enable GPU acceleration (if available)\n" +
                "3. Split the file into smaller chunks using the Audio Splitter tool\n" +
                "4. Try processing during off-hours when your computer is idle"
            )
            .troubleshootingLink("TROUBLESHOOTING.md#err-011")
            .build();
    }

    /**
     * Creates a dialog for model not found.
     *
     * @param modelName the name of the missing model
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forModelNotFound(String modelName) {
        return new Builder()
            .title("Model Not Found")
            .header("📦 The '" + modelName + "' model is not installed")
            .userMessage(
                "AudioManager couldn't find the " + modelName + " model in your cache.\n\n" +
                "Models must be downloaded manually before they can be used."
            )
            .fixInstructions(
                "1. Install HuggingFace CLI:\n" +
                "   pip install huggingface_hub[cli]\n" +
                "2. Download the model:\n" +
                "   huggingface-cli download Systran/faster-whisper-" + modelName + "\n" +
                "3. Check the model is downloaded:\n" +
                "   The folder should be in: .cache/huggingface/hub/\n" +
                "4. Restart AudioManager and select the model in Settings"
            )
            .troubleshootingLink("TROUBLESHOOTING.md#err-008")
            .build();
    }

    /**
     * Creates a dialog for a generic error with a custom message.
     *
     * @param title the dialog title
     * @param header the dialog header
     * @param userMessage the user-friendly message
     * @param technicalDetails the technical details (stack trace)
     * @return a new UserFriendlyErrorDialog
     */
    public static UserFriendlyErrorDialog forGenericError(String title, String header, 
                                                          String userMessage, String technicalDetails) {
        return new Builder()
            .title(title)
            .header(header)
            .userMessage(userMessage)
            .technicalDetails(technicalDetails)
            .troubleshootingLink("TROUBLESHOOTING.md")
            .build();
    }

    // ========================================================================
    //  Static utility methods for quick error display
    // ========================================================================

    /**
     * Shows a user-friendly error dialog for an exception.
     * This is the main entry point for error handling.
     *
     * @param throwable the exception to display
     * @param context the context where the error occurred
     */
    public static void showForException(Throwable throwable, String context) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getMessage() != null ? cause.getMessage() : throwable.getMessage();
        
        UserFriendlyErrorDialog dialog = createFromException(cause, message);
        dialog.showAndWait();
    }

    /**
     * Creates a user-friendly error dialog from an exception.
     *
     * @param cause the root cause exception
     * @param message the error message
     * @return a UserFriendlyErrorDialog appropriate for the error type
     */
    public static UserFriendlyErrorDialog createFromException(Throwable cause, String message) {
        String lowerMsg = message != null ? message.toLowerCase() : "";
        
        // FFmpeg errors
        if (lowerMsg.contains("ffmpeg") || lowerMsg.contains("ffprobe")) {
            return forFFmpegMissing();
        }
        
        // Python/WhisperX errors
        if (lowerMsg.contains("python") || lowerMsg.contains("whisperx") || 
            lowerMsg.contains("no module named")) {
            return forWhisperXMissing();
        }
        
        // Python version errors
        if (lowerMsg.contains("python") && (lowerMsg.contains("3.13") || lowerMsg.contains("3.14") || 
            lowerMsg.contains("3.15"))) {
            return forPythonVersionError("3.13+");
        }
        
        // TorchCodec errors
        if (lowerMsg.contains("torchcodec") || lowerMsg.contains("libtorchcodec") ||
            lowerMsg.contains("dll") || lowerMsg.contains("could not find module")) {
            return forTorchCodecError();
        }
        
        // Model errors
        if (lowerMsg.contains("model") && (lowerMsg.contains("not found") || 
            lowerMsg.contains("not installed") || lowerMsg.contains("missing"))) {
            String modelName = extractModelName(message);
            return forModelNotFound(modelName);
        }
        
        // Memory errors
        if (lowerMsg.contains("memory") || lowerMsg.contains("out of memory") ||
            lowerMsg.contains("heap space") || lowerMsg.contains("oom") ||
            lowerMsg.contains("allocation")) {
            return forOutOfMemory("the current file");
        }
        
        // Timeout errors
        if (lowerMsg.contains("timeout") || lowerMsg.contains("timed out") ||
            lowerMsg.contains("time limit")) {
            return forTimeout("the current file");
        }
        
        // Generic error
        return forGenericError(
            "Unexpected Error",
            "Something went wrong",
            "An unexpected error occurred while processing your request.\n\n" +
            "Please try again. If the problem persists, check the Troubleshooting Guide.",
            cause != null ? cause.toString() : message
        );
    }

    /**
     * Extracts the model name from an error message.
     *
     * @param message the error message
     * @return the extracted model name, or "base" if not found
     */
    private static String extractModelName(String message) {
        if (message == null) return "base";
        String lowerMsg = message.toLowerCase();
        String[] models = {"large-v3", "large-v2", "large", "medium", "small", "base", "tiny"};
        for (String model : models) {
            if (lowerMsg.contains(model)) {
                return model;
            }
        }
        return "base";
    }
}