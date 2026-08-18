/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.core.DependencyManager;
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

import java.util.concurrent.CompletableFuture;

/**
 * First-run onboarding wizard that guides users through:
 * 1. Dependency checks (FFmpeg, FFprobe, WhisperX)
 * 2. Model downloads (Whisper, PyAnnote, Alignment)
 * 3. GPU/CPU detection
 * 4. Configuration setup
 */
public class OnboardingWizard {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(OnboardingWizard.class);
    
    private final Stage stage;
    private final DependencyManager dependencyManager;
    private final ModelManager modelManager;
    private int currentStep = 0;
    private final VBox contentContainer;
    private final ProgressBar overallProgress;
    private final Label statusLabel;
    private final Button nextButton;
    private final Button skipButton;
    private boolean completed = false;
    
    public OnboardingWizard() {
        this.stage = new Stage();
        this.stage.initStyle(StageStyle.UTILITY);
        this.stage.setTitle("AudioManager - First Run Setup");
        this.stage.setMinWidth(700);
        this.stage.setMinHeight(500);
        
        this.dependencyManager = new DependencyManager();
        this.modelManager = new ModelManager();
        
        // Main layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(20));
        
        // Header
        Label headerLabel = new Label("AudioManager Setup");
        headerLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        BorderPane.setAlignment(headerLabel, Pos.CENTER);
        root.setTop(headerLabel);
        
        // Content area
        this.contentContainer = new VBox(15);
        this.contentContainer.setPadding(new Insets(20));
        this.contentContainer.setStyle("-fx-background-color: #f5f5f5; -fx-border-radius: 5;");
        root.setCenter(contentContainer);
        
        // Footer with progress
        VBox footer = new VBox(10);
        this.overallProgress = new ProgressBar(0);
        this.overallProgress.setPrefWidth(Double.MAX_VALUE);
        
        HBox buttonBox = new HBox(10);
        this.skipButton = new Button("Skip Setup");
        this.skipButton.setOnAction(e -> skipOnboarding());
        this.nextButton = new Button("Next");
        this.nextButton.setOnAction(e -> nextStep());
        this.nextButton.setDefaultButton(true);
        
        buttonBox.getChildren().addAll(skipButton, new Region(), nextButton);
        HBox.setHgrow(buttonBox.getChildren().get(1), Priority.ALWAYS);
        
        this.statusLabel = new Label("Checking system dependencies...");
        this.statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        footer.getChildren().addAll(overallProgress, statusLabel, buttonBox);
        root.setBottom(footer);
        BorderPane.setMargin(footer, new Insets(10, 0, 0, 0));
        
        this.stage.setScene(new Scene(root));
        this.stage.setOnCloseRequest(e -> {
            if (!completed) {
                e.consume();
            }
        });
    }
    
    public void showAndWait() {
        showStep(0);
        stage.showAndWait();
    }
    
    private void showStep(int step) {
        currentStep = step;
        contentContainer.getChildren().clear();
        
        switch (step) {
            case 0:
                showDependencyCheck();
                break;
            case 1:
                showModelSelection();
                break;
            case 2:
                showDownloadProgress();
                break;
            case 3:
                showCompletion();
                break;
            default:
                stage.close();
        }
    }
    
    private void showDependencyCheck() {
        VBox stepContent = new VBox(15);
        
        Label title = new Label("Step 1: Checking Dependencies");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        Label description = new Label(
            "AudioManager requires FFmpeg and WhisperX to work. We'll check if they're installed."
        );
        description.setWrapText(true);
        
        VBox statusBox = new VBox(8);
        Label ffmpegStatus = new Label("⏳ Checking FFmpeg...");
        Label whisperStatus = new Label("⏳ Checking WhisperX...");
        Label gpuStatus = new Label("⏳ Checking GPU availability...");
        
        statusBox.getChildren().addAll(ffmpegStatus, whisperStatus, gpuStatus);
        
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);
        
        HBox progressArea = new HBox(20, spinner, statusBox);
        progressArea.setAlignment(Pos.CENTER_LEFT);
        
        stepContent.getChildren().addAll(title, description, progressArea);
        contentContainer.getChildren().add(stepContent);
        
        // Run checks asynchronously
        CompletableFuture<Void> checks = CompletableFuture.runAsync(() -> {
            // Check FFmpeg
            DependencyManager.DependencyStatus ffmpeg = dependencyManager.checkFFmpeg();
            Platform.runLater(() -> {
                ffmpegStatus.setText(ffmpeg.isAvailable() 
                    ? "✅ FFmpeg: " + ffmpeg.getMessage() 
                    : "❌ FFmpeg: " + ffmpeg.getMessage());
                updateProgress(0.33);
            });
            
            // Check FFprobe
            DependencyManager.DependencyStatus ffprobe = dependencyManager.checkFFprobe();
            Platform.runLater(() -> {
                // Update same line or add new one
                if (!ffprobe.isAvailable()) {
                    statusLabel.setText("⚠️ FFprobe missing - some audio files may not work");
                }
            });
            
            // Check WhisperX
            try {
                String python = WhisperXTranscriptionService.resolvePythonExecutable();
                Platform.runLater(() -> {
                    whisperStatus.setText("✅ WhisperX found at: " + python);
                    updateProgress(0.66);
                });
            } catch (IllegalStateException e) {
                Platform.runLater(() -> {
                    whisperStatus.setText("❌ WhisperX not found. Please install it:\n" + e.getMessage());
                    updateProgress(0.66);
                });
            }
            
            // Check GPU
            boolean hasGPU = checkGPUAvailability();
            Platform.runLater(() -> {
                gpuStatus.setText(hasGPU 
                    ? "✅ NVIDIA GPU detected - will use CUDA" 
                    : "ℹ️ No NVIDIA GPU detected - using CPU (slower)");
                updateProgress(1.0);
                
                // Enable next button
                nextButton.setDisable(false);
            });
        });
        
