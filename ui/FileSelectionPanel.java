/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.constants.AppConstants;
import audiomanager.constants.PreferenceKeys;
import audiomanager.core.BatchProcessor;
import audiomanager.core.DependencyManager;
import audiomanager.ui.ThemeManager;
import audiomanager.model.BatchFileItem;
import audiomanager.model.ProcessingStatus;
import audiomanager.plugins.AudioSplitterTool;
import audiomanager.plugins.FileCombinerTool;
import audiomanager.util.PreferenceManager;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for file selection and batch queue management
 */
public class FileSelectionPanel implements BatchProcessor.FileCompletionCallback {

    /**
     * FIX (dark mode partial-revert bug): every {@code node.setStyle(...)}
     * call in this class now goes through here instead of calling
     * {@code setStyle} directly. The bug: {@link ThemeManager}'s sweep only
     * strips inline colors from nodes that exist in the scene graph *at the
     * moment dark mode is toggled* — any code that sets an inline style
     * dynamically AFTER that (e.g. re-coloring a button when it becomes
     * enabled/disabled based on selection state) puts the light-mode color
     * straight back, with no idea dark mode is even active. Confirmed by a
     * real screenshot: Browse/Add to Queue/Clear Queue/Output Directory
     * stayed in their bright light-mode colors after toggling dark mode,
     * specifically because their color is re-applied by state-change
     * handlers, not just at construction. Routing every call through here
     * means any future dynamic re-styling automatically gets the same
     * dark-mode treatment as the initial sweep, with no special-casing
     * required at each call site.
     */
    private static void setStyled(javafx.scene.Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    private final VBox root;
    private final ObservableList<BatchFileItem> batchFiles;
    private final PreferenceManager prefManager;
    private final Consumer<String> log;
    
    private TextField filePathField;
    private Label fileSizeLabel;
    private Button addFileButton;
    private Button browseButton;
    private Button clearQueueButton;
    private Button playButton;
    private javafx.scene.media.MediaPlayer mediaPlayer;
    private audiomanager.ui.WaveformView waveformView;
    private ListView<BatchFileItem> batchListView; // Keeping your ListView!
    private Label batchStatusLabel;

    // FIX: createFileTableView() is called from two places — the live
    // getBatchQueueSection() (wired into MainWindow, the actual "Batch Queue
    // Status" section the user sees) and the unused createUI(...) overload
    // (never called from MainWindow — dead code). Neither result was ever
    // kept as a field, so nothing outside the builder method could ever
    // reference the visible table to refresh it. This field points at the
    // live one, set inside getBatchQueueSection().
    private TableView<BatchFileItem> batchQueueTableView;
    
    private File selectedFile;
    private String outputDirectory;
    private Label fileDurationLabel;
    private static final Logger LOGGER = LoggerFactory.getLogger(FileSelectionPanel.class);

    // FIX (UI improvement — undo/redo): a small command-history stack for
    // queue mutations (add, remove, move-to-top/bottom). Scoped
    // deliberately to queue management, not to transcription itself —
    // there's no meaningful "undo" for a file that's already been
    // transcribed. Each command captures exactly what it needs to reverse
    // itself; both stacks are cleared on a fresh command push after an
    // undo (standard redo-invalidation behaviour).
    private interface QueueCommand {
        void undo();
        void redo();
        String description();
    }
    private final Deque<QueueCommand> undoStack = new ArrayDeque<>();
    private final Deque<QueueCommand> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO_HISTORY = 50;

    private void pushCommand(QueueCommand command) {
        undoStack.push(command);
        while (undoStack.size() > MAX_UNDO_HISTORY) undoStack.removeLast();
        redoStack.clear();
    }

    /** @return true if a queue change was undone. */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        QueueCommand command = undoStack.pop();
        command.undo();
        redoStack.push(command);
        log("↩️ Undo: " + command.description());
        updateBatchQueueTotals();
        return true;
    }

    /** @return true if a previously-undone queue change was reapplied. */
    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        QueueCommand command = redoStack.pop();
        command.redo();
        undoStack.push(command);
        log("↪️ Redo: " + command.description());
        updateBatchQueueTotals();
        return true;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    
    // Queue status labels that remain constant during processing
    private Label queueTotalLabel;
    private Label queueDurationLabel;
    private Label completedCountLabel;
    private Label failedCountLabel;
    private Label pendingCountLabel;

    // FIX: these four are never added to any container anywhere in this
    // file (confirmed by grep — no .getChildren().add(...) call references
    // them). They're a leftover from an earlier fix attempt that updated
    // the wrong label objects — queueTotalLabel/completedCountLabel/
    // failedCountLabel/queueDurationLabel above are the ones actually in
    // the visible "Batch Queue Status" grid. Left in place rather than
    // deleted, in case anything external reflects on them, but no longer
    // written to by the live update path — see applyQueueCounts() below.
    private final Label totalFilesValue = new Label("0");
    private final Label totalDurationValue = new Label("0s");
    private final Label completedValue = new Label("0");
    private final Label failedValue = new Label("0");
    
    // Current batch state
    private int currentBatchTotal = 0;
    private long currentBatchTotalDuration = 0;
    
    // Store initial total duration when processing starts
    private double initialTotalDurationSeconds = 0.0;
    private boolean isProcessing = false;
    private final SimpleBooleanProperty isProcessingProperty = new SimpleBooleanProperty(false);
    
    // Add this field to store pending files for multi-select
    private List<File> pendingFiles = new ArrayList<>();

    // FIX: was calling a bare "ffprobe" that only worked if the user had put it
    // on their system PATH. Now goes through the same bundled -> C:\AI\ffmpeg\bin
    // -> PATH resolution as the rest of the app (see DependencyManager).
    private final DependencyManager dependencyManager = new DependencyManager();
    
