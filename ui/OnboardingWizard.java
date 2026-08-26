/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.core.DependencyManager;
import audiomanager.core.HuggingFaceCacheResolver;
import audiomanager.core.ModelManager;
import audiomanager.core.WhisperXTranscriptionService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-run onboarding wizard that guides users through:
 * 1. Dependency checks (FFmpeg, FFprobe, Python, WhisperX, TorchCodec)
 * 2. Installation instructions for missing dependencies
 * 3. Model selection and download
 * 4. GPU/CPU detection
 * 5. Configuration setup
 */
public class OnboardingWizard {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(OnboardingWizard.class);
    
    // Python version compatibility
    private static final int PYTHON_MIN_MAJOR = 3;
    private static final int PYTHON_MIN_MINOR = 10;
    private static final int PYTHON_MAX_MINOR = 12;
    
    private final Stage stage;
    private final DependencyManager dependencyManager;
    private final ModelManager modelManager;
    private int currentStep = 0;
    private final VBox contentContainer;
    private final ProgressBar overallProgress;
    private final Label statusLabel;
    private final Button nextButton;
    private final Button skipButton;
    private final Button backButton;
    private boolean completed = false;
    
    // Status labels for dependency check
    private Label ffmpegStatus;
    private Label ffprobeStatus;
    private Label pythonStatus;
    private Label whisperStatus;
    private Label torchCodecStatus;
    private Label gpuStatus;
    private TextArea installInstructions;
    private Label pythonVersionWarning;
    
    // Model selection checkboxes
    private CheckBox[] modelCheckboxes;
    private CheckBox alignmentCb;
    private CheckBox diarizationCb;
    
    // Dependency check results
    private boolean ffmpegOk = false;
    private boolean ffprobeOk = false;
    private boolean pythonOk = false;
    private boolean whisperOk = false;
    private boolean torchCodecOk = false;
    
    public OnboardingWizard() {
        this.stage = new Stage();
        this.stage.initStyle(StageStyle.UTILITY);
        this.stage.setTitle("AudioManager - First Run Setup");
        this.stage.setMinWidth(750);
        this.stage.setMinHeight(600);
        this.stage.setResizable(true);
        
        this.dependencyManager = new DependencyManager();
        this.modelManager = new ModelManager();
        
        // Main layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #f0f2f5;");
        
        // Header with logo/icon
        HBox headerBox = new HBox(15);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setPadding(new Insets(0, 0, 15, 0));
        
        Label headerLabel = new Label("🎵 AudioManager Setup");
        headerLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        headerBox.getChildren().add(headerLabel);
        
        Label versionLabel = new Label("v4.0.0");
        versionLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #7f8c8d; -fx-padding: 4 0 0 10;");
        headerBox.getChildren().add(versionLabel);
        
        BorderPane.setAlignment(headerBox, Pos.CENTER_LEFT);
        root.setTop(headerBox);
        
        // Content area
        this.contentContainer = new VBox(15);
        this.contentContainer.setPadding(new Insets(20));
        this.contentContainer.setStyle("-fx-background-color: white; -fx-border-radius: 8; -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 5, 0, 0, 2);");
        root.setCenter(contentContainer);
        
        // Footer with progress and buttons
        VBox footer = new VBox(10);
        footer.setPadding(new Insets(15, 0, 0, 0));
        
        this.overallProgress = new ProgressBar(0);
        this.overallProgress.setPrefWidth(Double.MAX_VALUE);
        this.overallProgress.setStyle("-fx-accent: #4CAF50;");
        
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        
        this.backButton = new Button("← Back");
        this.backButton.setOnAction(e -> previousStep());
        this.backButton.setVisible(false);
        
        this.skipButton = new Button("Skip Setup");
        this.skipButton.setStyle("-fx-text-fill: #7f8c8d;");
        this.skipButton.setOnAction(e -> skipOnboarding());
        
        this.nextButton = new Button("Next →");
        this.nextButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 20; -fx-background-radius: 4;");
        this.nextButton.setOnAction(e -> nextStep());
        this.nextButton.setDefaultButton(true);
        
        buttonBox.getChildren().addAll(backButton, skipButton, nextButton);
        HBox.setHgrow(buttonBox, Priority.ALWAYS);
        
        this.statusLabel = new Label("Welcome to AudioManager! Let's get you set up.");
        this.statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        footer.getChildren().addAll(overallProgress, statusLabel, buttonBox);
        root.setBottom(footer);
        BorderPane.setMargin(footer, new Insets(10, 0, 0, 0));
        
        this.stage.setScene(new Scene(root));
        this.stage.setOnCloseRequest(e -> {
            if (!completed && currentStep < 3) {
                e.consume();
                showExitConfirmation();
            }
        });
    }
    
