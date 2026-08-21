/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.constants.AppConstants;
import audiomanager.constants.PreferenceKeys;
import audiomanager.core.BatchProcessor;
import audiomanager.core.DependencyManager;
import audiomanager.core.LicenseManager;
import audiomanager.model.BatchFileItem;
import audiomanager.model.ProcessingStatus;
import audiomanager.plugins.AudioSplitterTool;
import audiomanager.plugins.FileCombinerTool;
import audiomanager.util.PreferenceManager;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Panel for file selection and batch queue management.
 */
public class FileSelectionPanel implements BatchProcessor.FileCompletionCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSelectionPanel.class);

    // ===== Dependencies =====
    private final AppState appState = AppState.getInstance();
    private final ObservableList<BatchFileItem> batchFiles;
    private final PreferenceManager prefManager;
    private final Consumer<String> log;
    private final DependencyManager dependencyManager = new DependencyManager();

    // ===== UI Components =====
    private final VBox root;
    private TextField filePathField;
    private Label fileSizeLabel;
    private Label fileDurationLabel;
    private Button addFileButton;
    private Button browseButton;
    private Button clearQueueButton;
    private Button playButton;
    private WaveformView waveformView;
    private TableView<BatchFileItem> batchQueueTableView;

    // ===== Queue Status Labels - FINAL and BOUND =====
    private final Label queueTotalLabel;
    private final Label queueDurationLabel;
    private final Label completedCountLabel;
    private final Label failedCountLabel;
    private final Label pendingCountLabel;
    private final Label batchStatusLabel;

    // ===== State =====
    private File selectedFile;
    private String outputDirectory;
    private List<File> pendingFiles = new ArrayList<>();
    private boolean isProcessing = false;
    private final SimpleBooleanProperty isProcessingProperty = new SimpleBooleanProperty(false);
    private double initialTotalDurationSeconds = 0.0;

    // ===== Undo/Redo =====
    private final QueueCommandHistory commandHistory = new QueueCommandHistory();

    // ===== Media Player =====
    private javafx.scene.media.MediaPlayer mediaPlayer;

    // ========================================================================
    //  Construction
    // ========================================================================

    public FileSelectionPanel(ObservableList<BatchFileItem> batchFiles,
                              PreferenceManager prefManager,
                              AudioSplitterTool audioSplitter,
                              FileCombinerTool fileCombiner,
                              Consumer<String> logger) {
        this.batchFiles = batchFiles;
        this.prefManager = prefManager;
        this.log = logger;
        this.outputDirectory = prefManager.getOutputDirectory();

        // Initialize labels with binding
        this.queueTotalLabel = new Label();
        this.queueTotalLabel.textProperty().bind(appState.totalFilesProperty().asString());
        setStyled(queueTotalLabel, "-fx-font-size: 12px;");

        this.queueDurationLabel = new Label();
        this.queueDurationLabel.textProperty().bind(appState.totalDurationProperty());
        setStyled(queueDurationLabel, "-fx-font-size: 12px;");

        this.completedCountLabel = new Label();
        this.completedCountLabel.textProperty().bind(appState.completedFilesProperty().asString());
        setStyled(completedCountLabel, "-fx-font-size: 12px; -fx-text-fill: #2e7d32;");

        this.failedCountLabel = new Label();
        this.failedCountLabel.textProperty().bind(appState.failedFilesProperty().asString());
        setStyled(failedCountLabel, "-fx-font-size: 12px;");
        failedCountLabel.getStyleClass().add("status-negative");

        this.pendingCountLabel = new Label();
        this.pendingCountLabel.textProperty().bind(appState.pendingFilesProperty().asString());
        setStyled(pendingCountLabel, "-fx-font-size: 12px;");
        pendingCountLabel.getStyleClass().add("status-accent");

        this.batchStatusLabel = new Label("📁 Queue: 0 files");
        setStyled(batchStatusLabel, "-fx-text-fill: #666; -fx-font-size: 11px;");

        // Initialize UI
        this.root = createUI(audioSplitter, fileCombiner);

        // Setup bindings
        setupBindings();

        // Initialize status
        updateBatchQueueTotals();
        updateBatchStatus(batchFiles);
    }

    // ========================================================================
    //  UI Creation
    // ========================================================================

    private VBox createUI(AudioSplitterTool audioSplitter, FileCombinerTool fileCombiner) {
        VBox container = new VBox(10);
        container.setPadding(new Insets(0, 0, 15, 0));

        Label titleLabel = new Label("🎵 Audio File Selection");
        setStyled(titleLabel, "-fx-font-weight: bold; -fx-font-size: 14px;");
        titleLabel.getStyleClass().add("panel-heading");

        VBox fileSelectionRow = createFileSelectionRow();

        fileSizeLabel = new Label();
        setStyled(fileSizeLabel, "-fx-text-fill: #666; -fx-font-size: 11px;");

        // Tools section
        TitledPane toolsPane = createToolsPaneWithScroll(audioSplitter, fileCombiner);
        toolsPane.setText("🛠️ Audio Tools");
        toolsPane.setCollapsible(true);
        toolsPane.setExpanded(false);

        VBox toolsBox = new VBox(10);
        toolsBox.setPadding(new Insets(10));
        toolsBox.getChildren().addAll(
            audioSplitter.createUI(),
            new Separator(),
            fileCombiner.createUI()
        );
        toolsPane.setContent(toolsBox);

        // Batch queue - using pre-created labels
        VBox batchSection = createBatchQueueSection();

        container.getChildren().addAll(titleLabel, fileSelectionRow, fileSizeLabel, toolsPane, batchSection);
        return container;
    }

    private VBox createFileSelectionRow() {
        HBox firstRow = new HBox(10);
        firstRow.setAlignment(Pos.CENTER_LEFT);

        filePathField = new TextField();
        filePathField.setPromptText("🎵 Select audio file...");
        filePathField.setEditable(false);
        setStyled(filePathField, "-fx-border-color: #bdc3c7; -fx-border-radius: 4;");
        filePathField.getStyleClass().add("theme-fix-surface-alt");
        HBox.setHgrow(filePathField, Priority.ALWAYS);

        browseButton = new Button("📁 Browse...");
        browseButton.setOnAction(e -> selectAudioFiles());
        setStyled(browseButton, "-fx-font-weight: bold; -fx-background-radius: 4;");
        browseButton.getStyleClass().add("action-btn-browse");
        browseButton.setPrefWidth(120);

        addFileButton = new Button("➕ Add to Queue");
        addFileButton.setOnAction(e -> addFilesToBatch());
        addFileButton.setDisable(true);
        setStyled(addFileButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
        addFileButton.setPrefWidth(120);

        playButton = new Button("▶️ Play");
        playButton.setOnAction(e -> playSelectedFile());
        playButton.setDisable(true);
        setStyled(playButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
        playButton.setPrefWidth(100);

        firstRow.getChildren().addAll(filePathField, browseButton, addFileButton, playButton);

        // Second row: Clear Queue and Output Directory
        HBox secondRow = new HBox(10);
        secondRow.setAlignment(Pos.CENTER_LEFT);

        clearQueueButton = new Button("🗑️ Clear Queue");
        clearQueueButton.setOnAction(e -> clearBatchFiles());
        clearQueueButton.setDisable(true);
        setStyled(clearQueueButton, "-fx-background-radius: 4;");
        clearQueueButton.getStyleClass().add("action-btn-clear-queue-disabled");
        clearQueueButton.setPrefWidth(120);

        Button outputDirButton = new Button("📂 Output Directory...");
        outputDirButton.setOnAction(e -> selectOutputDirectory());
        outputDirButton.setTooltip(new Tooltip("Current: " + outputDirectory));
        setStyled(outputDirButton, "-fx-background-radius: 4;");
        outputDirButton.getStyleClass().add("action-btn-output-dir");
        outputDirButton.setPrefWidth(140);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        secondRow.getChildren().addAll(spacer, clearQueueButton, outputDirButton);

        // Waveform preview
        waveformView = new WaveformView(dependencyManager, 600, 50);
        HBox waveformRow = new HBox(waveformView);
        waveformRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(waveformView, Priority.ALWAYS);

        VBox fileBox = new VBox(8);
        fileBox.getChildren().addAll(firstRow, secondRow, waveformRow);
        return fileBox;
    }

    private TitledPane createToolsPaneWithScroll(AudioSplitterTool audioSplitter, FileCombinerTool fileCombiner) {
        TitledPane pane = new TitledPane();
        pane.setText("🛠️ Audio Tools");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        VBox toolsContent = new VBox(10);
        toolsContent.setPadding(new Insets(10));

        VBox splitterSection = new VBox(5);
        Label splitterLabel = new Label("Audio Splitter");
        setStyled(splitterLabel, "-fx-font-weight: bold;");
        splitterLabel.getStyleClass().add("panel-heading");
        Node splitterUI = audioSplitter.createUI();
        splitterSection.getChildren().addAll(splitterLabel, splitterUI);

        VBox combinerSection = new VBox(5);
        Label combinerLabel = new Label("File Combiner");
        setStyled(combinerLabel, "-fx-font-weight: bold;");
        combinerLabel.getStyleClass().add("panel-heading");
        Node combinerUI = fileCombiner.createUI();
        combinerSection.getChildren().addAll(combinerLabel, combinerUI);

        toolsContent.getChildren().addAll(splitterSection, new Separator(), combinerSection);

        ScrollPane scrollPane = new ScrollPane(toolsContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(250);
        scrollPane.setMaxHeight(300);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        pane.setContent(scrollPane);
        return pane;
    }

    // ========================================================================
    //  Batch Queue Section - Using Pre-Created Bound Labels
    // ========================================================================

    private VBox createBatchQueueSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(15, -5, 15, -5));
        setStyled(section, "-fx-border-color: #e0e0e0; -fx-border-width: 1; -fx-border-radius: 5;");

        Label title = new Label("📊 Batch Queue Status");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");
        title.getStyleClass().add("panel-heading");

        GridPane statusGrid = new GridPane();
        statusGrid.setHgap(15);
        statusGrid.setVgap(8);
        statusGrid.setPadding(new Insets(10, 0, 10, 0));

        // USE THE PRE-CREATED LABELS (they are bound to AppState)
        Label totalTitle = new Label("Total Files:");
        setStyled(totalTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        // queueTotalLabel is already bound - just add it
        setStyled(queueTotalLabel, "-fx-font-size: 12px;");

        Label durationTitle = new Label("Total Duration:");
        setStyled(durationTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        // queueDurationLabel is already bound
        setStyled(queueDurationLabel, "-fx-font-size: 12px;");

        Label completedTitle = new Label("Completed:");
        setStyled(completedTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        // completedCountLabel is already bound
        setStyled(completedCountLabel, "-fx-font-size: 12px; -fx-text-fill: #2e7d32;");

        Label failedTitle = new Label("Failed:");
        setStyled(failedTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        // failedCountLabel is already bound
        setStyled(failedCountLabel, "-fx-font-size: 12px;");
        failedCountLabel.getStyleClass().add("status-negative");

        Label pendingTitle = new Label("Pending:");
        setStyled(pendingTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        // pendingCountLabel is already bound
        setStyled(pendingCountLabel, "-fx-font-size: 12px;");
        pendingCountLabel.getStyleClass().add("status-accent");

        // Add to grid - using the bound labels
        statusGrid.add(totalTitle, 0, 0);
        statusGrid.add(queueTotalLabel, 1, 0);
        statusGrid.add(durationTitle, 2, 0);
        statusGrid.add(queueDurationLabel, 3, 0);
        statusGrid.add(completedTitle, 0, 1);
        statusGrid.add(completedCountLabel, 1, 1);
        statusGrid.add(failedTitle, 2, 1);
        statusGrid.add(failedCountLabel, 3, 1);
        statusGrid.add(pendingTitle, 0, 2);
        statusGrid.add(pendingCountLabel, 1, 2);

        // Table
        TableView<BatchFileItem> fileTableView = createFileTableView();
        this.batchQueueTableView = fileTableView;
        fileTableView.setItems(batchFiles);
        fileTableView.setPrefHeight(200);
        VBox.setVgrow(fileTableView, Priority.ALWAYS);

        // Action buttons
        HBox actionBox = new HBox(10);
        actionBox.setAlignment(Pos.CENTER_LEFT);

        Button clearCompletedButton = new Button("Clear Completed");
        clearCompletedButton.setOnAction(e -> clearCompletedFiles());

        Button removeSelectedButton = new Button("Remove Selected");
        removeSelectedButton.setOnAction(e -> removeSelectedFilesFromTableView(fileTableView));

        Button clearAllButton = new Button("Clear All");
        setStyled(clearAllButton, "");
        clearAllButton.getStyleClass().add("status-negative");
        clearAllButton.setOnAction(e -> clearBatchFiles());

        actionBox.getChildren().addAll(clearCompletedButton, removeSelectedButton, clearAllButton);

        section.getChildren().addAll(title, statusGrid, fileTableView, actionBox);
        
        // Add batch status label below the table
        section.getChildren().add(batchStatusLabel);

        return section;
    }

    // ========================================================================
    //  Table View
    // ========================================================================

    private TableView<BatchFileItem> createFileTableView() {
        TableView<BatchFileItem> tableView = new TableView<>();
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        setupContextMenuForTableView(tableView);
        setupDragAndDropForTableView(tableView);
        setupKeyboardShortcutsForTableView(tableView);

        // File name column
        TableColumn<BatchFileItem, String> nameColumn = new TableColumn<>("File Name");
        nameColumn.setCellValueFactory(cellData -> cellData.getValue().displayNameProperty());
        nameColumn.setPrefWidth(250);

        // Status column
        TableColumn<BatchFileItem, String> statusColumn = new TableColumn<>("Status");
        statusColumn.setCellValueFactory(cellData -> {
            String status = cellData.getValue().getStatus();
            return new SimpleStringProperty(status != null ? status : "PENDING");
        });
        statusColumn.setPrefWidth(100);
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    switch (item) {
                        case "COMPLETED" -> setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                        case "PROCESSING" -> setStyle("-fx-text-fill: #2196F3; -fx-font-weight: bold;");
                        case "FAILED" -> setStyle("-fx-text-fill: #F44336; -fx-font-weight: bold;");
                        default -> setStyle("-fx-text-fill: #666;");
                    }
                }
            }
        });

        // Size column
        TableColumn<BatchFileItem, String> sizeColumn = new TableColumn<>("Size");
        sizeColumn.setCellValueFactory(cellData -> 
            new SimpleStringProperty(formatFileSize(cellData.getValue().getFile().length())));
        sizeColumn.setPrefWidth(80);

        // Duration column
        TableColumn<BatchFileItem, String> durationColumn = new TableColumn<>("Duration");
        durationColumn.setCellValueFactory(cellData -> 
            new SimpleStringProperty(formatDuration((long) cellData.getValue().getTotalAudioDurationSeconds())));
        durationColumn.setPrefWidth(90);

        // Progress column
        TableColumn<BatchFileItem, Double> progressColumn = new TableColumn<>("Progress");
        progressColumn.setCellValueFactory(cellData -> 
            new SimpleObjectProperty<>(cellData.getValue().getProgress()));
        progressColumn.setPrefWidth(120);
        progressColumn.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar progressBar = new ProgressBar();
            private final Label percentLabel = new Label();
            private final HBox container = new HBox(5);

            {
                progressBar.setPrefWidth(80);
                progressBar.setPrefHeight(12);
                percentLabel.setPrefWidth(35);
                container.getChildren().addAll(progressBar, percentLabel);
                container.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Double progress, boolean empty) {
                super.updateItem(progress, empty);
                if (empty || progress == null || progress < 0) {
                    setGraphic(null);
                    setText(null);
                } else {
                    progressBar.setProgress(progress);
                    percentLabel.setText(String.format("%.0f%%", progress * 100));
                    setGraphic(container);
                    setText(null);
                }
            }
        });

        tableView.getColumns().addAll(nameColumn, statusColumn, sizeColumn, durationColumn, progressColumn);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        return tableView;
    }

    // ========================================================================
    //  File Operations
    // ========================================================================

    private void selectAudioFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Audio Files");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Audio", "*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a", "*.wma", "*.aac", "*.opus", "*.alac", "*.aiff", "*.amr", "*.ac3"),
            new FileChooser.ExtensionFilter("MP3 Files", "*.mp3"),
            new FileChooser.ExtensionFilter("WAV Files", "*.wav"),
            new FileChooser.ExtensionFilter("FLAC Files", "*.flac")
        );

        File initialDir = new File(prefManager.getLastFileAddLocation());
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        }

        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(null);

        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            selectedFile = selectedFiles.get(0);

            if (selectedFiles.size() == 1) {
                filePathField.setText(selectedFile.getAbsolutePath());
                updateFileInfoLabels(selectedFile);
                playButton.setDisable(false);
                setStyled(playButton, "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-background-radius: 4;");
                if (waveformView != null) waveformView.loadAndRender(selectedFile);
            } else {
                filePathField.setText(selectedFiles.size() + " files selected");
                fileSizeLabel.setText("📏 Multiple files selected");
                fileDurationLabel.setText("");
                stopPlayback();
                playButton.setDisable(true);
                setStyled(playButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
                if (waveformView != null) waveformView.clear();
            }

            prefManager.setLastFileAddLocation(selectedFiles.get(0).getParent());
            prefManager.setLastFileDirectory(selectedFiles.get(0).getParent());

            this.pendingFiles = new ArrayList<>(selectedFiles);
            updateAddToQueueButtonState(true);
        } else {
            updateAddToQueueButtonState(false);
            stopPlayback();
            playButton.setDisable(true);
            setStyled(playButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
            if (waveformView != null) waveformView.clear();
        }
    }

    private void addFilesToBatch() {
        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.accept("❌ ERROR: No files selected");
            return;
        }

        LicenseManager license = LicenseManager.getInstance();
        int added = 0;
        int skipped = 0;
        List<BatchFileItem> addedItems = new ArrayList<>();

        for (File file : pendingFiles) {
            // Check license limits
            if (!license.canProcessFile(file.length())) {
                long sizeMB = file.length() / (1024 * 1024);
                log.accept("⚠️ Skipped (exceeds " + license.getMaxFileSizeMB() + "MB limit): " + file.getName());
                skipped++;
                continue;
            }

            if (!license.canAddToBatch(batchFiles.size())) {
                log.accept("⚠️ Skipped (batch limit reached): " + file.getName());
                skipped++;
                continue;
            }

            // Check duplicates
            boolean duplicate = batchFiles.stream().anyMatch(item -> item.getFile().equals(file));
            if (duplicate) {
                log.accept("⚠️ Skipped (duplicate): " + file.getName());
                skipped++;
                continue;
            }

            BatchFileItem item = new BatchFileItem(file);
            double duration = getAudioDuration(file);
            if (duration > 0) {
                item.setTotalAudioDurationSeconds(duration);
            }

            batchFiles.add(item);
            addedItems.add(item);
            added++;
        }

        if (added > 0) {
            List<BatchFileItem> committedItems = new ArrayList<>(addedItems);
            pushCommand(new QueueCommand() {
                @Override public void undo() { batchFiles.removeAll(committedItems); }
                @Override public void redo() { batchFiles.addAll(committedItems); }
                @Override public String description() {
                    return "add " + committedItems.size() + " file(s) to queue";
                }
            });
            log.accept("➕ Added " + added + " file(s) to queue" + (skipped > 0 ? " (" + skipped + " skipped)" : ""));
            updateBatchStatus(batchFiles);
            updateBatchQueueTotals();
        }

        pendingFiles.clear();
        selectedFile = null;
        filePathField.clear();
        fileSizeLabel.setText("");
        fileDurationLabel.setText("");
        updateAddToQueueButtonState(false);
        stopPlayback();
        playButton.setDisable(true);
        setStyled(playButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
    }

    public void addFile(File file) {
        LicenseManager license = LicenseManager.getInstance();

        if (!license.canProcessFile(file.length())) {
            long sizeMB = file.length() / (1024 * 1024);
            showErrorDialog("File Too Large",
                String.format("File size (%dMB) exceeds the %dMB limit.\n\nFile: %s",
                    sizeMB, license.getMaxFileSizeMB(), file.getName()));
            return;
        }

        if (!license.canAddToBatch(batchFiles.size())) {
            showErrorDialog("Batch Limit Reached",
                "The Free version only supports one file at a time.\n\n" +
                "File: " + file.getName() + "\nQueue: " + batchFiles.size() + " file(s)");
            return;
        }

        BatchFileItem item = new BatchFileItem(file);
        batchFiles.add(item);
        probeAndSetDuration(item);
        updateBatchStatus(batchFiles);
        log.accept("📄 Added file: " + file.getName());
    }

    private void setupBindings() {
        // Bind clear queue button disable property
        clearQueueButton.disableProperty().bind(
            Bindings.isEmpty(batchFiles).or(isProcessingProperty)
        );

        // Update button style when disabled state changes
        clearQueueButton.disableProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                clearQueueButton.getStyleClass().setAll("action-btn-clear-queue-disabled");
            } else {
                clearQueueButton.getStyleClass().setAll("action-btn-clear-queue-active");
            }
        });
    }

    // ========================================================================
    //  Queue Management
    // ========================================================================

    private void clearBatchFiles() {
        if (batchFiles.isEmpty()) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Clear Queue");
        alert.setHeaderText("Clear all files from the batch queue?");
        alert.setContentText("This action cannot be undone.");
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                batchFiles.clear();
                log.accept("🗑️ Batch queue cleared");
                updateBatchStatus(batchFiles);
                updateBatchQueueTotals();
            }
        });
    }

    private void clearCompletedFiles() {
        int removed = 0;
        Iterator<BatchFileItem> iterator = batchFiles.iterator();
        while (iterator.hasNext()) {
            BatchFileItem item = iterator.next();
            if ("COMPLETED".equals(item.getStatus())) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.accept("🧹 Removed " + removed + " completed files from queue");
            updateBatchQueueTotals();
        }
    }

    public void removeItemFromBatchQueue(BatchFileItem item) {
        if (item == null) return;
        Platform.runLater(() -> {
            boolean autoRemove = prefManager.getBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, false);
            if (autoRemove) {
                boolean removed = batchFiles.remove(item);
                if (removed) {
                    LOGGER.debug("Removed completed file from UI queue: {}", item.getFileName());
                }
            }
            updateBatchQueueTotals();
        });
    }

    // ========================================================================
    //  Batch Status Updates
    // ========================================================================

    public void updateBatchQueueTotals() {
        Platform.runLater(() -> {
            int totalFiles = batchFiles.size();
            int completed = 0;
            int failed = 0;
            double totalDurationSeconds = 0.0;

            for (BatchFileItem item : batchFiles) {
                totalDurationSeconds += item.getTotalAudioDurationSeconds();
                if ("COMPLETED".equals(item.getStatus())) completed++;
                else if ("FAILED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus())) failed++;
            }

            // Update AppState - bound labels update automatically
            int pending = Math.max(0, totalFiles - completed - failed);
            appState.updateBatchStats(totalFiles, completed, failed, pending);

            LOGGER.debug("Batch queue totals updated: {} files, duration: {}", 
                totalFiles, queueDurationLabel.getText());
        });
    }

    public void updateBatchStatus(ObservableList<BatchFileItem> items) {
        Platform.runLater(() -> {
            int totalFiles = 0;
            int completed = 0;
            int failed = 0;
            double totalDurationSeconds = 0;

            for (BatchFileItem item : items) {
                totalFiles++;
                totalDurationSeconds += item.getTotalAudioDurationSeconds();
                if ("COMPLETED".equals(item.getStatus())) completed++;
                else if ("FAILED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus())) failed++;
            }

            // Update AppState - bound labels update automatically
            int pending = Math.max(0, totalFiles - completed - failed);
            appState.updateBatchStats(totalFiles, completed, failed, pending);

            // Update non-bound label
            batchStatusLabel.setText("📁 Queue: " + totalFiles + " files");

            if (batchQueueTableView != null) {
                batchQueueTableView.refresh();
            }
        });
    }

    public void updateBatchStatus(ObservableList<BatchFileItem> items,
                                   int completed, int failed, int pending, int total) {
        Platform.runLater(() -> {
            appState.updateBatchStats(total, completed, failed, pending);
            batchStatusLabel.setText("📁 Queue: " + pending + " pending / " + total + " total");

            if (batchQueueTableView != null) {
                batchQueueTableView.refresh();
            }
        });
    }

    public void updateCompletedFailedCounts(int completed, int failed) {
        // Handled by AppState binding - keep for compatibility
    }

    // ===== initializeBatchStatus REMOVED =====
    // The labels are bound to AppState, so this method is no longer needed.
    // Use appState.resetForNewBatch() or appState.updateBatchStats() instead.

    public void resetBatchStatus() {
        initialTotalDurationSeconds = 0.0;
        for (BatchFileItem item : batchFiles) {
            double duration = item.getTotalAudioDurationSeconds();
            if (duration <= 0) {
                duration = estimateDurationFromFileSize(item.getFile());
            }
            initialTotalDurationSeconds += duration;
        }
        isProcessing = true;
        updateBatchStatus(batchFiles);
        log.accept("📊 Batch initialized: " + batchFiles.size() + " files, total duration: " +
            formatDuration((long) initialTotalDurationSeconds));
    }

    // ========================================================================
    //  File Info Helpers
    // ========================================================================

    private void updateFileInfoLabels(File file) {
        long size = file.length();
        double sizeMB = size / (1024.0 * 1024.0);

        if (size > AppConstants.MAX_FILE_SIZE) {
            fileSizeLabel.setText(String.format("❌ File size: %.2f MB (EXCEEDS %d MB limit)",
                sizeMB, AppConstants.MAX_FILE_SIZE_MB));
            setStyled(fileSizeLabel, "-fx-text-fill: red; -fx-font-size: 11px;");
            addFileButton.setDisable(true);
            fileDurationLabel.setText("");
            log.accept("⚠️ WARNING: File exceeds maximum size limit");
        } else {
            fileSizeLabel.setText(String.format("📏 File size: %.2f MB", sizeMB));
            setStyled(fileSizeLabel, "-fx-text-fill: green; -fx-font-size: 11px;");
            addFileButton.setDisable(false);

            long duration = (long) getAudioDuration(file);
            if (duration > 0) {
                fileDurationLabel.setText("⏱️ Duration: " + formatDuration(duration));
                setStyled(fileDurationLabel, "-fx-font-size: 11px;");
                fileDurationLabel.getStyleClass().add("status-accent");
            } else {
                fileDurationLabel.setText("⏱️ Duration: Unknown");
                setStyled(fileDurationLabel, "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
            }
        }
    }

    private void updateAddToQueueButtonState(boolean hasFiles) {
        addFileButton.setDisable(!hasFiles);
        if (hasFiles) {
            setStyled(addFileButton, "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        } else {
            setStyled(addFileButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
        }
    }

    public void probeAndSetDuration(BatchFileItem item) {
        double duration = getAudioDuration(item.getFile());
        if (duration > 0) {
            item.setTotalAudioDurationSeconds(duration);
        }
    }

    private double getAudioDuration(File file) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                dependencyManager.getFFprobePath(), "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.getAbsolutePath()
            );
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return Double.parseDouble(output);
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            LOGGER.debug("Could not get audio duration for: {}", file.getName(), e);
        }
        return 0.0;
    }

    private double estimateDurationFromFileSize(File file) {
        long fileSizeMB = file.length() / (1024 * 1024);
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".wav")) return (fileSizeMB / 10.0) * 60;
        else if (fileName.endsWith(".flac")) return (fileSizeMB / 5.0) * 60;
        else return (fileSizeMB / 1.0) * 60;
    }

    // ========================================================================
    //  Output Directory
    // ========================================================================

    private void selectOutputDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory");

        File initialDir = new File(outputDirectory);
        if (initialDir.exists() && initialDir.isDirectory()) {
            chooser.setInitialDirectory(initialDir);
        }

        File dir = chooser.showDialog(null);
        if (dir != null) {
            outputDirectory = dir.getAbsolutePath();
            prefManager.setOutputDirectory(outputDirectory);
            log.accept("📂 Output directory: " + outputDirectory);
        }
    }

    public void updateOutputDirectory(String path) {
        this.outputDirectory = path;
    }

    // ========================================================================
    //  Media Playback
    // ========================================================================

    private void playSelectedFile() {
        if (selectedFile == null) return;
        if (mediaPlayer != null) {
            stopPlayback();
            return;
        }

        try {
            javafx.scene.media.Media media = new javafx.scene.media.Media(selectedFile.toURI().toString());
            mediaPlayer = new javafx.scene.media.MediaPlayer(media);
            mediaPlayer.setOnError(() -> {
                log.accept("❌ Cannot play audio: " + mediaPlayer.getError().getMessage());
                stopPlayback();
            });
            mediaPlayer.setOnEndOfMedia(this::stopPlayback);
            mediaPlayer.play();
            playButton.setText("⏹️ Stop");
        } catch (Exception e) {
            log.accept("❌ Cannot play audio: " + e.getMessage());
            mediaPlayer = null;
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.dispose();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (playButton != null) {
            playButton.setText("▶️ Play");
        }
    }

    // ========================================================================
    //  Undo/Redo
    // ========================================================================

    private void pushCommand(QueueCommand command) {
        commandHistory.push(command);
    }

    public boolean undo() {
        QueueCommand command = commandHistory.undo();
        if (command == null) return false;
        log.accept("↩️ Undo: " + command.description());
        updateBatchQueueTotals();
        return true;
    }

    public boolean redo() {
        QueueCommand command = commandHistory.redo();
        if (command == null) return false;
        log.accept("↪️ Redo: " + command.description());
        updateBatchQueueTotals();
        return true;
    }

    public boolean canUndo() { return commandHistory.canUndo(); }
    public boolean canRedo() { return commandHistory.canRedo(); }

    // ========================================================================
    //  Table View Helpers
    // ========================================================================

    private void setupContextMenuForTableView(TableView<BatchFileItem> tableView) {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem removeSelectedItem = new MenuItem("Remove Selected");
        removeSelectedItem.setOnAction(e -> removeSelectedFilesFromTableView(tableView));

        MenuItem removeAllItem = new MenuItem("Remove All");
        removeAllItem.setOnAction(e -> clearBatchFiles());

        MenuItem moveToTopItem = new MenuItem("Move to Top");
        moveToTopItem.setOnAction(e -> moveSelectedToTopInTableView(tableView));

        MenuItem moveToBottomItem = new MenuItem("Move to Bottom");
        moveToBottomItem.setOnAction(e -> moveSelectedToBottomInTableView(tableView));

        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setOnAction(e -> tableView.getSelectionModel().selectAll());

        Menu priorityMenu = new Menu("Set Priority");
        MenuItem highPriorityItem = new MenuItem("🔴 High");
        highPriorityItem.setOnAction(e -> setSelectedPriority(tableView, BatchFileItem.Priority.HIGH));
        MenuItem normalPriorityItem = new MenuItem("⚪ Normal");
        normalPriorityItem.setOnAction(e -> setSelectedPriority(tableView, BatchFileItem.Priority.NORMAL));
        MenuItem lowPriorityItem = new MenuItem("🔵 Low");
        lowPriorityItem.setOnAction(e -> setSelectedPriority(tableView, BatchFileItem.Priority.LOW));
        priorityMenu.getItems().addAll(highPriorityItem, normalPriorityItem, lowPriorityItem);

        MenuItem renameItem = new MenuItem("✏️ Rename...");
        renameItem.setOnAction(e -> renameSelectedInTableView(tableView));

        MenuItem editNotesItem = new MenuItem("📝 Edit Notes...");
        editNotesItem.setOnAction(e -> editNotesForSelectedInTableView(tableView));

        contextMenu.getItems().addAll(
            removeSelectedItem, removeAllItem, new SeparatorMenuItem(),
            moveToTopItem, moveToBottomItem, new SeparatorMenuItem(),
            priorityMenu, new SeparatorMenuItem(),
            renameItem, editNotesItem, new SeparatorMenuItem(),
            selectAllItem
        );

        tableView.setContextMenu(contextMenu);
    }

    private void setupDragAndDropForTableView(TableView<BatchFileItem> tableView) {
        tableView.setRowFactory(tv -> {
            TableRow<BatchFileItem> row = new TableRow<>();

            row.setOnDragDetected(event -> {
                if (!row.isEmpty()) {
                    Dragboard dragboard = row.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(Integer.toString(row.getIndex()));
                    dragboard.setContent(content);
                    event.consume();
                }
            });

            row.setOnDragOver(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString() && !row.isEmpty()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                    event.consume();
                }
            });

            row.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString() && !row.isEmpty()) {
                    int draggedIndex = Integer.parseInt(db.getString());
                    int dropIndex = row.getIndex();

                    if (draggedIndex != dropIndex) {
                        BatchFileItem item = tableView.getItems().remove(draggedIndex);
                        tableView.getItems().add(dropIndex, item);
                        tableView.getSelectionModel().select(dropIndex);
                        log.accept("↕️ Reordered file in queue");
                    }
                    event.setDropCompleted(true);
                    event.consume();
                }
            });

            return row;
        });
    }

    private void setupKeyboardShortcutsForTableView(TableView<BatchFileItem> tableView) {
        tableView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE) {
                removeSelectedFilesFromTableView(tableView);
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.A) {
                tableView.getSelectionModel().selectAll();
                event.consume();
            } else if (event.isControlDown() && !event.isShiftDown() && event.getCode() == KeyCode.Z) {
                undo();
                event.consume();
            } else if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.Z) {
                redo();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.Y) {
                redo();
                event.consume();
            }
        });
    }

    private void removeSelectedFilesFromTableView(TableView<BatchFileItem> tableView) {
        ObservableList<BatchFileItem> selectedItems = tableView.getSelectionModel().getSelectedItems();
        if (!selectedItems.isEmpty()) {
            List<BatchFileItem> removed = new ArrayList<>(selectedItems);
            List<Integer> originalIndices = new ArrayList<>();
            for (BatchFileItem item : removed) {
                originalIndices.add(batchFiles.indexOf(item));
            }

            batchFiles.removeAll(removed);
            pushCommand(new QueueCommand() {
                @Override public void undo() {
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < removed.size(); i++) order.add(i);
                    order.sort((a, b) -> Integer.compare(originalIndices.get(a), originalIndices.get(b)));
                    for (int i : order) {
                        int idx = Math.min(originalIndices.get(i), batchFiles.size());
                        batchFiles.add(idx, removed.get(i));
                    }
                }
                @Override public void redo() { batchFiles.removeAll(removed); }
                @Override public String description() { return "remove " + removed.size() + " file(s)"; }
            });
            log.accept("🗑️ Removed " + selectedItems.size() + " file(s) from queue");
            updateBatchQueueTotals();
        }
    }

    private void moveSelectedToTopInTableView(TableView<BatchFileItem> tableView) {
        int selectedIndex = tableView.getSelectionModel().getSelectedIndex();
        if (selectedIndex > 0) {
            final int originalIndex = selectedIndex;
            BatchFileItem item = batchFiles.remove(selectedIndex);
            batchFiles.add(0, item);
            tableView.getSelectionModel().select(0);
            pushCommand(new QueueCommand() {
                @Override public void undo() {
                    batchFiles.remove(item);
                    batchFiles.add(Math.min(originalIndex, batchFiles.size()), item);
                }
                @Override public void redo() {
                    batchFiles.remove(item);
                    batchFiles.add(0, item);
                }
                @Override public String description() { return "move " + item.getFileName() + " to top"; }
            });
            log.accept("⬆️ Moved file to top");
        }
    }

    private void moveSelectedToBottomInTableView(TableView<BatchFileItem> tableView) {
        int selectedIndex = tableView.getSelectionModel().getSelectedIndex();
        if (selectedIndex < batchFiles.size() - 1 && selectedIndex >= 0) {
            final int originalIndex = selectedIndex;
            BatchFileItem item = batchFiles.remove(selectedIndex);
            batchFiles.add(item);
            tableView.getSelectionModel().select(batchFiles.size() - 1);
            pushCommand(new QueueCommand() {
                @Override public void undo() {
                    batchFiles.remove(item);
                    batchFiles.add(Math.min(originalIndex, batchFiles.size()), item);
                }
                @Override public void redo() {
                    batchFiles.remove(item);
                    batchFiles.add(item);
                }
                @Override public String description() { return "move " + item.getFileName() + " to bottom"; }
            });
            log.accept("⬇️ Moved file to bottom");
        }
    }

    private void setSelectedPriority(TableView<BatchFileItem> tableView, BatchFileItem.Priority priority) {
        List<BatchFileItem> selected = new ArrayList<>(tableView.getSelectionModel().getSelectedItems());
        for (BatchFileItem item : selected) {
            item.setPriority(priority);
        }
        if (!selected.isEmpty()) {
            log.accept("🎯 Set priority to " + priority + " for " + selected.size() + " file(s)");
            tableView.refresh();
        }
    }

    private void renameSelectedInTableView(TableView<BatchFileItem> tableView) {
        List<BatchFileItem> selected = tableView.getSelectionModel().getSelectedItems();
        if (selected.size() != 1) {
            log.accept("✏️ Select exactly one file to rename.");
            return;
        }
        BatchFileItem item = selected.get(0);

        TextInputDialog dialog = new TextInputDialog(item.getDisplayName());
        dialog.setTitle("Rename File");
        dialog.setHeaderText("Display name for \"" + item.getFileName() + "\"");
        dialog.setContentText("This only changes how the file is labeled in this app.");
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(newName -> {
            String oldName = item.getDisplayName();
            String trimmed = newName.trim();
            if (trimmed.equals(oldName)) return;

            item.setDisplayName(trimmed);
            pushCommand(new QueueCommand() {
                @Override public void undo() { item.setDisplayName(oldName); }
                @Override public void redo() { item.setDisplayName(trimmed); }
                @Override public String description() { return "rename \"" + item.getFileName() + "\""; }
            });
            log.accept("✏️ Renamed \"" + item.getFileName() + "\" to \"" + item.getDisplayName() + "\"");
        });
    }

    private void editNotesForSelectedInTableView(TableView<BatchFileItem> tableView) {
        List<BatchFileItem> selected = tableView.getSelectionModel().getSelectedItems();
        if (selected.size() != 1) {
            log.accept("📝 Select exactly one file to edit notes.");
            return;
        }
        BatchFileItem item = selected.get(0);

        TextInputDialog dialog = new TextInputDialog(item.getNotes());
        dialog.setTitle("Edit Notes");
        dialog.setHeaderText("Notes for \"" + item.getDisplayName() + "\"");
        dialog.setContentText("Visible only in this app (row tooltip).");
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(newNotes -> {
            String oldNotes = item.getNotes();
            String trimmed = newNotes.trim();
            if (trimmed.equals(oldNotes)) return;

            item.setNotes(trimmed);
            pushCommand(new QueueCommand() {
                @Override public void undo() { item.setNotes(oldNotes); }
                @Override public void redo() { item.setNotes(trimmed); }
                @Override public String description() { return "edit notes for \"" + item.getFileName() + "\""; }
            });
            log.accept("📝 Updated notes for \"" + item.getDisplayName() + "\"");
        });
    }

    // ========================================================================
    //  Styling Helper
    // ========================================================================

    private void setStyled(Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    // ========================================================================
    //  Format Helpers
    // ========================================================================

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    private String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds);
        if (minutes > 0) return String.format("%d:%02d", minutes, seconds);
        return String.format("%ds", seconds);
    }

    // ========================================================================
    //  Dialogs
    // ========================================================================

    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    // ========================================================================
    //  Getters
    // ========================================================================

    public VBox getRoot() { return root; }

    public VBox getFileSelectionControls() {
        VBox fileControls = new VBox(8);
        fileControls.setPadding(new Insets(15));
        setStyled(fileControls, "-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; -fx-background-color: white;");
        fileControls.getStyleClass().add("theme-fix-surface");

        Label titleLabel = new Label("🎵 Audio File Selection");
        setStyled(titleLabel, "-fx-font-weight: bold; -fx-font-size: 14px;");
        titleLabel.getStyleClass().add("panel-heading");

        HBox fileInputRow = new HBox(10);
        fileInputRow.setAlignment(Pos.CENTER_LEFT);

        filePathField = new TextField();
        filePathField.setPromptText("🎵 Select audio file...");
        filePathField.setEditable(false);
        setStyled(filePathField, "-fx-border-color: #bdc3c7; -fx-border-radius: 4;");
        filePathField.getStyleClass().add("theme-fix-surface-alt");
        HBox.setHgrow(filePathField, Priority.ALWAYS);

        browseButton = new Button("📁 Browse...");
        browseButton.setOnAction(e -> selectAudioFiles());
        setStyled(browseButton, "-fx-font-weight: bold; -fx-background-radius: 4;");
        browseButton.getStyleClass().add("action-btn-browse");
        browseButton.setPrefWidth(120);

        addFileButton = new Button("➕ Add to Queue");
        addFileButton.setOnAction(e -> addFilesToBatch());
        addFileButton.setDisable(true);
        setStyled(addFileButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
        addFileButton.setPrefWidth(140);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        fileInputRow.getChildren().addAll(titleLabel, spacer, browseButton, addFileButton);

        HBox fileInfoRow = new HBox(20);
        fileInfoRow.setAlignment(Pos.CENTER_LEFT);
        fileSizeLabel = new Label();
        setStyled(fileSizeLabel, "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
        fileDurationLabel = new Label();
        setStyled(fileDurationLabel, "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
        fileInfoRow.getChildren().addAll(fileSizeLabel, fileDurationLabel);

        HBox secondRow = new HBox(10);
        secondRow.setAlignment(Pos.CENTER_LEFT);
        clearQueueButton = new Button("🗑️ Clear Queue");
        clearQueueButton.setOnAction(e -> clearBatchFiles());
        setStyled(clearQueueButton, "-fx-background-radius: 4;");
        clearQueueButton.getStyleClass().add("action-btn-clear-queue-active");
        clearQueueButton.setPrefWidth(120);

        Button outputDirButton = new Button("📂 Output Directory...");
        outputDirButton.setOnAction(e -> selectOutputDirectory());
        outputDirButton.setTooltip(new Tooltip("Current: " + outputDirectory));
        setStyled(outputDirButton, "-fx-background-radius: 4;");
        outputDirButton.getStyleClass().add("action-btn-output-dir");
        outputDirButton.setPrefWidth(140);

        secondRow.getChildren().addAll(filePathField, clearQueueButton, outputDirButton);

        fileControls.getChildren().addAll(fileInputRow, secondRow, fileInfoRow);
        return fileControls;
    }

    public VBox getBatchQueueSection() {
        return createBatchQueueSection();
    }

    /**
     * Returns the file selection controls without the title label.
     * Used for embedding in a TitledPane.
     */
    public VBox getFileSelectionControlsWithoutTitle() {
        VBox controls = getFileSelectionControls();
        if (!controls.getChildren().isEmpty() && controls.getChildren().get(0) instanceof Label) {
            controls.getChildren().remove(0);
        }
        return controls;
    }

    /**
     * Returns the batch queue section without the title label.
     * Used for embedding in a TitledPane.
     */
    public VBox getBatchQueueSectionWithoutTitle() {
        VBox section = getBatchQueueSection();
        if (!section.getChildren().isEmpty() && section.getChildren().get(0) instanceof Label) {
            section.getChildren().remove(0);
        }
        return section;
    }

    public Button getClearQueueButton() { return clearQueueButton; }

    public void setProcessingState(boolean processing) {
        this.isProcessing = processing;
        if (!processing) {
            updateBatchStatus(batchFiles);
        }
    }

    public void setIsProcessing(boolean isProcessing) {
        Platform.runLater(() -> this.isProcessingProperty.set(isProcessing));
    }

    public void saveFilePreferences() {
        if (prefManager != null) {
            try {
                prefManager.setOutputDirectory(outputDirectory);
                if (outputDirectory != null && !outputDirectory.isEmpty()) {
                    prefManager.putString("output_directory", outputDirectory);
                }
                if (selectedFile != null) {
                    prefManager.setLastFileAddLocation(selectedFile.getParent());
                }
                prefManager.putInt("last_batch_file_count", batchFiles.size());
                prefManager.flush();
            } catch (Exception e) {
                LOGGER.error("Failed to save file preferences", e);
            }
        }
    }

    public void triggerBrowse() { selectAudioFiles(); }
    public void triggerOutputDirectoryChooser() { selectOutputDirectory(); }

    @Override
    public void onFileCompleted(BatchFileItem item, boolean wasSuccessful) {
        Platform.runLater(() -> {
            if (wasSuccessful) {
                log.accept("✅ File completed successfully: " + item.getFileName());
            } else {
                log.accept("❌ File failed processing: " + item.getFileName());
            }
            removeItemFromBatchQueue(item);
        });
    }
}