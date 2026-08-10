/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.plugins;

/**
 *
 * @author USER
 */

import audiomanager.core.DependencyManager;
import audiomanager.util.PreferenceManager;
import java.io.BufferedReader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;

import javafx.scene.control.Alert;

/**
 * Audio file splitting tool using FFmpeg
 */
public class AudioSplitterTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioSplitterTool.class);
    
    private final DependencyManager dependencyManager;
    private final PreferenceManager prefManager; // Add this
    private Consumer<String> logger;
    
    // UI Components
    private TextField filePathField;
    private TextField segmentDurationField;
    private TextField outputDirField;
    private Button splitButton;
    
    public AudioSplitterTool(DependencyManager dependencyManager, PreferenceManager prefManager) {
        this.dependencyManager = dependencyManager;
        this.prefManager = prefManager; // Initialize
    }
    
    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }
    
    public Node createUI() {
        // Main container with split layout
        SplitPane mainSplitPane = new SplitPane();
        mainSplitPane.setOrientation(Orientation.HORIZONTAL);

        // Left side - Controls
        VBox controlsContainer = new VBox(15);
        controlsContainer.setPadding(new Insets(20));
        controlsContainer.setPrefWidth(400);

        Label titleLabel = new Label("🎵 Audio Splitter");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        titleLabel.getStyleClass().add("panel-heading");

        // File selection section
        VBox fileSection = new VBox(8);
        Label fileLabel = new Label("Audio File:");
        fileLabel.setStyle("-fx-font-weight: bold;");
        fileLabel.getStyleClass().add("tool-subheading");

        HBox fileSelectionBox = new HBox(10);
        filePathField = new TextField();
        filePathField.setPromptText("Select audio file...");
        filePathField.setEditable(false);
        HBox.setHgrow(filePathField, Priority.ALWAYS);

        Button browseButton = new Button("📁 Browse");
        browseButton.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        browseButton.getStyleClass().add("tool-button-blue");
        browseButton.setOnAction(e -> browseAudioFile());

        fileSelectionBox.getChildren().addAll(filePathField, browseButton);
        fileSection.getChildren().addAll(fileLabel, fileSelectionBox);

        // Settings section
        VBox settingsSection = new VBox(8);
        Label settingsLabel = new Label("Split Settings:");
        settingsLabel.setStyle("-fx-font-weight: bold;");
        settingsLabel.getStyleClass().add("tool-subheading");

        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(10);
        settingsGrid.setVgap(8);

        Label durationLabel = new Label("Segment Duration (seconds):");
        segmentDurationField = new TextField();
        segmentDurationField.setPromptText("e.g., 300 for 5 minutes");
        segmentDurationField.setPrefWidth(150);

        Label outputLabel = new Label("Output Directory:");
        outputDirField = new TextField();
        outputDirField.setPromptText("Where to save split files...");
        outputDirField.setEditable(false);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);

        Button outputBrowseButton = new Button("📂 Choose");
        outputBrowseButton.setStyle("-fx-text-fill: white;");
        outputBrowseButton.getStyleClass().add("tool-button-purple");
        outputBrowseButton.setOnAction(e -> chooseOutputDirectory());

        settingsGrid.add(durationLabel, 0, 0);
        settingsGrid.add(segmentDurationField, 1, 0);
        settingsGrid.add(outputLabel, 0, 1);
        settingsGrid.add(outputDirField, 1, 1);
        settingsGrid.add(outputBrowseButton, 2, 1);

        settingsSection.getChildren().addAll(settingsLabel, settingsGrid);

        // Action button
        splitButton = new Button("✂️ Split Audio File");
        splitButton.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        splitButton.getStyleClass().add("tool-button-red");
        splitButton.setPrefHeight(40);
        splitButton.setMaxWidth(Double.MAX_VALUE);
        splitButton.setOnAction(e -> splitAudioFile());

        controlsContainer.getChildren().addAll(titleLabel, fileSection, settingsSection, splitButton);

        // Right side - Preview
        VBox previewContainer = new VBox(10);
        previewContainer.setPadding(new Insets(20));
        previewContainer.setPrefWidth(300);
        previewContainer.getStyleClass().add("tool-preview-surface");

        Label previewLabel = new Label("Split Files Preview");
        previewLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        previewLabel.getStyleClass().add("panel-heading");

        // Preview list for split files
        ListView<String> previewList = new ListView<>();
        previewList.setStyle("-fx-border-color: #ddd; -fx-border-radius: 3;");
        VBox.setVgrow(previewList, Priority.ALWAYS);

        // Preview info label
        Label previewInfoLabel = new Label("No file selected");
        previewInfoLabel.setStyle("-fx-font-size: 11px;");
        previewInfoLabel.getStyleClass().add("tool-muted-text");
        previewInfoLabel.setWrapText(true);

        previewContainer.getChildren().addAll(previewLabel, previewList, previewInfoLabel);

        // Add both containers to split pane
        mainSplitPane.getItems().addAll(controlsContainer, previewContainer);

        // Set initial divider position (30% for controls, 70% for preview)
        mainSplitPane.setDividerPositions(0.3);

        // Make both panes resizable with minimum widths
        controlsContainer.setMinWidth(300);
        previewContainer.setMinWidth(250);

        // Update preview when file is selected
        filePathField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview(previewList, previewInfoLabel));
        segmentDurationField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview(previewList, previewInfoLabel));
        outputDirField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview(previewList, previewInfoLabel));

        return mainSplitPane;
    }

    private void updatePreview(ListView<String> previewList, Label previewInfoLabel) {
        previewList.getItems().clear();

        String inputPath = filePathField.getText();
        String durationText = segmentDurationField.getText();
        String outputDir = outputDirField.getText();

        if (inputPath == null || inputPath.isEmpty()) {
            previewInfoLabel.setText("Select an audio file to see preview");
            return;
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            previewInfoLabel.setText("Selected file does not exist");
            return;
        }

        if (durationText == null || durationText.isEmpty()) {
            previewInfoLabel.setText("Enter segment duration to see preview");
            return;
        }

        if (outputDir == null || outputDir.isEmpty()) {
            previewInfoLabel.setText("Select output directory to see preview");
            return;
        }

        try {
            int segmentDuration = Integer.parseInt(durationText);
            String baseName = getFileNameWithoutExtension(inputFile.getName());
            String extension = getFileExtension(inputFile.getName());

            // Estimate number of segments based on file duration (placeholder)
            // In a real implementation, you'd get the actual duration
            int estimatedSegments = 4; // Default estimate

            previewInfoLabel.setText(String.format(
                "Input: %s (%s)\nEstimated segments: %d\nSegment duration: %d seconds",
                inputFile.getName(), extension.toUpperCase(), estimatedSegments, segmentDuration
            ));

            // Generate preview file names
            for (int i = 1; i <= estimatedSegments; i++) {
                String fileName = String.format("%s_part_%03d.%s", baseName, i, extension);
                previewList.getItems().add(fileName);
            }

        } catch (NumberFormatException e) {
            previewInfoLabel.setText("Invalid segment duration");
        }
    }
    
    private void browseAudioFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Audio File to Split");
        PreferenceManager prefManager = new PreferenceManager(AudioSplitterTool.class);

        // Use last audio splitter location
        File initialDir = new File(prefManager.getLastAudioSplitterLocation());
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        } else {
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        }

        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.flac", "*.m4a", "*.aac", "*.ogg"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            filePathField.setText(file.getAbsolutePath());

            // Remember this location for next time
            prefManager.setLastAudioSplitterLocation(file.getParent());

            log("Selected audio file for splitting: " + file.getName());

            // Set default output directory to same as input file
            if (outputDirField.getText().isEmpty()) {
                outputDirField.setText(file.getParent());
            }
        }
    }
    
    private void chooseOutputDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory for Split Files");

        // Prefer whatever's already typed in the field (mid-session choice),
        // then fall back to the last directory this dialog was actually
        // used from, so re-opening the app doesn't reset it to nothing.
        File currentDir = new File(outputDirField.getText());
        if (currentDir.exists()) {
            chooser.setInitialDirectory(currentDir);
        } else {
            File lastUsed = new File(prefManager.getLastAudioSplitterOutputLocation());
            if (lastUsed.exists()) {
                chooser.setInitialDirectory(lastUsed);
            }
        }

        File dir = chooser.showDialog(null);
        if (dir != null) {
            outputDirField.setText(dir.getAbsolutePath());
            prefManager.setLastAudioSplitterOutputLocation(dir.getAbsolutePath());
        }
    }
    
    private void splitAudioFile() {
        String inputPath = filePathField.getText();
        String durationText = segmentDurationField.getText();
        String outputDir = outputDirField.getText();
        
        // Validation
        if (inputPath == null || inputPath.isEmpty()) {
            showError("Please select an audio file to split");
            return;
        }
        
        if (durationText == null || durationText.isEmpty()) {
            showError("Please enter segment duration in seconds");
            return;
        }
        
        if (outputDir == null || outputDir.isEmpty()) {
            showError("Please select an output directory");
            return;
        }
        
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            showError("Selected audio file does not exist");
            return;
        }
        
        File outputDirectory = new File(outputDir);
        if (!outputDirectory.exists() || !outputDirectory.isDirectory()) {
            showError("Output directory does not exist or is not a directory");
            return;
        }
        
        try {
            int segmentDuration = Integer.parseInt(durationText);
            if (segmentDuration <= 0) {
                showError("Segment duration must be a positive number");
                return;
            }
            
            // Perform the actual splitting
            performAudioSplitting(inputFile, outputDirectory, segmentDuration);
            
        } catch (NumberFormatException e) {
            showError("Please enter a valid number for segment duration");
        }
    }
    
    private void performAudioSplitting(File inputFile, File outputDir, int segmentDuration) {
        // Disable the split button during processing
        splitButton.setDisable(true);
        splitButton.setText("⏳ Splitting...");

        // Make copies of parameters to make them effectively final
        final File finalInputFile = inputFile;
        final File finalOutputDir = outputDir;
        final int finalSegmentDuration = segmentDuration;

        // Run the splitting process in a background thread
        Task<Void> splitTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    log("Starting audio split: " + finalInputFile.getName() + " into " + finalSegmentDuration + "s segments");

                    // Get base filename without extension
                    String baseName = getFileNameWithoutExtension(finalInputFile.getName());
                    String outputPattern = new File(finalOutputDir, baseName + "_part_%03d." + getFileExtension(finalInputFile.getName())).getAbsolutePath();

                    // Build FFmpeg command for splitting
                    List<String> command = List.of(
                        dependencyManager.getFFmpegPath(),
                        "-i", finalInputFile.getAbsolutePath(),
                        "-f", "segment",
                        "-segment_time", String.valueOf(finalSegmentDuration),
                        "-c", "copy",
                        "-reset_timestamps", "1",
                        outputPattern
                    );

                    log("FFmpeg command: " + String.join(" ", command));

                    // Execute the command
                    ProcessBuilder builder = new ProcessBuilder(command);
                    Process process = builder.start();

                    // Read the process output to prevent blocking
                    Thread outputReader = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                             new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                LOGGER.debug("FFmpeg: {}", line);
                            }
                        } catch (IOException e) {
                            LOGGER.debug("Error reading FFmpeg output", e);
                        }
                    });
                    outputReader.setDaemon(true);
                    outputReader.start();

                    // Read error stream too
                    Thread errorReader = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                             new InputStreamReader(process.getErrorStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                LOGGER.debug("FFmpeg error: {}", line);
                                // Update log with progress information if needed
                                if (line.contains("time=")) {
                                    final String progressLine = line; // Make effectively final
                                    Platform.runLater(() -> 
                                        log("Progress: " + progressLine.trim())
                                    );
                                }
                            }
                        } catch (IOException e) {
                            LOGGER.debug("Error reading FFmpeg error stream", e);
                        }
                    });
                    errorReader.setDaemon(true);
                    errorReader.start();

                    int exitCode = process.waitFor();

                    // Wait for readers to finish
                    outputReader.join(1000);
                    errorReader.join(1000);

                    if (exitCode == 0) {
                        Platform.runLater(() -> {
                            log("✅ Audio splitting completed successfully");
                            showInfo("Audio splitting completed", 
                                "File was successfully split into segments of " + finalSegmentDuration + " seconds");
                        });
                    } else {
                        Platform.runLater(() -> {
                            log("❌ Audio splitting failed with exit code: " + exitCode);
                            showError("Audio splitting failed with exit code: " + exitCode);
                        });
                    }

                } catch (IOException | InterruptedException e) {
                    LOGGER.error("Audio splitting failed", e);
                    Platform.runLater(() -> {
                        log("❌ Audio splitting failed: " + e.getMessage());
                        showError("Audio splitting failed: " + e.getMessage());
                    });
                }
                return null;
            }

            @Override
            protected void succeeded() {
                Platform.runLater(() -> {
                    splitButton.setDisable(false);
                    splitButton.setText("✂️ Split Audio File");
                });
            }

            @Override
            protected void failed() {
                Platform.runLater(() -> {
                    splitButton.setDisable(false);
                    splitButton.setText("✂️ Split Audio File");
                    showError("Audio splitting failed: " + getException().getMessage());
                });
            }

            @Override
            protected void cancelled() {
                Platform.runLater(() -> {
                    splitButton.setDisable(false);
                    splitButton.setText("✂️ Split Audio File");
                    log("⏹️ Audio splitting cancelled");
                });
            }
        };

        // Start the background task
        Thread splitThread = new Thread(splitTask);
        splitThread.setDaemon(true); // Don't prevent JVM shutdown
        splitThread.start();
    }
    
    private String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("Audio Splitting Error");
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Success");
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void log(String message) {
        if (logger != null) {
            logger.accept("[AudioSplitter] " + message);
        }
        LOGGER.info(message);
    }
}