    public void showAndWait() {
        showStep(0);
        stage.showAndWait();
    }
    
    // ========================================================================
    //  Step Navigation
    // ========================================================================
    
    private void showStep(int step) {
        currentStep = step;
        contentContainer.getChildren().clear();
        backButton.setVisible(step > 0);
        
        // Update progress bar
        double progress = (double) step / 3;
        overallProgress.setProgress(Math.min(1.0, progress));
        
        switch (step) {
            case 0 -> showDependencyCheck();
            case 1 -> showInstallationInstructions();
            case 2 -> showModelSelection();
            case 3 -> showDownloadProgress();
            case 4 -> showCompletion();
            default -> stage.close();
        }
    }
    
    private void nextStep() {
        if (currentStep == 0 && !allDependenciesMet()) {
            // If dependencies are missing, show the installation instructions
            showStep(1);
            return;
        }
        if (currentStep < 4) {
            showStep(currentStep + 1);
        } else {
            completed = true;
            stage.close();
        }
    }
    
    private void previousStep() {
        if (currentStep > 0) {
            showStep(currentStep - 1);
        }
    }
    
    private boolean allDependenciesMet() {
        return ffmpegOk && ffprobeOk && pythonOk && whisperOk;
    }
    
    // ========================================================================
    //  Step 0: Dependency Check
    // ========================================================================
    
    private void showDependencyCheck() {
        VBox stepContent = new VBox(15);
        
        Label title = new Label("Step 1: Checking System Dependencies");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        
        Label description = new Label(
            "AudioManager requires several components to work properly. We'll check if they're installed.\n" +
            "If something is missing, we'll show you exactly how to install it."
        );
        description.setWrapText(true);
        description.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        
        // Status grid
        GridPane statusGrid = new GridPane();
        statusGrid.setHgap(15);
        statusGrid.setVgap(10);
        statusGrid.setPadding(new Insets(10, 0, 10, 0));
        
        // Row 0: FFmpeg
        Label ffmpegLabel = new Label("FFmpeg:");
        ffmpegLabel.setStyle("-fx-font-weight: bold;");
        ffmpegStatus = new Label("⏳ Checking...");
        statusGrid.add(ffmpegLabel, 0, 0);
        statusGrid.add(ffmpegStatus, 1, 0);
        
        // Row 1: FFprobe
        Label ffprobeLabel = new Label("FFprobe:");
        ffprobeLabel.setStyle("-fx-font-weight: bold;");
        ffprobeStatus = new Label("⏳ Checking...");
        statusGrid.add(ffprobeLabel, 0, 1);
        statusGrid.add(ffprobeStatus, 1, 1);
        
        // Row 2: Python
        Label pythonLabel = new Label("Python:");
        pythonLabel.setStyle("-fx-font-weight: bold;");
        pythonStatus = new Label("⏳ Checking...");
        statusGrid.add(pythonLabel, 0, 2);
        statusGrid.add(pythonStatus, 1, 2);
        
        // Row 3: WhisperX
        Label whisperLabel = new Label("WhisperX:");
        whisperLabel.setStyle("-fx-font-weight: bold;");
        whisperStatus = new Label("⏳ Checking...");
        statusGrid.add(whisperLabel, 0, 3);
        statusGrid.add(whisperStatus, 1, 3);
        
        // Row 4: TorchCodec
        Label torchCodecLabel = new Label("TorchCodec:");
        torchCodecLabel.setStyle("-fx-font-weight: bold;");
        torchCodecStatus = new Label("⏳ Checking...");
        statusGrid.add(torchCodecLabel, 0, 4);
        statusGrid.add(torchCodecStatus, 1, 4);
        
        // Row 5: GPU
        Label gpuLabel = new Label("GPU Acceleration:");
        gpuLabel.setStyle("-fx-font-weight: bold;");
        gpuStatus = new Label("⏳ Checking...");
        statusGrid.add(gpuLabel, 0, 5);
        statusGrid.add(gpuStatus, 1, 5);
        
        // Python version warning
        pythonVersionWarning = new Label();
        pythonVersionWarning.setStyle("-fx-font-size: 12px; -fx-text-fill: #ed6c02; -fx-padding: 5 0 0 0;");
        pythonVersionWarning.setWrapText(true);
        pythonVersionWarning.setVisible(false);
        GridPane.setColumnSpan(pythonVersionWarning, 2);
        statusGrid.add(pythonVersionWarning, 0, 6);
        
        // Progress indicator
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);
        
