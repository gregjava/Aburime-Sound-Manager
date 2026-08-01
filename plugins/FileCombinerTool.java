/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.plugins;

/**
 *
 * @author USER
 */

import audiomanager.util.PreferenceManager;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;

/**
 * Text file combining tool
 */
public class FileCombinerTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileCombinerTool.class);
    
    private final PreferenceManager prefManager;
    private Consumer<String> logger;
    
    // UI Components
    private ListView<File> fileListView;
    private TextField outputPathField;
    
    public FileCombinerTool(PreferenceManager prefManager) {
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
        VBox controlsContainer = new VBox(10);
        controlsContainer.setPadding(new Insets(10));
        controlsContainer.setMinWidth(350);
        controlsContainer.setPrefWidth(400);

        Label titleLabel = new Label("📄 Text File Combiner");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #2c3e50;");

        // File selection section
        VBox fileSection = new VBox(8);
        Label fileLabel = new Label("Selected Files:");
        fileLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #34495e;");

        fileListView = new ListView<>();
        fileListView.setPrefHeight(120);
        fileListView.setMinHeight(120);
        fileListView.setMaxHeight(120);
        fileListView.setStyle("-fx-border-color: #bdc3c7; -fx-border-radius: 4;");

        HBox fileButtonBox = new HBox(10);
        Button selectFilesButton = new Button("📄 Add Files");
        selectFilesButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-weight: bold;");
        selectFilesButton.setOnAction(e -> selectTextFiles());

        Button clearFilesButton = new Button("🗑️ Clear All");
        clearFilesButton.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-weight: bold;");
        clearFilesButton.setOnAction(e -> clearFileList());

        Button removeFileButton = new Button("➖ Remove");
        removeFileButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        removeFileButton.setOnAction(e -> removeSelectedFile());

        fileButtonBox.getChildren().addAll(selectFilesButton, clearFilesButton, removeFileButton);
        fileSection.getChildren().addAll(fileLabel, fileListView, fileButtonBox);

        // Output section
        VBox outputSection = new VBox(8);
        Label outputLabel = new Label("Output File:");
        outputLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #34495e;");

        HBox outputBox = new HBox(10);
        outputPathField = new TextField();
        outputPathField.setPromptText("combined_output.txt");
        HBox.setHgrow(outputPathField, Priority.ALWAYS);

        Button browseOutputButton = new Button("📂 Choose");
        browseOutputButton.setStyle("-fx-background-color: #9b59b6; -fx-text-fill: white; -fx-font-weight: bold;");
        browseOutputButton.setOnAction(e -> chooseOutputLocation());

        outputBox.getChildren().addAll(outputPathField, browseOutputButton);
        outputSection.getChildren().addAll(outputLabel, outputBox);

        // Combine button
        Button combineButton = new Button("🔗 Combine Files");
        combineButton.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        combineButton.setPrefHeight(40);
        combineButton.setMaxWidth(Double.MAX_VALUE);
        combineButton.setOnAction(e -> combineFiles());

        controlsContainer.getChildren().addAll(titleLabel, fileSection, outputSection, combineButton);

        // Right side - Preview
        VBox previewContainer = new VBox(10);
        previewContainer.setPadding(new Insets(10));
        previewContainer.setMinWidth(300);
        previewContainer.setStyle("-fx-background-color: #f8f9fa;");

        Label previewLabel = new Label("Combined Texts Preview");
        previewLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 14px;");

        // Preview text area
        TextArea previewTextArea = new TextArea();
        previewTextArea.setPrefHeight(200);
        previewTextArea.setMinHeight(150);
        previewTextArea.setEditable(false);
        previewTextArea.setWrapText(true);
        previewTextArea.setStyle("-fx-font-family: 'Monaco', 'Consolas', monospace; -fx-font-size: 11px;");
        VBox.setVgrow(previewTextArea, Priority.ALWAYS); // Make text area fill available space

        // Preview info label
        Label previewInfoLabel = new Label("No files selected");
        previewInfoLabel.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
        previewInfoLabel.setWrapText(true);

        previewContainer.getChildren().addAll(previewLabel, previewTextArea, previewInfoLabel);

        // Add both containers to split pane
        mainSplitPane.getItems().addAll(controlsContainer, previewContainer);

        // Set initial divider position (40% for controls, 60% for preview)
        mainSplitPane.setDividerPositions(0.4);

        // Make both panes resizable with minimum widths
        controlsContainer.setMinWidth(350);
        previewContainer.setMinWidth(300);

        // Update preview when files change
        fileListView.getItems().addListener((javafx.collections.ListChangeListener<File>) change -> 
            updatePreview(previewTextArea, previewInfoLabel));
        outputPathField.textProperty().addListener((obs, oldVal, newVal) -> 
            updatePreview(previewTextArea, previewInfoLabel));

        // Create scroll pane for the entire split pane if needed
        ScrollPane mainContainer = new ScrollPane();
        mainContainer.setContent(mainSplitPane);
        mainContainer.setPadding(new Insets(0, -10, 0, 0));
        mainContainer.setFitToWidth(true);
        mainContainer.setFitToHeight(true);

        return mainContainer;
    }

    private void updatePreview(TextArea previewTextArea, Label previewInfoLabel) {
        List<File> files = new ArrayList<>(fileListView.getItems());
        String outputPath = outputPathField.getText();

        if (files.isEmpty()) {
            previewTextArea.clear();
            previewInfoLabel.setText("No files selected");
            return;
        }

        // Calculate total size and file count
        long totalSize = 0;
        int validFiles = 0;

        StringBuilder previewContent = new StringBuilder();
        previewContent.append("// Preview of combined files structure:\n\n");

        for (File file : files) {
            if (file.exists()) {
                totalSize += file.length();
                validFiles++;

                previewContent.append("=== File: ").append(file.getName()).append(" ===\n");
                previewContent.append("Size: ").append(String.format("%.1f KB", file.length() / 1024.0)).append("\n");

                // Preview first few lines if it's a text file
                if (file.getName().toLowerCase().endsWith(".txt")) {
                    try {
                        List<String> lines = Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                        int linesToShow = Math.min(3, lines.size());
                        for (int i = 0; i < linesToShow; i++) {
                            previewContent.append(lines.get(i)).append("\n");
                        }
                        if (lines.size() > linesToShow) {
                            previewContent.append("... [").append(lines.size() - linesToShow).append(" more lines]\n");
                        }
                    } catch (IOException e) {
                        previewContent.append("[Unable to preview content]\n");
                    }
                } else {
                    previewContent.append("[Binary or unsupported file type]\n");
                }
                previewContent.append("\n");
            }
        }

        previewTextArea.setText(previewContent.toString());

        String infoText = String.format(
            "Files: %d selected (%d valid)\nTotal size: %.1f KB\nOutput: %s",
            files.size(), validFiles, totalSize / 1024.0,
            outputPath.isEmpty() ? "Not specified" : new File(outputPath).getName()
        );
        previewInfoLabel.setText(infoText);
    }
    
    private void selectTextFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Text Files to Combine");
        PreferenceManager prefManager = new PreferenceManager(FileCombinerTool.class);

        // Use last text combiner location
        File initialDir = new File(prefManager.getLastTextCombinerLocation());
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        } else {
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        }

        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.srt", "*.json", "*.csv", "*.xml", "*.log"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        // Allow multiple selection
        java.util.List<File> files = fileChooser.showOpenMultipleDialog(null);
        if (files != null && !files.isEmpty()) {
            for (File file : files) {
                if (!fileListView.getItems().contains(file)) {
                    fileListView.getItems().add(file);
                }
            }

            // Remember this location for next time
            if (!files.isEmpty()) {
                prefManager.setLastTextCombinerLocation(files.get(0).getParent());
            }

            log("Added " + files.size() + " text files for combining");
        }
    }

    
    private void clearFileList() {
        fileListView.getItems().clear();
        log("File list cleared");
    }
    
    private void removeSelectedFile() {
        File selected = fileListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            fileListView.getItems().remove(selected);
            log("Removed file: " + selected.getName());
        }
    }
    
    private void chooseOutputLocation() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choose Output File Location");
        fileChooser.setInitialFileName("combined_output.txt");
        
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Text Files", "*.txt"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            outputPathField.setText(file.getAbsolutePath());
        }
    }
    
    private void combineFiles() {
        List<File> files = new ArrayList<>(fileListView.getItems());
        String outputPath = outputPathField.getText();
        
        // Validation
        if (files.isEmpty()) {
            showError("Please select at least one text file to combine");
            return;
        }
        
        if (outputPath == null || outputPath.isEmpty()) {
            showError("Please choose an output location");
            return;
        }
        
        File outputFile = new File(outputPath);
        if (outputFile.exists()) {
            // Ask for confirmation to overwrite
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("File Exists");
            alert.setHeaderText("Output file already exists");
            alert.setContentText("Do you want to overwrite " + outputFile.getName() + "?");
            
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        
        // Perform the actual file combining
        performFileCombining(files, outputFile);
    }
    
    private void performFileCombining(List<File> inputFiles, File outputFile) {
        try {
            log("Starting file combination: " + inputFiles.size() + " files -> " + outputFile.getName());
            
            long totalBytes = 0;
            int fileCount = 0;
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {
                
                for (File inputFile : inputFiles) {
                    if (!inputFile.exists()) {
                        log("⚠️ File not found, skipping: " + inputFile.getName());
                        continue;
                    }
                    
                    log("Processing file: " + inputFile.getName());
                    
                    // Add separator between files (except for the first one)
                    if (fileCount > 0) {
                        writer.newLine();
                        writer.write("--- File: " + inputFile.getName() + " ---");
                        writer.newLine();
                        writer.newLine();
                    }
                    
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            new FileInputStream(inputFile), StandardCharsets.UTF_8))) {
                        
                        String line;
                        while ((line = reader.readLine()) != null) {
                            writer.write(line);
                            writer.newLine();
                            totalBytes += line.getBytes(StandardCharsets.UTF_8).length + 1; // +1 for newline
                        }
                        
                    } catch (IOException e) {
                        log("❌ Error reading file: " + inputFile.getName() + " - " + e.getMessage());
                        continue;
                    }
                    
                    fileCount++;
                }
            }
            
            String resultMessage = String.format(
                "Successfully combined %d files into %s (%.2f KB)",
                fileCount, outputFile.getName(), totalBytes / 1024.0
            );
            
            log("✅ " + resultMessage);
            showInfo("File Combination Complete", resultMessage);
            
        } catch (IOException e) {
            String errorMessage = "File combining failed: " + e.getMessage();
            LOGGER.error(errorMessage, e);
            log("❌ " + errorMessage);
            showError(errorMessage);
        }
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("File Combination Error");
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
            logger.accept("[FileCombiner] " + message);
        }
        LOGGER.info(message);
    }
}