    public FileSelectionPanel(ObservableList<BatchFileItem> batchFiles,
                             PreferenceManager prefManager,
                             AudioSplitterTool audioSplitter,
                             FileCombinerTool fileCombiner,
                             Consumer<String> logger) {
        this.batchFiles = batchFiles;
        this.prefManager = prefManager;
        this.log = logger;
        this.outputDirectory = prefManager.getOutputDirectory();

        // Initialize the batch list view and status label
        this.batchListView = new ListView<>(batchFiles);
        this.batchListView.setCellFactory(lv -> new BatchFileListCell());
        this.batchStatusLabel = new Label("📁 Queue: 0 files");

        // Set up drag and drop for the ListView
        setupDragAndDropForListView();

        this.root = createUI(audioSplitter, fileCombiner);

        // ========== FIX: Initialize status label styles ==========
        initializeStatusLabels(); // ← ADD THIS LINE

        // Bind Clear Queue button disable property to batch files emptiness AND processing state
        clearQueueButton.disableProperty().bind(
            Bindings.isEmpty(batchFiles).or(isProcessingProperty)
        );

        // Add style change listener
        clearQueueButton.disableProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                // Button is disabled
                clearQueueButton.getStyleClass().setAll("action-btn-clear-queue-disabled");
            } else {
                // Button is enabled
                clearQueueButton.getStyleClass().setAll("action-btn-clear-queue-active");
            }
        });

        // Update batch status
        updateBatchStatus(batchFiles);
    }
    
    /**
     * Set up drag and drop reordering for ListView
     */
    private void setupDragAndDropForListView() {
        // Enable multiple selection
        batchListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        
        // Set up context menu
        setupContextMenuForListView();
        
        // Set up drag detection
        batchListView.setOnDragDetected(event -> {
            if (batchListView.getItems().isEmpty()) {
                return;
            }
            
            List<BatchFileItem> selectedItems = new ArrayList<>(
                batchListView.getSelectionModel().getSelectedItems()
            );
            
            if (!selectedItems.isEmpty()) {
                Dragboard dragboard = batchListView.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                
                // Store the indices of selected items
                List<Integer> selectedIndices = new ArrayList<>();
                for (BatchFileItem item : selectedItems) {
                    selectedIndices.add(batchListView.getItems().indexOf(item));
                }
                
                // Convert indices to string for storage
                StringBuilder indicesStr = new StringBuilder();
                for (int i = 0; i < selectedIndices.size(); i++) {
                    if (i > 0) indicesStr.append(",");
                    indicesStr.append(selectedIndices.get(i));
                }
                
                content.putString(indicesStr.toString());
                dragboard.setContent(content);
                event.consume();
            }
        });
        
        // Handle drag over
        batchListView.setOnDragOver(event -> {
            Dragboard dragboard = event.getDragboard();
            if (dragboard.hasString() && event.getGestureSource() != batchListView) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });
        
        // Handle drag dropped
        batchListView.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean success = false;
            
            if (dragboard.hasString()) {
                String[] indexStrs = dragboard.getString().split(",");
                List<Integer> draggedIndices = new ArrayList<>();
                
                for (String indexStr : indexStrs) {
                    try {
                        draggedIndices.add(Integer.parseInt(indexStr));
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
                
                if (!draggedIndices.isEmpty()) {
                    // Get drop position
                    int dropIndex = getDropIndex(event.getY());
                    
                    // Move the items
                    moveMultipleItems(draggedIndices, dropIndex);
                    success = true;
                }
            }
            
            event.setDropCompleted(success);
            event.consume();
        });
    }
    
    /**
     * Get the index where to drop based on mouse Y coordinate
     */
    private int getDropIndex(double mouseY) {
        int size = batchListView.getItems().size();
        if (size == 0) return 0;
        
        // Calculate which cell the mouse is over
        double cellHeight = 24.0; // Approximate cell height
        int index = (int) (mouseY / cellHeight);
        
        // Clamp to valid range
        return Math.max(0, Math.min(index, size));
    }
    
    /**
     * Move multiple items to a new position
     */
    private void moveMultipleItems(List<Integer> sourceIndices, int targetIndex) {
        if (sourceIndices.isEmpty()) return;
        
        // Sort indices in descending order to remove from end first
        List<Integer> sortedIndices = new ArrayList<>(sourceIndices);
        sortedIndices.sort(Collections.reverseOrder());
        
        // Collect items to move (in original order)
        List<BatchFileItem> itemsToMove = new ArrayList<>();
        for (int index : sourceIndices) {
            itemsToMove.add(batchFiles.get(index));
        }
        
        // Remove items from original positions
        for (int index : sortedIndices) {
            batchFiles.remove(index);
        }
        
        // Adjust target index if necessary
        int adjustedTarget = targetIndex;
        for (int index : sortedIndices) {
            if (index < targetIndex) {
                adjustedTarget--;
            }
        }
        
        // Ensure target index is within bounds
        adjustedTarget = Math.max(0, Math.min(adjustedTarget, batchFiles.size()));
        
        // Insert items at new position
        batchFiles.addAll(adjustedTarget, itemsToMove);
        
        // Select the moved items
        batchListView.getSelectionModel().clearSelection();
        for (int i = 0; i < itemsToMove.size(); i++) {
            batchListView.getSelectionModel().select(adjustedTarget + i);
        }
        
        log("↕️ Reordered " + itemsToMove.size() + " file(s) in queue");
        updateBatchStatus(batchFiles);
    }
    
    /**
     * Set up context menu for ListView
     */
    private void setupContextMenuForListView() {
        ContextMenu contextMenu = new ContextMenu();
        
        MenuItem removeSelectedItem = new MenuItem("Remove Selected");
        removeSelectedItem.setOnAction(e -> removeSelectedFiles());
        
        MenuItem removeAllItem = new MenuItem("Remove All");
        removeAllItem.setOnAction(e -> clearAllFiles());
        
        MenuItem moveToTopItem = new MenuItem("Move to Top");
        moveToTopItem.setOnAction(e -> moveSelectedToTop());
        
        MenuItem moveToBottomItem = new MenuItem("Move to Bottom");
        moveToBottomItem.setOnAction(e -> moveSelectedToBottom());
        
        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setOnAction(e -> batchListView.getSelectionModel().selectAll());
        
        contextMenu.getItems().addAll(
            removeSelectedItem,
            removeAllItem,
            new SeparatorMenuItem(),
            moveToTopItem,
            moveToBottomItem,
            new SeparatorMenuItem(),
            selectAllItem
        );
        
        batchListView.setContextMenu(contextMenu);
    }
    
    /**
     * Remove selected files
     */
    private void removeSelectedFiles() {
        ObservableList<BatchFileItem> selectedItems = batchListView.getSelectionModel().getSelectedItems();
        if (!selectedItems.isEmpty()) {
            batchFiles.removeAll(selectedItems);
            log("🗑️ Removed " + selectedItems.size() + " file(s) from queue");
            updateBatchQueueTotals();
        }
    }
    
    /**
     * Move selected items to top
     */
    private void moveSelectedToTop() {
        int selectedIndex = batchListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex > 0) {
            BatchFileItem item = batchFiles.remove(selectedIndex);
            batchFiles.add(0, item);
            batchListView.getSelectionModel().select(0);
            log("⬆️ Moved file to top");
        }
    }
    
    /**
     * Move selected items to bottom
     */
    private void moveSelectedToBottom() {
        int selectedIndex = batchListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex < batchFiles.size() - 1 && selectedIndex >= 0) {
            BatchFileItem item = batchFiles.remove(selectedIndex);
            batchFiles.add(item);
            batchListView.getSelectionModel().select(batchFiles.size() - 1);
            log("⬇️ Moved file to bottom");
        }
    }
    
    // 🚨 FIX 1: Implementation of the BatchProcessor.FileCompletionCallback interface
    @Override
    public void onFileCompleted(BatchFileItem item, boolean wasSuccessful) {
        Platform.runLater(() -> {
            if (wasSuccessful) {
                log("✅ File completed successfully: " + item.getFileName());
            } else {
                log("❌ File failed processing: " + item.getFileName());
            }
            
            // Delegate the UI removal and total calculation update to FileSelectionPanel
            removeItemFromBatchQueue(item);
        });
    }
    
    // 🚨 FIX 1: Implementation of the queue removal logic
    public void removeItemFromBatchQueue(BatchFileItem item) {
        if (item == null) return;
        
        Platform.runLater(() -> {
            boolean autoRemove = prefManager.getBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, false);
            
            if (autoRemove) {
                boolean removed = batchFiles.remove(item);
                if (removed) {
                    LOGGER.debug("Removed completed file from UI queue: {}", item.getFileName());
                }
            } else {
                // If not auto-removing, the file stays in the list with its COMPLETED status
                LOGGER.debug("Kept item in queue display: {}", item.getFileName());
            }

            // FIX: previously only called inside the `if (removed)` branch
            // above, so the done/failed/pending totals never refreshed when
            // auto-remove was off (the default) — a file could finish, have
            // its status correctly set to COMPLETED, and sit visibly in the
            // list, but the "Ready to Process" summary counts wouldn't
            // reflect it until something else happened to trigger a
            // recalculation. Failed files never go through this method at
            // all (MainWindow.onFileCompleted only calls it on success), so
            // that count was never affected by this gap — which is why
            // "failed" worked while "done" appeared stuck.
            updateBatchQueueTotals();
        });
    }
    
    // 🚨 NEW: Helper Method to Remove File and Update Totals
    /**
     * Removes a BatchFileItem from the queue and updates status labels.
     * This method is called by the "Remove From Queue" context menu item.
     * @param item The file item to remove.
     */
    public void removeFileFromQueue(BatchFileItem item) {
        if (item != null) {
            batchFiles.remove(item);
            log(String.format("🗑️ File removed from queue: %s", item.getFileName()));
            
            // This is crucial to fix Bug #2 (Total Files/Duration labels update)
            updateBatchQueueTotals(); 
        }
    }
    
    // 🚨 FIX P2, P5: Method to compute and update totals
    public void updateBatchQueueTotals() {
        Platform.runLater(() -> {
            int totalFiles = batchFiles.size();
            int completed = 0;
            int failed = 0;

            // FIX: was BatchFileItem::getDurationSeconds — that method is
            // never populated by any setter anywhere in the codebase (grep
            // confirms setDurationSeconds() doesn't exist; only
            // setTotalAudioDurationSeconds() does, called after ffprobe/
            // file-size estimation completes), so it always summed to 0.
            double totalDurationSeconds = 0.0;
            for (BatchFileItem item : batchFiles) {
                totalDurationSeconds += item.getTotalAudioDurationSeconds();
                if ("COMPLETED".equals(item.getStatus())) completed++;
                else if ("FAILED".equals(item.getStatus())) failed++;
                // FIX: CANCELLED (a real ProcessingStatus value, and confirmed
                // reachable — BatchProcessor sets it on user cancellation)
                // previously fell through uncounted here, so pendingCountLabel
                // below (totalFiles - completed - failed) permanently counted a
                // cancelled file as "pending". Same trade-off (no distinct
                // cancelled bucket in this label set) as the equivalent fix in
                // MainWindow.setupTimeUpdater().
                else if ("CANCELLED".equals(item.getStatus())) failed++;
            }

            // FIX: was writing to totalFilesValue/totalDurationValue — see
            // the fix note on the field declarations above; those Labels
            // are never added to any visible container. queueTotalLabel/
            // queueDurationLabel/completedCountLabel/failedCountLabel/
            // pendingCountLabel are the ones actually shown in the "Batch
            // Queue Status" grid.
            queueTotalLabel.setText(String.valueOf(totalFiles));
            queueDurationLabel.setText(formatDuration(totalDurationSeconds));
            completedCountLabel.setText(String.valueOf(completed));
            failedCountLabel.setText(String.valueOf(failed));
            pendingCountLabel.setText(String.valueOf(Math.max(0, totalFiles - completed - failed)));

            LOGGER.debug("Batch queue totals updated: {} files, {} duration", totalFiles, queueDurationLabel.getText());
        });
    }

    // 🚨 FIX P2, P5: Helper method to format time
    private String formatDuration(double totalSeconds) {
        if (totalSeconds <= 0) return "00:00:00";
        long seconds = (long) totalSeconds;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    // 🚨 FIX P4: Getter for the button
    public Button getClearQueueButton() {
        return clearQueueButton; 
    }

    // Style the value labels
    private void initializeStatusLabels() {
        String valueStyle = "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 11px;";
        setStyled(totalFilesValue, valueStyle);
        setStyled(totalDurationValue, valueStyle);
        setStyled(completedValue, valueStyle);
        setStyled(failedValue, valueStyle);
    }

    /**
     * Returns the batch queue section separately
     * @return The Batch Queue object is returned as VBox.
     */
    public VBox getBatchQueueSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(15, -5, 15, -5));
        setStyled(section, "-fx-border-color: #e0e0e0; -fx-border-width: 1; -fx-border-radius: 5;");

        Label title = new Label("📊 Batch Queue Status");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");        // color removed
        title.getStyleClass().add("panel-heading");

        // Queue status grid - these values remain constant during processing
        GridPane statusGrid = new GridPane();
        statusGrid.setHgap(15);
        statusGrid.setVgap(8);
        statusGrid.setPadding(new Insets(10, 0, 10, 0));

        // Total files (constant during batch)
        Label totalTitle = new Label("Total Files:");
        setStyled(totalTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        queueTotalLabel = new Label("0");
        setStyled(queueTotalLabel, "-fx-font-size: 12px;");

        // Total duration (constant during batch)  
        Label durationTitle = new Label("Total Duration:");
        setStyled(durationTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        queueDurationLabel = new Label("0s");
        setStyled(queueDurationLabel, "-fx-font-size: 12px;");

        // Completion counts (updated in real-time)
        Label completedTitle = new Label("Completed:");
        setStyled(completedTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        completedCountLabel = new Label("0");
        setStyled(completedCountLabel, "-fx-font-size: 12px; -fx-text-fill: #2e7d32;");

        Label failedTitle = new Label("Failed:");
        setStyled(failedTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        failedCountLabel = new Label("0");
        setStyled(failedCountLabel, "-fx-font-size: 12px;");
        failedCountLabel.getStyleClass().add("status-negative");

        // Pending (added — total remaining to be processed: total - completed - failed)
        Label pendingTitle = new Label("Pending:");
        setStyled(pendingTitle, "-fx-font-weight: bold; -fx-font-size: 12px;");
        pendingCountLabel = new Label("0");
        setStyled(pendingCountLabel, "-fx-font-size: 12px;");
        pendingCountLabel.getStyleClass().add("status-accent");

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

        // ========== FIX: Use TableView instead of ListView ==========
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
        clearAllButton.setOnAction(e -> clearAllFiles());

        actionBox.getChildren().addAll(clearCompletedButton, removeSelectedButton, clearAllButton);

        // ========== IMPORTANT: Add fileTableView, NOT batchListView ==========
        section.getChildren().addAll(title, statusGrid, fileTableView, actionBox);

        return section;
    }

    /**
     * Returns only the file selection controls (without tools and batch queue)
     */
    VBox getFileSelectionControls() {
        VBox fileControls = new VBox(8);
        fileControls.setPadding(new Insets(15));
        setStyled(fileControls, "-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; -fx-background-color: white;");
        fileControls.getStyleClass().add("theme-fix-surface");   // ← add

        // Row 1: Title Label
        Label titleLabel = new Label("🎵 Audio File Selection");
        setStyled(titleLabel, "-fx-font-weight: bold; -fx-font-size: 14px;");   // color removed
        titleLabel.getStyleClass().add("panel-heading");

        // Row 2: File input box and buttons
        HBox fileInputRow = new HBox(10);
        fileInputRow.setAlignment(Pos.CENTER_LEFT);

        filePathField = new TextField();
        filePathField.setPromptText("🎵 Select audio file...");
        filePathField.setEditable(false);
        setStyled(filePathField, "-fx-border-color: #bdc3c7; -fx-border-radius: 4;");
        filePathField.getStyleClass().add("theme-fix-surface-alt");
        HBox.setHgrow(filePathField, Priority.ALWAYS);

        browseButton = new Button("📁 Browse...");
        browseButton.setOnAction(e -> selectAudioFiles()); // Multi-select
        browseButton.setStyle("-fx-font-weight: bold; -fx-background-radius: 4;");
        browseButton.getStyleClass().add("action-btn-browse");
        browseButton.setPrefWidth(120);

        addFileButton = new Button("➕ Add to Queue");
        addFileButton.setOnAction(e -> addFilesToBatch()); // Multi-add
        addFileButton.setDisable(true);
        setStyled(addFileButton, "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        addFileButton.setPrefWidth(140);

        // Add spacer to align with first row
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        fileInputRow.getChildren().addAll(titleLabel, spacer, browseButton, addFileButton);

        // Row 3: File info (size and duration)
        HBox fileInfoRow = new HBox(20);
        fileInfoRow.setAlignment(Pos.CENTER_LEFT);

        fileSizeLabel = new Label();
        setStyled(fileSizeLabel, "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");

        fileDurationLabel = new Label();
        setStyled(fileDurationLabel, "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");

        fileInfoRow.getChildren().addAll(fileSizeLabel, fileDurationLabel);

        // Second row: Clear Queue and Output Directory buttons
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
        
    /**
     * Set processing state
     * @param processing  A value of type boolean that is set to true if the processing state is active.
     */
    public void setProcessingState(boolean processing) {
        this.isProcessing = processing;
        if (!processing) {
            // When processing stops, recalculate duration based on completed files
            updateBatchStatus(batchFiles);
        }
    }
    
    /**
     * Estimate audio duration from file size
     */
    private double estimateDurationFromFileSize(File file) {
        long fileSizeBytes = file.length();
        double fileSizeMB = fileSizeBytes / (1024.0 * 1024.0);
        
        // Conservative estimation for various formats
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".wav")) {
            return (fileSizeMB / 10.0) * 60; // WAV: ~10MB per minute
        } else if (fileName.endsWith(".flac")) {
            return (fileSizeMB / 5.0) * 60;  // FLAC: ~5MB per minute
        } else {
            return (fileSizeMB / 1.0) * 60;  // Compressed: ~1MB per minute
        }
    }
    
    /**
     * Update batch status with persistent totals and statistics
     * @param items  A list of Batch File Items to be processed.
     */
    public void updateBatchStatus(ObservableList<BatchFileItem> items) {
        Platform.runLater(() -> {
        int totalFiles = 0;
        int completed = 0;
        int failed = 0;
        long totalDurationSeconds = 0;
        
        for (BatchFileItem item : items) {
            totalFiles++;
            // FIX: was getDurationSeconds(), an always-unset field — see
            // fix note in updateBatchQueueTotals() above.
            totalDurationSeconds += (long) item.getTotalAudioDurationSeconds();
            if ("COMPLETED".equals(item.getStatus())) completed++;
            else if ("FAILED".equals(item.getStatus())) failed++;
            // FIX: same CANCELLED-miscounted-as-pending bug as
            // updateBatchQueueTotals() above — see that method's comment.
            else if ("CANCELLED".equals(item.getStatus())) failed++;
        }
        
        // Update total files and duration labels
        queueTotalLabel.setText(String.valueOf(totalFiles));
        queueDurationLabel.setText(formatDuration(totalDurationSeconds));
        completedCountLabel.setText(String.valueOf(completed));
        failedCountLabel.setText(String.valueOf(failed));
        pendingCountLabel.setText(String.valueOf(Math.max(0, totalFiles - completed - failed)));
        batchStatusLabel.setText("📁 Queue: " + totalFiles + " files");
        });
    }
    
    /**
     * Update batch status during active processing, using cumulative counts
     * computed by the caller (MainWindow tracks completed/failed with
     * identity sets that survive items being removed from {@code items} by
     * auto-remove-completed — recomputing from {@code items} directly here,
     * as the old version did, would undercount the moment a completed item
     * is removed from the list it's being tallied from).
     *
     * FIX: previously (as {@code updateBatchStatus(items, stats)}) this
     * wrote to {@code totalFilesValue}/{@code completedValue}/
     * {@code failedValue}/{@code totalDurationValue} — four Label fields
     * that are never added to any container anywhere in this class, so
     * every update was invisible. The real, visible "Batch Queue Status"
     * grid uses {@code queueTotalLabel}/{@code completedCountLabel}/
     * {@code failedCountLabel}/{@code queueDurationLabel}, which is what
     * this version now writes to, plus the new {@code pendingCountLabel}.
     *
     * @param items     current queue (only used here to refresh the table)
     * @param completed cumulative completed count for this batch run
     * @param failed    cumulative failed count for this batch run
     * @param pending   files not yet completed or failed
     * @param total     original batch size (fixed for the run)
     */
    public void updateBatchStatus(ObservableList<BatchFileItem> items,
                                   int completed, int failed, int pending, int total) {
        Platform.runLater(() -> {
            if (items == null) return;

            // FIX: was `(long)(initialTotalDurationSeconds * 1000)` — the
            // same x1000 unit bug as resetBatchStatus() above.
            // initialTotalDurationSeconds is already in seconds, and this
            // method is called every second while processing (from
            // MainWindow's Timeline), so the inflated "410 hour" style
            // value was what stayed visible in the Batch Status panel for
            // the entire run, not just at startup.
            long totalDurationSeconds = (long) initialTotalDurationSeconds;

            queueTotalLabel.setText(String.valueOf(total));
            completedCountLabel.setText(String.valueOf(completed));
            failedCountLabel.setText(String.valueOf(failed));
            pendingCountLabel.setText(String.valueOf(pending));
            queueDurationLabel.setText(formatDuration(totalDurationSeconds));
            batchStatusLabel.setText("📁 Queue: " + pending + " pending / " + total + " total");

            // FIX: added — the Progress column's cellValueFactory returns a
            // fresh, disconnected SimpleObjectProperty snapshot each time a
            // row renders; it never re-observes the item's own progress as
            // it changes. Without an explicit refresh() call, JavaFX has no
            // reason to re-invoke that factory, so the per-row progress
            // bars stayed frozen at whatever they were on first render for
            // the whole batch. This method already runs every second while
            // processing, so refreshing here is enough to keep them live.
            if (batchQueueTableView != null) {
                batchQueueTableView.refresh();
            }

            LOGGER.debug("Batch Status - Total: {}, Completed: {}, Failed: {}, Pending: {}, Duration: {}s",
                        total, completed, failed, pending, totalDurationSeconds);
        });
    }

    private VBox createUI(AudioSplitterTool audioSplitter, FileCombinerTool fileCombiner) {
        VBox container = new VBox(10);
        container.setPadding(new Insets(0, 0, 15, 0));

        Label titleLabel = new Label("🎵 Audio File Selection");
        setStyled(titleLabel, "-fx-font-weight: bold; -fx-font-size: 14px;");   // color removed
        titleLabel.getStyleClass().add("panel-heading");

        // Use the dedicated method instead of duplicating code
        VBox fileSelectionRow = createFileSelectionRow();

        fileSizeLabel = new Label();
        setStyled(fileSizeLabel, "-fx-text-fill: #666; -fx-font-size: 11px;");


        // Tools section with fixed height and scroll
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

        // Batch queue - using TableView instead of ListView
        VBox batchSection = new VBox(5);
        Label batchLabel = new Label("📋 Batch Queue:");
        setStyled(batchLabel, "-fx-font-weight: bold; -fx-font-size: 12px;");

        TableView<BatchFileItem> fileTableView = createFileTableView();
        fileTableView.setItems(batchFiles);
        fileTableView.setPrefHeight(150);

        batchStatusLabel = new Label("📁 Queue: 0 files");
        setStyled(batchStatusLabel, "-fx-text-fill: #666; -fx-font-size: 11px;");

        batchSection.getChildren().addAll(batchLabel, fileTableView, batchStatusLabel);

        container.getChildren().addAll(
            titleLabel, fileSelectionRow, fileSizeLabel, 
            toolsPane, batchSection
        );

        return container;
    }
    
    private TitledPane createToolsPaneWithScroll(AudioSplitterTool audioSplitter, FileCombinerTool fileCombiner) {
        TitledPane pane = new TitledPane();
        pane.setText("🛠️ Audio Tools");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        // Create content for tools
        VBox toolsContent = new VBox(10);
        toolsContent.setPadding(new Insets(10));

        // Audio Splitter section
        VBox splitterSection = new VBox(5);
        Label splitterLabel = new Label("Audio Splitter");
        setStyled(splitterLabel, "-fx-font-weight: bold;");
        splitterLabel.getStyleClass().add("panel-heading");
        Node splitterUI = audioSplitter.createUI();
        splitterSection.getChildren().addAll(splitterLabel, splitterUI);

        // File Combiner section  
        VBox combinerSection = new VBox(5);
        Label combinerLabel = new Label("File Combiner");
        setStyled(combinerLabel, "-fx-font-weight: bold;");
        combinerLabel.getStyleClass().add("panel-heading");
        Node combinerUI = fileCombiner.createUI();
        combinerSection.getChildren().addAll(combinerLabel, combinerUI);

        toolsContent.getChildren().addAll(splitterSection, new Separator(), combinerSection);

        // Wrap in scroll pane with fixed height
        ScrollPane scrollPane = new ScrollPane(toolsContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(250); // Fixed height
        scrollPane.setMaxHeight(300);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        pane.setContent(scrollPane);

        return pane;
    }
    
    private VBox createFileSelectionRow() {
        // First row: Browse and Add to Queue
        HBox firstRow = new HBox(10);
        firstRow.setAlignment(Pos.CENTER_LEFT);

        filePathField = new TextField();
        filePathField.setPromptText("🎵 Select audio file...");
        filePathField.setEditable(false);
        setStyled(filePathField, "-fx-border-color: #bdc3c7; -fx-border-radius: 4;");
        filePathField.getStyleClass().add("theme-fix-surface-alt");
        HBox.setHgrow(filePathField, Priority.ALWAYS);

        browseButton = new Button("📁 Browse...");
        browseButton.setOnAction(e -> selectAudioFiles()); // Multi-select
        setStyled(browseButton, "-fx-font-weight: bold; -fx-background-radius: 4;");
        browseButton.getStyleClass().add("action-btn-browse");
        browseButton.setPrefWidth(120);

        addFileButton = new Button("➕ Add to Queue");
        addFileButton.setOnAction(e -> addFilesToBatch()); // Multi-add
        addFileButton.setDisable(true); // Disabled on startup
        setStyled(addFileButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
        addFileButton.setPrefWidth(120);

        playButton = new Button("▶️ Play");
        playButton.setOnAction(e -> playSelectedFile());
        playButton.setDisable(true);
        setStyled(playButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
        playButton.setPrefWidth(100);

        firstRow.getChildren().addAll(filePathField, browseButton, addFileButton, playButton);

        // Second row: Clear Queue and Output Directory buttons
        HBox secondRow = new HBox(10);
        secondRow.setAlignment(Pos.CENTER_LEFT);

        clearQueueButton = new Button("🗑️ Clear Queue");
        clearQueueButton.setOnAction(e -> clearBatchFiles());
        clearQueueButton.setDisable(true); // Disabled on startup - no files in queue
        setStyled(clearQueueButton, "-fx-background-radius: 4;");
        clearQueueButton.getStyleClass().add("action-btn-clear-queue-disabled");
        clearQueueButton.setPrefWidth(120);

        Button outputDirButton = new Button("📂 Output Directory...");
        outputDirButton.setOnAction(e -> selectOutputDirectory());
        outputDirButton.setTooltip(new Tooltip("Current: " + outputDirectory));
        setStyled(outputDirButton, "-fx-background-radius: 4;");
        outputDirButton.getStyleClass().add("action-btn-output-dir");
        outputDirButton.setPrefWidth(140);

        // Add spacer to align with first row
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        secondRow.getChildren().addAll(spacer, clearQueueButton, outputDirButton);

        // FIX (UI improvement — waveform preview): third row, a simple
        // amplitude preview of the currently-selected file. Decoded via
        // FFmpeg (already a hard dependency) on a background thread — see
        // WaveformView for scope/limits (skips files over 200MB).
        waveformView = new audiomanager.ui.WaveformView(dependencyManager, 600, 50);
        HBox waveformRow = new HBox(waveformView);
        waveformRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(waveformView, Priority.ALWAYS);

        // Combine both rows in a VBox
        VBox fileBox = new VBox(8);
        fileBox.getChildren().addAll(firstRow, secondRow, waveformRow);

        return fileBox;
    }

    /**
     * Update Add to Queue button state based on file selection
     */
    private void updateAddToQueueButtonState(boolean hasFiles) {
        addFileButton.setDisable(!hasFiles);

        // Update button style based on state
        if (hasFiles) {
            setStyled(addFileButton, "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4;");
        } else {
            setStyled(addFileButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
        }
    }

    /**
     * Play (or stop) a simple preview of the currently selected file using
     * JavaFX's MediaPlayer. Best-effort: some formats/codecs aren't supported
     * by JavaFX Media out of the box, so failures are logged and surfaced
     * without crashing the app.
     */
    private void playSelectedFile() {
        if (selectedFile == null) return;

        // If something is already playing, treat this click as "stop".
        if (mediaPlayer != null) {
            stopPlayback();
            return;
        }

        try {
            javafx.scene.media.Media media = new javafx.scene.media.Media(selectedFile.toURI().toString());
            mediaPlayer = new javafx.scene.media.MediaPlayer(media);
            mediaPlayer.setOnError(() -> {
                log("❌ Cannot play audio: " + mediaPlayer.getError().getMessage());
                stopPlayback();
            });
            mediaPlayer.setOnEndOfMedia(this::stopPlayback);
            mediaPlayer.play();
            playButton.setText("⏹️ Stop");
        } catch (Exception e) {
            log("❌ Cannot play audio: " + e.getMessage());
            mediaPlayer = null;
        }
    }

    /** Stop and dispose of any in-progress preview playback, resetting the button label. */
    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.dispose();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            mediaPlayer = null;
        }
        if (playButton != null) {
            playButton.setText("▶️ Play");
        }
    }

    /**
     * Public entry points for keyboard shortcuts (Ctrl+O / Ctrl+Shift+O in
     * MainWindow) to trigger the same actions as the Browse... and Output
     * Directory... buttons, without exposing the private selection methods
     * or button fields themselves.
     */
    public void triggerBrowse() {
        selectAudioFiles();
    }

    public void triggerOutputDirectoryChooser() {
        selectOutputDirectory();
    }

    /**
     * Select multiple audio files
     */
    private void selectAudioFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Audio Files");

        // Enable multiple file selection
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Audio", 
                "*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a", "*.wma", 
                "*.aac", "*.opus", "*.alac", "*.aiff", "*.amr", "*.ac3"),
            new FileChooser.ExtensionFilter("MP3 Files", "*.mp3"),
            new FileChooser.ExtensionFilter("WAV Files", "*.wav"),
            new FileChooser.ExtensionFilter("FLAC Files", "*.flac")
        );

        // Use last file add location
        File initialDir = new File(prefManager.getLastFileAddLocation());
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        }

        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(null);

        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            // Store the first file for display purposes
            selectedFile = selectedFiles.get(0);

            // Update display with count if multiple files
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

            // Remember the directory for next time
            prefManager.setLastFileAddLocation(selectedFiles.get(0).getParent());
            prefManager.setLastFileDirectory(selectedFiles.get(0).getParent());

            // ========== FIX: Create a modifiable copy of the list ==========
            this.pendingFiles = new ArrayList<>(selectedFiles); // ← THIS IS THE FIX
            updateAddToQueueButtonState(true);
        } else {
            updateAddToQueueButtonState(false);
            stopPlayback();
            playButton.setDisable(true);
            setStyled(playButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
            if (waveformView != null) waveformView.clear();
        }
    }

    /**
     * Add multiple files to batch
     */
    private void addFilesToBatch() {
        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log("❌ ERROR: No files selected");
            return;
        }

        int added = 0;
        int skipped = 0;
        List<BatchFileItem> addedItems = new ArrayList<>();

        for (File file : pendingFiles) {
            if (file.length() > AppConstants.MAX_FILE_SIZE) {
                log("⚠️ Skipped (too large): " + file.getName());
                skipped++;
                continue;
            }

            // Check for duplicates
            boolean duplicate = batchFiles.stream()
                .anyMatch(item -> item.getFile().equals(file));

            if (duplicate) {
                log("⚠️ Skipped (duplicate): " + file.getName());
                skipped++;
                continue;
            }

            BatchFileItem item = new BatchFileItem(file);

            // Set the audio duration for the batch item
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
            log("➕ Added " + added + " file(s) to queue" + (skipped > 0 ? " (" + skipped + " skipped)" : ""));
            updateBatchStatus(batchFiles);
            updateBatchQueueTotals(); // Make sure totals are updated
        }

        // Clear selection - now safe because pendingFiles is modifiable
        pendingFiles.clear(); // ← Now this will work
        selectedFile = null;
        filePathField.clear();
        fileSizeLabel.setText("");
        fileDurationLabel.setText("");
        updateAddToQueueButtonState(false);
        stopPlayback();
        playButton.setDisable(true);
        setStyled(playButton, "-fx-background-color: #bdc3c7; -fx-text-fill: #7f8c8d; -fx-background-radius: 4;");
    }

    private void updateFileInfoLabels(File file) {
        long size = file.length();
        double sizeMB = size / (1024.0 * 1024.0);

        // Update file size label
        if (size > AppConstants.MAX_FILE_SIZE) {
            fileSizeLabel.setText(String.format(
                "❌ File size: %.2f MB (EXCEEDS %d MB limit)", 
                sizeMB, AppConstants.MAX_FILE_SIZE_MB
            ));
            setStyled(fileSizeLabel, "-fx-text-fill: red; -fx-font-size: 11px;");
            addFileButton.setDisable(true);
            fileDurationLabel.setText(""); // Clear duration if file is too large
            log("⚠️ WARNING: File exceeds maximum size limit");
        } else {
            fileSizeLabel.setText(String.format("📏 File size: %.2f MB", sizeMB));
            setStyled(fileSizeLabel, "-fx-text-fill: green; -fx-font-size: 11px;");
            addFileButton.setDisable(false);

            // Calculate and display duration
            long duration = (long)getAudioDuration(file);
            if (duration > 0) {
                String durationText = formatDuration(duration);
                fileDurationLabel.setText("⏱️ Duration: " + durationText);
                setStyled(fileDurationLabel, "-fx-font-size: 11px;");
                fileDurationLabel.getStyleClass().add("status-accent");
            } else {
                fileDurationLabel.setText("⏱️ Duration: Unknown");
                setStyled(fileDurationLabel, "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
            }
        }
    }

    /**
     * Probes the real audio duration for a file (via ffprobe) and sets it
     * on the item if successful — the same logic addFilesToBatch() already
     * uses for freshly-added files. Exposed so other entry points that
     * construct a BatchFileItem directly (e.g. MainWindow's session-restore
     * path) can populate duration the same way, instead of leaving it at
     * its default and only ever falling back to the file-size estimate.
     */
    public void probeAndSetDuration(BatchFileItem item) {
        double duration = getAudioDuration(item.getFile());
        if (duration > 0) {
            item.setTotalAudioDurationSeconds(duration);
        }
    }

    private double getAudioDuration(File file) {
        try {
            // Use FFprobe to get audio duration
            ProcessBuilder builder = new ProcessBuilder(
                dependencyManager.getFFprobePath(), "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.getAbsolutePath()
            );

            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return Double.parseDouble(output);
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            // If FFprobe fails, try a simpler approach or return 0
            LOGGER.debug("Could not get audio duration for: {}", file.getName(), e);
        }
        return 0.0;
    }

    /**
     * Format file size for display
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Format duration for display
     */
    private String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%d:%02d", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    /**
     * Reset batch status when processing starts
     */
    public void resetBatchStatus() {
        // Calculate total duration from all files in the queue at start
        initialTotalDurationSeconds = 0.0;
        
        for (BatchFileItem item : batchFiles) {
            // Use actual audio duration if available, otherwise estimate from file size
            double duration = item.getTotalAudioDurationSeconds();
            if (duration <= 0) {
                // Estimate duration from file size
                duration = estimateDurationFromFileSize(item.getFile());
            }
            initialTotalDurationSeconds += duration;
        }
        
        isProcessing = true;
        
        // Update UI with initial values
        updateBatchStatus(batchFiles);
        
        // FIX: was `formatDuration((long)(initialTotalDurationSeconds * 1000))`.
        // initialTotalDurationSeconds is already a count of SECONDS (summed
        // from getTotalAudioDurationSeconds() / estimateDurationFromFileSize()
        // above), but formatDuration(long totalSeconds) also treats its
        // argument as seconds directly (divides by 3600/60, no /1000 anywhere
        // inside it). Multiplying by 1000 before calling it inflated the
        // displayed/logged total batch duration by 1000x — e.g. a real
        // ~25-minute batch was logged and displayed as "410:36:28" (410
        // hours). Since this total feeds the batch progress/time-left
        // denominator, it made a batch that WAS actively processing look
        // permanently stuck at ~0% complete.
        log("📊 Batch initialized: " + batchFiles.size() + " files, total duration: " + 
            formatDuration((long) initialTotalDurationSeconds));
    }

    /**
     * Clear completed files from queue
     */
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
            log("🧹 Removed " + removed + " completed files from queue");
            updateBatchQueueTotals(); // 🚨 ADDED: Update totals after removal
        }
    }

    /**
     * Clear all files from queue
     */
    private void clearAllFiles() {
        if (!batchFiles.isEmpty()) {
            int count = batchFiles.size();
            batchFiles.clear();
            resetBatchStatus();
            log("🗑️ Cleared all " + count + " files from queue");
            updateBatchQueueTotals(); // 🚨 ADDED: Update totals after removal
        }
    }
    
    private void clearBatchFiles() {
        if (batchFiles.isEmpty()) {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Clear Queue");
        alert.setHeaderText("Clear all files from the batch queue?");
        alert.setContentText("This action cannot be undone.");

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                batchFiles.clear();
                log("🗑️ Batch queue cleared");
                updateBatchStatus(batchFiles);
                updateBatchQueueTotals(); // 🚨 ADDED: Update totals after removal
            }
        });
    }

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
            log("📂 Output directory: " + outputDirectory);
        }
    }
    
    public void updateOutputDirectory(String path) {
        this.outputDirectory = path;
    }

    public VBox getRoot() {
        return root;
    }

    private void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    /**
     * Custom cell for batch file list
     */
    private static class BatchFileListCell extends ListCell<BatchFileItem> {
        private final HBox content;
        private final Label fileNameLabel;
        private final Label statusLabel;
        private final ProgressBar progressBar;

        public BatchFileListCell() {
            fileNameLabel = new Label();
            statusLabel = new Label();
            progressBar = new ProgressBar();
            progressBar.setMaxWidth(Double.MAX_VALUE);

            VBox infoBox = new VBox(2, fileNameLabel, statusLabel);
            infoBox.setAlignment(Pos.CENTER_LEFT);
            infoBox.setPrefWidth(300);

            HBox progressBox = new HBox(10, progressBar);
            progressBox.setAlignment(Pos.CENTER_RIGHT);
            HBox.setHgrow(progressBox, Priority.ALWAYS);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            content = new HBox(10, infoBox, spacer, progressBox);
            content.setAlignment(Pos.CENTER_LEFT);
            content.setPadding(new Insets(5));
        }

        @Override
        protected void updateItem(BatchFileItem item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
            } else {
                progressBar.progressProperty().unbind();
                statusLabel.textProperty().unbind();

                fileNameLabel.setText(item.getFile().getName());
                progressBar.progressProperty().bind(item.progressProperty());
                statusLabel.textProperty().bind(item.statusProperty());

                // Style based on status
                String status = item.getStatus();
                if (ProcessingStatus.COMPLETED.name().equals(status)) {
                    setStyled(statusLabel, "-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                    setStyled(progressBar, "-fx-accent: #4CAF50;");
                } else if (ProcessingStatus.PROCESSING.name().equals(status)) {
                    setStyled(statusLabel, "-fx-text-fill: #2196F3; -fx-font-weight: bold;");
                    setStyled(progressBar, "-fx-accent: #2196F3;");
                } else if (ProcessingStatus.FAILED.name().equals(status)) {
                    setStyled(statusLabel, "-fx-text-fill: #F44336; -fx-font-weight: bold;");
                    setStyled(progressBar, "-fx-accent: #F44336;");
                } else {
                    setStyled(statusLabel, "-fx-text-fill: #666;");
                    setStyled(progressBar, "-fx-accent: #666;");
                }

                setGraphic(content);
            }
        }
    }
    
    public VBox getRootWithoutTools() {
        VBox rootWithoutTools = new VBox(10);
        rootWithoutTools.setPadding(new Insets(0, 0, 15, 0));

        // Copy the main file selection components (title, file selection row, file size label, batch section)
        // Skip only the tools pane
        for (int i = 0; i < root.getChildren().size(); i++) {
            Node node = root.getChildren().get(i);
            // Skip the tools pane - identify it by its content or index
            if (node instanceof TitledPane pane) if ("🛠️ Audio Tools".equals(pane.getText())) continue;
            rootWithoutTools.getChildren().add(node);
        }

        return rootWithoutTools;
    }

    // Also add this method to get the tools pane separately
    public TitledPane getToolsPane() {
        // The tools pane is the 4th child (index 3) in the root VBox
        if (root.getChildren().size() > 3) {
            Node node = root.getChildren().get(3);
            if (node instanceof TitledPane titledPane) {
                return titledPane;
            }
        }
        return null;
    }
    
    public ListView<BatchFileItem> getBatchListView() {
        return batchListView;
    }

    public VBox getBatchStatusDisplay() {
        VBox statusBox = new VBox(5);
        statusBox.getChildren().add(batchStatusLabel);
        return statusBox;
    }
    
    /**
     * Initialize batch status labels when processing starts
     * @param totalFiles
     * @param totalDuration
     */
    public void initializeBatchStatus(int totalFiles, String totalDuration) {
        Platform.runLater(() -> {
            // FIX: was writing to totalFilesValue/totalDurationValue/
            // completedValue/failedValue — Labels never added to any
            // visible container in this class (see fix note on their
            // declarations above). queueTotalLabel/queueDurationLabel/
            // completedCountLabel/failedCountLabel/pendingCountLabel are
            // the ones actually shown in the "Batch Queue Status" grid.
            queueTotalLabel.setText(String.valueOf(totalFiles));
            queueDurationLabel.setText(totalDuration);
            completedCountLabel.setText("0");
            failedCountLabel.setText("0");
            pendingCountLabel.setText(String.valueOf(totalFiles));

            LOGGER.debug("Batch status initialized: {} files, {}", totalFiles, totalDuration);
        });
    }

    /**
     * Update completed and failed counts in real-time
     * @param completed
     * @param failed
     */
    public void updateCompletedFailedCounts(int completed, int failed) {
        Platform.runLater(() -> {
            // FIX: was writing to completedValue/failedValue — see fix
            // note above; those Labels render nowhere. This method is
            // called every second from MainWindow's Timeline
            // (updateBatchProgress()), so it's worth having it actually
            // reach the visible labels rather than silently doing nothing.
            completedCountLabel.setText(String.valueOf(completed));
            failedCountLabel.setText(String.valueOf(failed));

            // Update colors based on status
            if (completed > 0) {
                setStyled(completedCountLabel, "-fx-font-weight: bold; -fx-text-fill: #2e7d32; -fx-font-size: 12px;");
            }
            if (failed > 0) {
                setStyled(failedCountLabel, "-fx-font-weight: bold; -fx-text-fill: #d32f2f; -fx-font-size: 12px;");
            }

            LOGGER.debug("Completed/Failed counts updated: {}/{}", completed, failed);
        });
    }
    
    /**
     * Save file-related preferences
     */
    public void saveFilePreferences() {
        if (prefManager != null) {
            try {
                // Save output directory
                prefManager.setOutputDirectory(outputDirectory);
                if (outputDirectory != null && !outputDirectory.isEmpty()) {
                    prefManager.putString("output_directory", outputDirectory);
                }

                // Save last file locations
                if (selectedFile != null) {
                    prefManager.setLastFileAddLocation(selectedFile.getParent());
                }

                // Save current batch statistics
                prefManager.putInt("last_batch_file_count", batchFiles.size());

                // Force immediate save
                prefManager.flush();

                LOGGER.debug("File preferences saved and flushed");
            } catch (Exception e) {
                LOGGER.error("Failed to save file preferences", e);
            }
        }
    }
    
    /**
     * Updates the processing state, used to enable/disable the Clear Queue button.
     * @param isProcessing
     */
    public void setIsProcessing(boolean isProcessing) {
        Platform.runLater(() -> {
            this.isProcessingProperty.set(isProcessing);
        });
    }

    // 🚨 FIX P5: Update file adding logic to refresh totals
    public void addFile(File file) {
        BatchFileItem newItem = new BatchFileItem(file);
        
        // Try to get duration
        double duration = getAudioDuration(file);
        if (duration > 0) {
            newItem.setTotalAudioDurationSeconds(duration);
        }
        
        batchFiles.add(newItem);
        updateBatchQueueTotals();
        log("➕ File added to queue: " + file.getName());
    }
    /**
    * Create file table view with proper column headers
    */
   private TableView<BatchFileItem> createFileTableView() {
       TableView<BatchFileItem> tableView = new TableView<>();

       // Enable multiple selection
       tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

       // Set up context menu
       setupContextMenuForTableView(tableView);

       // Set up drag and drop
       setupDragAndDropForTableView(tableView);

       // Set up keyboard shortcuts
       setupKeyboardShortcutsForTableView(tableView);

       // File name column
       TableColumn<BatchFileItem, String> nameColumn = new TableColumn<>("File Name");
       nameColumn.setCellValueFactory(cellData -> 
           new SimpleStringProperty(cellData.getValue().getFile().getName()));
       nameColumn.setPrefWidth(250);
       nameColumn.setCellFactory(col -> new TableCell<BatchFileItem, String>() {
           @Override
           protected void updateItem(String item, boolean empty) {
               super.updateItem(item, empty);
               if (empty || item == null) {
                   setText(null);
               } else {
                   setText(item);
                   // Add tooltip for long filenames
                   setTooltip(new Tooltip(item));
               }
           }
       });

       // Status column with color coding
       TableColumn<BatchFileItem, String> statusColumn = new TableColumn<>("Status");
       statusColumn.setCellValueFactory(cellData -> {
           String status = cellData.getValue().getStatus();
           if (status == null || status.isEmpty()) {
               status = "PENDING";
           }
           return new SimpleStringProperty(status);
       });
       statusColumn.setPrefWidth(100);
       statusColumn.setCellFactory(col -> new TableCell<BatchFileItem, String>() {
           @Override
           protected void updateItem(String item, boolean empty) {
               super.updateItem(item, empty);
               if (empty || item == null) {
                   setText(null);
                   setStyle("");
               } else {
                   setText(item);
                   // Color code based on status
                   if ("COMPLETED".equals(item)) {
                       setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                   } else if ("PROCESSING".equals(item)) {
                       setStyle("-fx-text-fill: #2196F3; -fx-font-weight: bold;");
                   } else if ("FAILED".equals(item)) {
                       setStyle("-fx-text-fill: #F44336; -fx-font-weight: bold;");
                   } else {
                       setStyle("-fx-text-fill: #666;");
                   }
               }
           }
       });

       // Size column
       TableColumn<BatchFileItem, String> sizeColumn = new TableColumn<>("Size");
       sizeColumn.setCellValueFactory(cellData -> {
           long fileSize = cellData.getValue().getFile().length();
           return new SimpleStringProperty(formatFileSize(fileSize));
       });
       sizeColumn.setPrefWidth(80);

       // Duration column
       TableColumn<BatchFileItem, String> durationColumn = new TableColumn<>("Duration");
       // FIX: was cellData.getValue().getDurationSeconds() — never populated
       // by any setter in the codebase (see fix notes above), so this
       // column always showed "0s" regardless of the file's real length.
       // getTotalAudioDurationSeconds() is set right after ffprobe / the
       // file-size-based estimate completes (see setFile()/addFile()).
       durationColumn.setCellValueFactory(cellData -> 
           new SimpleStringProperty(formatDuration((long) cellData.getValue().getTotalAudioDurationSeconds())));
       durationColumn.setPrefWidth(90);

       // Progress column with progress bar
       TableColumn<BatchFileItem, Double> progressColumn = new TableColumn<>("Progress");
       progressColumn.setCellValueFactory(cellData -> {
           Double progress = cellData.getValue().getProgress();
           return new SimpleObjectProperty<>(progress != null ? progress : 0.0);
       });
       progressColumn.setPrefWidth(120);
       progressColumn.setCellFactory(col -> new TableCell<BatchFileItem, Double>() {
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

                   // Color code progress bar based on status
                   BatchFileItem item = getTableView().getItems().get(getIndex());
                   String status = item.getStatus();
                   if ("COMPLETED".equals(status)) {
                       setStyled(progressBar, "-fx-accent: #4CAF50;");
                   } else if ("PROCESSING".equals(status)) {
                       setStyled(progressBar, "-fx-accent: #2196F3;");
                   } else if ("FAILED".equals(status)) {
                       setStyled(progressBar, "-fx-accent: #F44336;");
                   } else {
                       setStyled(progressBar, "-fx-accent: #666;");
                   }

                   setGraphic(container);
                   setText(null);
               }
           }
       });

       tableView.getColumns().addAll(nameColumn, statusColumn, sizeColumn, durationColumn, progressColumn);

       // Make table fill available space
       tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

       return tableView;
   }

   /**
    * Set up context menu for TableView
    */
   private void setupContextMenuForTableView(TableView<BatchFileItem> tableView) {
       ContextMenu contextMenu = new ContextMenu();

       MenuItem removeSelectedItem = new MenuItem("Remove Selected");
       removeSelectedItem.setOnAction(e -> removeSelectedFilesFromTableView(tableView));

       MenuItem removeAllItem = new MenuItem("Remove All");
       removeAllItem.setOnAction(e -> clearAllFiles());

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

       contextMenu.getItems().addAll(
           removeSelectedItem,
           removeAllItem,
           new SeparatorMenuItem(),
           moveToTopItem,
           moveToBottomItem,
           new SeparatorMenuItem(),
           priorityMenu,
           new SeparatorMenuItem(),
           selectAllItem
       );

       tableView.setContextMenu(contextMenu);
   }

   /**
    * Set priority on every currently-selected row. Only affects scheduling
    * order for files still PENDING when the next batch starts — priority
    * doesn't preempt a file already mid-processing.
    */
   private void setSelectedPriority(TableView<BatchFileItem> tableView, BatchFileItem.Priority priority) {
       List<BatchFileItem> selected = new ArrayList<>(tableView.getSelectionModel().getSelectedItems());
       for (BatchFileItem item : selected) {
           item.setPriority(priority);
       }
       if (!selected.isEmpty()) {
           log("🎯 Set priority to " + priority + " for " + selected.size() + " file(s)");
           tableView.refresh();
       }
   }

   /**
    * Set up drag and drop for TableView
    */
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
                       log("↕️ Reordered file in queue");
                   }
                   event.setDropCompleted(true);
                   event.consume();
               }
           });

           return row;
       });
   }

   /**
    * Set up keyboard shortcuts for TableView
    */
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
               // Common alternate redo binding (Windows convention).
               redo();
               event.consume();
           }
       });
   }

   /**
    * Remove selected files from TableView
    */
   private void removeSelectedFilesFromTableView(TableView<BatchFileItem> tableView) {
       ObservableList<BatchFileItem> selectedItems = tableView.getSelectionModel().getSelectedItems();
       if (!selectedItems.isEmpty()) {
           // FIX (undo/redo): capture each removed item's original index so
           // undo can restore exact positions, not just append them back at
           // the end (which would silently reorder the queue on undo).
           List<BatchFileItem> removed = new ArrayList<>(selectedItems);
           List<Integer> originalIndices = new ArrayList<>();
           for (BatchFileItem item : removed) {
               originalIndices.add(batchFiles.indexOf(item));
           }

           batchFiles.removeAll(removed);
           pushCommand(new QueueCommand() {
               @Override public void undo() {
                   // Re-insert in ascending index order so earlier insertions
                   // don't shift the target index for later ones.
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
           log("🗑️ Removed " + selectedItems.size() + " file(s) from queue");
           updateBatchQueueTotals();
       }
   }

   /**
    * Move selected item to top in TableView
    */
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
           log("⬆️ Moved file to top");
       }
   }

   /**
    * Move selected item to bottom in TableView
    */
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
           log("⬇️ Moved file to bottom");
       }
   }
}