        HBox progressArea = new HBox(20, spinner, statusGrid);
        progressArea.setAlignment(Pos.CENTER_LEFT);
        
        // Status message area
        Label statusMessage = new Label("Running dependency checks...");
        statusMessage.setStyle("-fx-font-size: 12px; -fx-text-fill: #666; -fx-font-style: italic;");
        
        stepContent.getChildren().addAll(title, description, progressArea, statusMessage);
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setDisable(true);
        skipButton.setVisible(true);
        
        // Run checks asynchronously
        CompletableFuture.runAsync(() -> {
            // Check FFmpeg
            DependencyManager.DependencyStatus ffmpeg = dependencyManager.checkFFmpeg();
            ffmpegOk = ffmpeg.isAvailable();
            Platform.runLater(() -> {
                ffmpegStatus.setText(ffmpegOk ? "✅ " + ffmpeg.getMessage() : "❌ " + ffmpeg.getMessage());
                ffmpegStatus.setStyle(ffmpegOk ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #c62828;");
                updateProgress(0.15);
            });
            
            // Check FFprobe
            DependencyManager.DependencyStatus ffprobe = dependencyManager.checkFFprobe();
            ffprobeOk = ffprobe.isAvailable();
            Platform.runLater(() -> {
                ffprobeStatus.setText(ffprobeOk ? "✅ " + ffprobe.getMessage() : "❌ " + ffprobe.getMessage());
                ffprobeStatus.setStyle(ffprobeOk ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #c62828;");
                updateProgress(0.30);
            });
            
            // Check Python and WhisperX
            checkPythonAndWhisperX();
            
            // Check TorchCodec
            checkTorchCodec();
            
            // Check GPU
            boolean hasGPU = checkGPUAvailability();
            Platform.runLater(() -> {
                gpuStatus.setText(hasGPU 
                    ? "✅ NVIDIA GPU detected - will use CUDA for faster transcription" 
                    : "ℹ️ No NVIDIA GPU detected - using CPU mode (slower but works)");
                gpuStatus.setStyle(hasGPU ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #7f8c8d;");
                updateProgress(1.0);
                
                // Enable next button
                nextButton.setDisable(false);
                statusMessage.setText("✅ Dependency check complete!");
                
                // Update button text based on results
                if (allDependenciesMet()) {
                    nextButton.setText("Next →");
                    statusLabel.setText("All dependencies are installed! Ready to proceed.");
                } else {
                    nextButton.setText("Show Installation Guide →");
                    statusLabel.setText("Some dependencies are missing. We'll show you how to install them.");
                }
            });
        });
    }
    
    // ========================================================================
    //  Dependency Check Methods
    // ========================================================================
    
    private void checkPythonAndWhisperX() {
        try {
            String python = WhisperXTranscriptionService.resolvePythonExecutable();
            
            // Check Python version
            String versionOutput = runCommand(python, "--version", 5);
            PythonVersionInfo versionInfo = parsePythonVersion(versionOutput);
            
            Platform.runLater(() -> {
                if (versionInfo.isValid) {
                    boolean compatible = versionInfo.isCompatible();
                    if (compatible) {
                        pythonStatus.setText("✅ Python " + versionInfo.version + " (compatible)");
                        pythonStatus.setStyle("-fx-text-fill: #2e7d32;");
                        pythonOk = true;
                        pythonVersionWarning.setVisible(false);
                    } else {
                        pythonStatus.setText("❌ Python " + versionInfo.version + " is NOT supported");
                        pythonStatus.setStyle("-fx-text-fill: #c62828;");
                        pythonOk = false;
                        pythonVersionWarning.setText(
                            "⚠️ Python " + versionInfo.version + " is not compatible with WhisperX.\n" +
                            "Please install Python 3.10, 3.11, or 3.12 from python.org"
                        );
                        pythonVersionWarning.setVisible(true);
                        pythonVersionWarning.setStyle("-fx-font-size: 12px; -fx-text-fill: #c62828; -fx-padding: 5 0 0 0;");
                    }
                } else {
                    pythonStatus.setText("❌ Python not found in PATH");
                    pythonStatus.setStyle("-fx-text-fill: #c62828;");
                    pythonOk = false;
                    pythonVersionWarning.setText(
                        "⚠️ Python is not installed or not in your PATH.\n" +
                        "Please install Python 3.10, 3.11, or 3.12 from python.org"
                    );
                    pythonVersionWarning.setVisible(true);
                }
                updateProgress(0.50);
            });
            
            // Check WhisperX
            if (pythonOk || versionInfo.isValid) {
                try {
                    String whisperCheck = runCommand(python, "-c", "import whisperx; print('OK')", 10);
                    boolean whisperInstalled = whisperCheck != null && whisperCheck.contains("OK");
                    Platform.runLater(() -> {
                        if (whisperInstalled) {
                            whisperStatus.setText("✅ WhisperX installed");
                            whisperStatus.setStyle("-fx-text-fill: #2e7d32;");
                            whisperOk = true;
                        } else {
                            whisperStatus.setText("❌ WhisperX not installed");
                            whisperStatus.setStyle("-fx-text-fill: #c62828;");
                            whisperOk = false;
                        }
                        updateProgress(0.65);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        whisperStatus.setText("❌ WhisperX not installed");
                        whisperStatus.setStyle("-fx-text-fill: #c62828;");
                        whisperOk = false;
                        updateProgress(0.65);
                    });
                }
            }
        } catch (IllegalStateException e) {
            Platform.runLater(() -> {
                pythonStatus.setText("❌ Python not found: " + e.getMessage());
                pythonStatus.setStyle("-fx-text-fill: #c62828;");
                pythonOk = false;
                whisperStatus.setText("❌ WhisperX not installed (Python missing)");
                whisperStatus.setStyle("-fx-text-fill: #c62828;");
                whisperOk = false;
                updateProgress(0.65);
            });
        } catch (Exception ex) {
            System.getLogger(OnboardingWizard.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
    
    private void checkTorchCodec() {
        try {
            String python = WhisperXTranscriptionService.resolvePythonExecutable();
            try {
                String output = runCommand(python, "-c", 
                    "import torchcodec; print('OK')", 10);
                boolean installed = output != null && output.contains("OK");
                Platform.runLater(() -> {
                    if (installed) {
                        torchCodecStatus.setText("✅ TorchCodec installed");
                        torchCodecStatus.setStyle("-fx-text-fill: #2e7d32;");
                        torchCodecOk = true;
                    } else {
                        torchCodecStatus.setText("⚠️ TorchCodec not found (optional, but recommended)");
                        torchCodecStatus.setStyle("-fx-text-fill: #ed6c02;");
                        torchCodecOk = false;
                    }
                    updateProgress(0.80);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (e.getMessage() != null && e.getMessage().contains("libtorchcodec")) {
                        torchCodecStatus.setText("⚠️ TorchCodec installed but missing DLLs (Windows issue)");
                        torchCodecStatus.setStyle("-fx-text-fill: #ed6c02;");
                        torchCodecOk = false;
                    } else {
                        torchCodecStatus.setText("⚠️ TorchCodec not found (optional)");
                        torchCodecStatus.setStyle("-fx-text-fill: #ed6c02;");
                        torchCodecOk = false;
                    }
                    updateProgress(0.80);
                });
            }
        } catch (Exception e) {
            Platform.runLater(() -> {
                torchCodecStatus.setText("⚠️ TorchCodec check failed");
                torchCodecStatus.setStyle("-fx-text-fill: #ed6c02;");
                torchCodecOk = false;
                updateProgress(0.80);
            });
        }
    }
    
    private boolean checkGPUAvailability() {
        try {
            Process process = new ProcessBuilder("nvidia-smi").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private String runCommand(String... command) throws Exception {
        return runCommand(command, 10);
    }
    
    private String runCommand(String[] command, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            return output.toString();
        }
    }
    
    private String runCommand(String executable, String arg, int timeoutSeconds) throws Exception {
        return runCommand(new String[]{executable, arg}, timeoutSeconds);
    }
    
    private String runCommand(String executable, String arg1, String arg2, int timeoutSeconds) throws Exception {
        return runCommand(new String[]{executable, arg1, arg2}, timeoutSeconds);
    }
    
    private PythonVersionInfo parsePythonVersion(String versionOutput) {
        if (versionOutput == null || versionOutput.isEmpty()) {
            return new PythonVersionInfo(false, "", -1, -1, -1);
        }
        Pattern pattern = Pattern.compile("Python (\\d+)\\.(\\d+)\\.(\\d+)");
        Matcher matcher = pattern.matcher(versionOutput);
        if (matcher.find()) {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            boolean compatible = (major == PYTHON_MIN_MAJOR && minor >= PYTHON_MIN_MINOR && minor <= PYTHON_MAX_MINOR);
            return new PythonVersionInfo(true, matcher.group(0), major, minor, patch, compatible);
        }
        return new PythonVersionInfo(false, versionOutput.trim(), -1, -1, -1);
    }
    
    // ========================================================================
    //  Step 1: Installation Instructions
    // ========================================================================
    
    private void showInstallationInstructions() {
        VBox stepContent = new VBox(15);
        
        Label title = new Label("Step 2: Installation Guide");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        
        Label description = new Label(
            "Here's what you need to install. Follow these steps in order."
        );
        description.setWrapText(true);
        description.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        
        // Installation instructions
        installInstructions = new TextArea(
            "═══════════════════════════════════════════════════════════════\n" +
            "  📋 INSTALLATION REQUIREMENTS\n" +
            "═══════════════════════════════════════════════════════════════\n\n" +
            "  1. PYTHON 3.10, 3.11, or 3.12 (REQUIRED)\n" +
            "     ────────────────────────────────────────\n" +
            "     • Python 3.13+ is NOT supported!\n" +
            "     • Download from: https://www.python.org/downloads/\n" +
            "     • During installation, check \"Add Python to PATH\"\n" +
            "     • Verify: open CMD and type: python --version\n\n" +
            "  2. WHISPERX (REQUIRED for transcription)\n" +
            "     ──────────────────────────────────────\n" +
            "     • Open CMD and run:\n" +
            "       pip install whisperx\n\n" +
            "     • If you get an error, try:\n" +
            "       pip install --upgrade pip\n" +
            "       pip install whisperx\n\n" +
            "  3. TORCHCODEC (RECOMMENDED for Windows)\n" +
            "     ──────────────────────────────────────\n" +
            "     • Open CMD and run:\n" +
            "       pip install torchcodec==0.7.0\n\n" +
            "     • If you see DLL errors after installation:\n" +
            "       Copy FFmpeg DLLs to the torchcodec folder\n" +
            "       (See Troubleshooting Guide for details)\n\n" +
            "  4. FFMPEG & FFPROBE (REQUIRED)\n" +
            "     ─────────────────────────────\n" +
            "     • Download from: https://ffmpeg.org/download.html\n" +
            "     • Windows: Place ffmpeg.exe and ffprobe.exe in:\n" +
            "       C:\\AI\\ffmpeg\\bin\\\n" +
            "     • Or add them to your system PATH\n\n" +
            "  5. GPU ACCELERATION (OPTIONAL)\n" +
            "     ─────────────────────────────\n" +
            "     • Requires NVIDIA GPU with CUDA support\n" +
            "     • Install NVIDIA drivers and CUDA Toolkit 12.x\n" +
            "     • Check with: nvidia-smi\n\n" +
            "═══════════════════════════════════════════════════════════════\n" +
            "  💡 Need help? Press F1 for Troubleshooting Guide\n" +
            "═══════════════════════════════════════════════════════════════"
        );
        installInstructions.setEditable(false);
        installInstructions.setWrapText(true);
        installInstructions.setPrefHeight(400);
        installInstructions.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; -fx-font-size: 12px;");
        
        // Status summary
        VBox statusSummary = new VBox(5);
        statusSummary.setPadding(new Insets(10, 0, 0, 0));
        
        Label summaryLabel = new Label("Current Status:");
        summaryLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        
        HBox statusBox = new HBox(15);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        
        Label ffmpegIcon = new Label(ffmpegOk ? "✅ FFmpeg" : "❌ FFmpeg");
        ffmpegIcon.setStyle(ffmpegOk ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #c62828;");
        
        Label pythonIcon = new Label(pythonOk ? "✅ Python" : "❌ Python");
        pythonIcon.setStyle(pythonOk ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #c62828;");
        
        Label whisperIcon = new Label(whisperOk ? "✅ WhisperX" : "❌ WhisperX");
        whisperIcon.setStyle(whisperOk ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #c62828;");
        
        statusBox.getChildren().addAll(ffmpegIcon, pythonIcon, whisperIcon);
        
        // Check again button
        Button checkAgainButton = new Button("🔄 Re-check Dependencies");
        checkAgainButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-padding: 8 16; -fx-background-radius: 4;");
        checkAgainButton.setOnAction(e -> {
            statusLabel.setText("Re-checking dependencies...");
            showStep(0);
        });
        
        HBox buttonRow = new HBox(15, checkAgainButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        
        statusSummary.getChildren().addAll(summaryLabel, statusBox, buttonRow);
        
        stepContent.getChildren().addAll(title, description, installInstructions, statusSummary);
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setText("I've installed everything →");
        nextButton.setDisable(false);
        skipButton.setVisible(true);
        statusLabel.setText("Install the missing dependencies, then click 'Next'.");
    }
    
    // ========================================================================
    //  Step 2: Model Selection
    // ========================================================================
    
    private void showModelSelection() {
        VBox stepContent = new VBox(15);
        
        Label title = new Label("Step 3: Select Models");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        
        Label description = new Label(
            "Select which Whisper models to use. Larger models are more accurate but slower.\n" +
            "Models must be downloaded manually using HuggingFace CLI (see documentation)."
        );
        description.setWrapText(true);
        description.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        
        // Model selection grid
        GridPane modelGrid = new GridPane();
        modelGrid.setHgap(15);
        modelGrid.setVgap(10);
        modelGrid.setPadding(new Insets(10));
        
        String[][] models = {
            {"tiny", "39 MB", "Fastest, lowest accuracy"},
            {"base", "74 MB", "Fast, basic accuracy"},
            {"small", "244 MB", "Good balance"},
            {"medium", "769 MB", "High accuracy, slower"},
            {"large-v3", "1.5 GB", "Best accuracy, very slow"}
        };
        
        modelCheckboxes = new CheckBox[models.length];
        for (int i = 0; i < models.length; i++) {
            CheckBox cb = new CheckBox(models[i][0] + " (" + models[i][1] + ")");
            cb.setUserData(models[i][0]);
            cb.setSelected(i >= 2 && i <= 3);
            cb.setStyle("-fx-font-size: 13px;");
            modelCheckboxes[i] = cb;
            
            Label info = new Label(models[i][2]);
            info.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            info.setPadding(new Insets(0, 0, 0, 22));
            
            VBox cell = new VBox(2, cb, info);
            modelGrid.add(cell, i % 3, i / 3 * 2);
        }
        
        // Additional models
        alignmentCb = new CheckBox("Alignment Model (360 MB) - for precise timestamps");
        alignmentCb.setSelected(true);
        alignmentCb.setStyle("-fx-font-size: 13px;");
        
        diarizationCb = new CheckBox("Speaker Diarization Model (100+ MB) - identifies speakers");
        diarizationCb.setSelected(true);
        diarizationCb.setStyle("-fx-font-size: 13px;");
        
        Label hfTokenNote = new Label(
            "💡 Speaker diarization requires a HuggingFace token.\n" +
            "Get one at: https://huggingface.co/settings/tokens"
        );
        hfTokenNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #666; -fx-padding: 5 0 0 0;");
        hfTokenNote.setWrapText(true);
        
        VBox additionalModels = new VBox(8, alignmentCb, diarizationCb, hfTokenNote);
        additionalModels.setPadding(new Insets(10, 0, 0, 0));
        
        // Model download instructions
        Label downloadInstructions = new Label(
            "📥 How to download models:\n" +
            "1. Install HuggingFace CLI: pip install huggingface_hub[cli]\n" +
            "2. Download a model: huggingface-cli download Systran/faster-whisper-<model-name>\n" +
            "3. Example: huggingface-cli download Systran/faster-whisper-small"
        );
        downloadInstructions.setStyle("-fx-font-size: 12px; -fx-text-fill: #555; -fx-padding: 10 0 0 0;");
        downloadInstructions.setWrapText(true);
        
        stepContent.getChildren().addAll(
            title, description, 
            new Separator(),
            modelGrid, 
            new Separator(),
            additionalModels,
            downloadInstructions
        );
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setDisable(false);
        skipButton.setVisible(true);
        nextButton.setText("Next →");
        statusLabel.setText("Select the models you want to use.");
    }
    
    // ========================================================================
    //  Step 3: Download Progress (Simulated - Real implementation would use ModelManager)
    // ========================================================================
    
    private void showDownloadProgress() {
        VBox stepContent = new VBox(15);
        
        Label title = new Label("Step 4: Model Download");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        
        Label description = new Label(
            "AudioManager does NOT download models automatically. You must download them manually.\n" +
            "Follow the instructions from the previous step to download your selected models.\n\n" +
            "Once downloaded, models will be detected automatically."
        );
        description.setWrapText(true);
        description.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        
        // Status
        VBox downloadStatus = new VBox(5);
        downloadStatus.setPadding(new Insets(10, 0, 0, 0));
        
        Label statusTitle = new Label("Model Detection Status:");
        statusTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        
        // Check selected models
        VBox modelStatusList = new VBox(3);
        for (CheckBox cb : modelCheckboxes) {
            if (cb.isSelected()) {
                String modelName = (String) cb.getUserData();
                Label modelStatus = new Label("⏳ Checking " + modelName + "...");
                modelStatus.setStyle("-fx-font-size: 12px;");
                modelStatusList.getChildren().add(modelStatus);
                
                // Check if model exists
                CompletableFuture.runAsync(() -> {
                    boolean exists = modelManager.isModelValid(modelName, "whisper") ||
                                    HuggingFaceCacheResolver.resolve(modelName).isPresent();
                    Platform.runLater(() -> {
                        modelStatus.setText((exists ? "✅ " : "❌ ") + modelName + 
                            (exists ? " - found in cache" : " - not found (download manually)"));
                        modelStatus.setStyle(exists ? "-fx-text-fill: #2e7d32; -fx-font-size: 12px;" : 
                                                  "-fx-text-fill: #c62828; -fx-font-size: 12px;");
                    });
                });
            }
        }
        
        // Alignment model check
        if (alignmentCb.isSelected()) {
            Label alignStatus = new Label("⏳ Checking alignment model...");
            alignStatus.setStyle("-fx-font-size: 12px;");
            modelStatusList.getChildren().add(alignStatus);
            
            CompletableFuture.runAsync(() -> {
                try {
                    Path modelPath = Paths.get(System.getProperty("user.home"),
                            ".cache", "torch", "hub", "checkpoints", "wav2vec2_fairseq_base_ls960_asr_ls960.pth");
                    boolean exists = Files.exists(modelPath) && Files.size(modelPath) > 300_000_000;
                    Platform.runLater(() -> {
                        alignStatus.setText((exists ? "✅ " : "❌ ") +
                                "Alignment model" + (exists ? " - found" : " - not found (auto-downloads on first use)"));
                        alignStatus.setStyle(exists ? "-fx-text-fill: #2e7d32; -fx-font-size: 12px;" :
                                "-fx-text-fill: #ed6c02; -fx-font-size: 12px;");
                    });
                } catch (IOException ex) {
                    System.getLogger(OnboardingWizard.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
                }
            });
        }
        
        Button refreshButton = new Button("🔄 Refresh Status");
        refreshButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-padding: 8 16; -fx-background-radius: 4;");
        refreshButton.setOnAction(e -> {
            statusLabel.setText("Refreshing model status...");
            showDownloadProgress();
        });
        
        HBox refreshBox = new HBox(15, refreshButton);
        refreshBox.setAlignment(Pos.CENTER_LEFT);
        refreshBox.setPadding(new Insets(10, 0, 0, 0));
        
        downloadStatus.getChildren().addAll(statusTitle, modelStatusList, refreshBox);
        
        stepContent.getChildren().addAll(title, description, downloadStatus);
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setText("Next →");
        nextButton.setDisable(false);
        skipButton.setVisible(true);
        statusLabel.setText("Check that your selected models are downloaded.");
    }
    
    // ========================================================================
    //  Step 4: Completion
    // ========================================================================
    
    private void showCompletion() {
        VBox stepContent = new VBox(20);
        stepContent.setAlignment(Pos.CENTER);
        
        Label title = new Label("🎉 Setup Complete!");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #2e7d32;");
        
        // Summary of status
        VBox summaryBox = new VBox(8);
        summaryBox.setAlignment(Pos.CENTER_LEFT);
        summaryBox.setPadding(new Insets(10, 20, 10, 20));
        summaryBox.setStyle("-fx-background-color: #f8f9fa; -fx-border-radius: 4; -fx-background-radius: 4;");
        
        Label summaryTitle = new Label("✅ All systems ready!");
        summaryTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        
        Label summary1 = new Label((ffmpegOk ? "✅" : "❌") + " FFmpeg: " + (ffmpegOk ? "Installed" : "Missing"));
        Label summary2 = new Label((pythonOk ? "✅" : "❌") + " Python: " + (pythonOk ? "Installed" : "Missing"));
        Label summary3 = new Label((whisperOk ? "✅" : "❌") + " WhisperX: " + (whisperOk ? "Installed" : "Missing"));
        
        summaryBox.getChildren().addAll(summaryTitle, summary1, summary2, summary3);
        
        if (!allDependenciesMet()) {
            Label warning = new Label("⚠️ Some dependencies are still missing. You can still use the app, but some features may not work.");
            warning.setStyle("-fx-text-fill: #ed6c02; -fx-font-weight: bold; -fx-font-size: 13px;");
            warning.setWrapText(true);
            summaryBox.getChildren().add(warning);
        }
        
        Label message = new Label(
            "AudioManager is ready to use.\n\n" +
            "• Click 'Browse' to select audio files\n" +
            "• Adjust settings in the Configuration panel\n" +
            "• Press 'Start Processing' to begin\n" +
            "• Press F1 for Troubleshooting Guide"
        );
        message.setStyle("-fx-font-size: 14px; -fx-text-fill: #555;");
        message.setAlignment(Pos.CENTER);
        message.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        
        Button startButton = new Button("🚀 Launch AudioManager");
        startButton.setStyle("-fx-font-size: 16px; -fx-padding: 12 40; -fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8;");
        startButton.setOnAction(e -> {
            completed = true;
            stage.close();
        });
        
        stepContent.getChildren().addAll(title, summaryBox, message, startButton);
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setVisible(false);
        skipButton.setVisible(false);
        backButton.setVisible(false);
        statusLabel.setText("✅ Setup complete! Click Launch to start.");
        overallProgress.setProgress(1.0);
    }
    
    // ========================================================================
    //  Helper Methods
    // ========================================================================
    
    private void updateProgress(double value) {
        double current = overallProgress.getProgress();
        overallProgress.setProgress(Math.max(current, Math.min(1.0, value)));
    }
    
    private void showExitConfirmation() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Exit Setup");
        confirm.setHeaderText("Exit the setup wizard?");
        confirm.setContentText("You can always run the setup again later.\n\n" +
            "Note: Some features may not work until dependencies are installed.");
        ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                completed = false;
                stage.close();
            }
        });
    }
    
    private void skipOnboarding() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Skip Setup");
        confirm.setHeaderText("Skip the setup wizard?");
        confirm.setContentText("You can always run the setup again later.\n\n" +
            "Note: Some features may not work until dependencies are installed.");
        ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                completed = true;
                stage.close();
            }
        });
    }
    
    // ========================================================================
    //  Inner Class: PythonVersionInfo
    // ========================================================================
    
    private static class PythonVersionInfo {
        final boolean isValid;
        final String version;
        final int major;
        final int minor;
        final int patch;
        final boolean compatible;
        
        PythonVersionInfo(boolean isValid, String version, int major, int minor, int patch) {
            this(isValid, version, major, minor, patch, false);
        }
        
        PythonVersionInfo(boolean isValid, String version, int major, int minor, int patch, boolean compatible) {
            this.isValid = isValid;
            this.version = version;
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.compatible = compatible;
        }
        
        boolean isCompatible() {
            return compatible;
        }
    }
}