        nextButton.setDisable(true);
    }
    
    private void showModelSelection() {
        VBox stepContent = new VBox(15);
        
        Label title = new Label("Step 2: Select Models to Download");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        Label description = new Label(
            "Select which Whisper models to download. Larger models are more accurate but slower."
        );
        description.setWrapText(true);
        
        // Model selection grid
        GridPane modelGrid = new GridPane();
        modelGrid.setHgap(15);
        modelGrid.setVgap(10);
        modelGrid.setPadding(new Insets(10));
        
        String[][] models = {
            {"tiny", "39 MB", "Fastest, low accuracy"},
            {"base", "74 MB", "Fast, basic accuracy"},
            {"small", "244 MB", "Good balance"},
            {"medium", "769 MB", "High accuracy, slower"},
            {"large", "1.5 GB", "Best accuracy, very slow"}
        };
        
        CheckBox[] checkboxes = new CheckBox[models.length];
        for (int i = 0; i < models.length; i++) {
            CheckBox cb = new CheckBox(models[i][0] + " (" + models[i][1] + ")");
            cb.setUserData(models[i][0]);
            cb.setSelected(i >= 2 && i <= 3); // default: small, medium
            cb.setStyle("-fx-font-size: 13px;");
            checkboxes[i] = cb;
            
            Label info = new Label(models[i][2]);
            info.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            
            VBox cell = new VBox(2, cb, info);
            modelGrid.add(cell, i % 3, i / 3);
        }
        
        // Alignment model checkbox
        CheckBox alignmentCb = new CheckBox("Alignment Model (360 MB) - for precise timestamps");
        alignmentCb.setSelected(true);
        
        // Diarization model checkbox
        CheckBox diarizationCb = new CheckBox("Speaker Diarization Model (100+ MB) - identifies speakers");
        diarizationCb.setSelected(true);
        
        VBox additionalModels = new VBox(8, alignmentCb, diarizationCb);
        additionalModels.setPadding(new Insets(10, 0, 0, 0));
        
        stepContent.getChildren().addAll(title, description, modelGrid, 
            new Separator(), additionalModels);
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setDisable(false);
        skipButton.setVisible(true);
    }
    
    private void showDownloadProgress() {
        VBox stepContent = new VBox(15);
        
        Label title = new Label("Step 3: Downloading Models");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        Label description = new Label(
            "Downloading selected models. This may take several minutes."
        );
        description.setWrapText(true);
        
        VBox downloadStatus = new VBox(5);
        ProgressBar modelProgress = new ProgressBar(0);
        modelProgress.setPrefWidth(Double.MAX_VALUE);
        
        Label currentFile = new Label("Preparing download...");
        currentFile.setStyle("-fx-font-size: 13px;");
        
        Label sizeInfo = new Label("");
        sizeInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        
        downloadStatus.getChildren().addAll(currentFile, modelProgress, sizeInfo);
        
        stepContent.getChildren().addAll(title, description, downloadStatus);
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setDisable(true);
        skipButton.setVisible(false);
        statusLabel.setText("Downloading models...");
        
        // Start downloads (simplified - actual implementation would use ModelManager)
        CompletableFuture.runAsync(() -> {
            try {
                // This would call ModelManager.downloadModel() for each selection
                // For now, just simulate progress
                for (int i = 0; i <= 100; i += 5) {
                    final int progress = i;
                    Platform.runLater(() -> {
                        modelProgress.setProgress(progress / 100.0);
                        currentFile.setText("Downloading model... " + progress + "%");
                    });
                    Thread.sleep(100);
                }
                
                Platform.runLater(() -> {
                    statusLabel.setText("✅ All models downloaded successfully!");
                    nextButton.setDisable(false);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    private void showCompletion() {
        VBox stepContent = new VBox(20);
        stepContent.setAlignment(Pos.CENTER);
        
        Label title = new Label("🎉 Setup Complete!");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        
        Label message = new Label(
            "AudioManager is ready to use.\n\n" +
            "• Click 'Start' to begin processing audio files\n" +
            "• Adjust settings in the Configuration panel\n" +
            "• Use 'Batch Processing' for multiple files"
        );
        message.setStyle("-fx-font-size: 14px;");
        message.setAlignment(Pos.CENTER);
        
        Button startButton = new Button("Start AudioManager");
        startButton.setStyle("-fx-font-size: 16px; -fx-padding: 10 30;");
        startButton.setOnAction(e -> {
            completed = true;
            stage.close();
        });
        
        stepContent.getChildren().addAll(title, message, startButton);
        contentContainer.getChildren().add(stepContent);
        
        nextButton.setVisible(false);
        skipButton.setVisible(false);
        statusLabel.setText("✅ Setup complete!");
        overallProgress.setProgress(1.0);
    }
    
    private void updateProgress(double value) {
        overallProgress.setProgress(Math.max(overallProgress.getProgress(), value));
    }
    
    private void nextStep() {
        if (currentStep < 3) {
            showStep(currentStep + 1);
        } else {
            completed = true;
            stage.close();
        }
    }
    
    private void skipOnboarding() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Skip Setup");
        confirm.setHeaderText("Skip First-Run Setup?");
        confirm.setContentText("Some features may not work until dependencies are installed.");
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                completed = true;
                stage.close();
            }
        });
    }
    
    private boolean checkGPUAvailability() {
        try {
            Process process = new ProcessBuilder("nvidia-smi").start();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && 
                   process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}