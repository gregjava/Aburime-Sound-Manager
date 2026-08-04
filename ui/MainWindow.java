/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.constants.AppConstants;
import audiomanager.core.*;
import audiomanager.exceptions.FfmpegException;
import audiomanager.model.*;
import audiomanager.plugins.*;
import audiomanager.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.CheckMenuItem;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import javafx.scene.Node;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;

/**
 * Main application window
 */
public class MainWindow implements BatchProcessor.FileCompletionCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindow.class);
    
    private final Stage stage;
    private PreferenceManager prefManager;
    private final DependencyManager dependencyManager;
    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final TimeLeftEstimator timeEstimator;
    private final BatchProcessor batchProcessor;
    
    // UI Components
    private FileSelectionPanel fileSelectionPanel;
    private ConfigurationPanel configurationPanel;
    private ControlPanel controlPanel;
    private TextArea logArea;
    
    // Tools
    private final AudioSplitterTool audioSplitter;
    private final FileCombinerTool fileCombiner;
    
    // State
    private final ObservableList<BatchFileItem> batchFiles;
    private Timeline timeUpdateTimeline;

    // FIX (consolidation): the old parallelBatchRunning/isAnyBatchRunning()
    // workaround and MainWindow's own separate ParallelProcessingManager
    // instance are gone. They existed only because MainWindow used to call
    // parallelManager.processBatchParallel() directly, bypassing
    // batchProcessor entirely, for any batch with max-parallel > 1 — which
    // is why batchProcessor.isProcessing() couldn't be trusted alone. Now
    // that batchProcessor.processBatch() always delegates to its own
    // internal parallelManager (see BatchProcessor.java), there's only ever
    // one thing running a batch, so batchProcessor.isProcessing() /
    // batchProcessor.cancel() are sufficient everywhere again, the same as
    // before parallel processing was added.

    // FIX: added — Total/Completed/Failed/Pending counts need a source of
    // truth that survives items being removed from batchFiles mid-run
    // (auto-remove-completed removes each item the instant it finishes).
    // Tallying "completed" by iterating batchFiles live would undercount
    // the moment a completed item is removed — it'd have already vanished
    // from the list it's being counted from. These identity-keyed sets
    // record "this exact BatchFileItem object was seen as COMPLETED/FAILED"
    // once and keep it counted even after the item itself is gone from the
    // visible queue. originalBatchSize is snapshotted once when a batch
    // starts so "Total"/"Pending" stay meaningful as the queue shrinks.
    private final Set<BatchFileItem> countedCompleted =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BatchFileItem> countedFailed =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int originalBatchSize = 0;

    // FIX (watch-folder feature): tracks the active FolderWatcher (if any) so
    // it can be toggled off from the same menu item and cleanly stopped on exit.
    private FolderWatcher folderWatcher;
    private Thread folderWatcherThread;
    private MenuItem watchFolderMenuItem;
    
    public MainWindow(Stage stage, PreferenceManager prefManager) {
        this.stage = stage;
        this.prefManager = prefManager;

        // Validate preference manager
        if (prefManager == null) {
            LOGGER.error("PreferenceManager is null - creating fallback instance");
            this.prefManager = new PreferenceManager(MainWindow.class);
        }

        // Initialize core services with preference manager
        this.dependencyManager = new DependencyManager();
        this.audioProcessor = new AudioProcessor(dependencyManager);
        this.timeEstimator = new TimeLeftEstimator(10, this.prefManager);
        this.transcriptionService = new WhisperXTranscriptionService(dependencyManager, timeEstimator);

        // Initialize batch files BEFORE batch processor
        this.batchFiles = FXCollections.observableArrayList();
        this.configurationPanel = new ConfigurationPanel(prefManager);

        // Create batch processor (only once)
        this.batchProcessor = new BatchProcessor(
            audioProcessor, 
            transcriptionService, 
            timeEstimator,
            prefManager,
            this::log,
            this,
            batchFiles
        );

        // Initialize tools with logger
        this.audioSplitter = new AudioSplitterTool(dependencyManager, prefManager);
        this.audioSplitter.setLogger(this::log);

        this.fileCombiner = new FileCombinerTool(prefManager);
        this.fileCombiner.setLogger(this::log);

        LOGGER.info("Application components initialized");
    }

    @Override
    public void onFileCompleted(BatchFileItem item, boolean wasSuccessful) {
        Platform.runLater(() -> {
            // FIX: this is the actual root cause of the "done count never
            // updates" bug, which survived an earlier fix to how ControlPanel
            // *consumed* countedCompleted/countedFailed. The producer side was
            // still wrong: countedCompleted/countedFailed used to be populated
            // by scanning batchFiles once a second in the timeline tick below.
            // But removeItemFromBatchQueue() (called a few lines down) removes
            // the item from batchFiles immediately, via its own Platform.runLater
            // — which reliably beats the once-per-second tick to the punch. By
            // the time the tick's scan ran, the completed item was routinely
            // already gone from the list, so it was never added to
            // countedCompleted at all. onFileCompleted() fires exactly once per
            // file, synchronously with the real completion event — count here,
            // not by racing a 1-second poll against an immediate removal.
            if (wasSuccessful) {
                countedCompleted.add(item);
                log("✅ File completed successfully: " + item.getFileName());
                // FIX: was calling removeItemFromBatchQueue(item) unconditionally
                // for both success AND failure. removeItemFromBatchQueue() itself
                // doesn't check status, so a failed file would silently vanish
                // from the queue too whenever auto-remove was on — the opposite
                // of what the setting is for (failed files need to stay visible
                // so they can be investigated/retried).
                fileSelectionPanel.removeItemFromBatchQueue(item);
            } else {
                countedFailed.add(item);
                log("❌ File failed processing: " + item.getFileName());
            }
        });
    }

    public void initialize() {
        configureStage();
        Scene scene = createScene();
        scene.getAccelerators().put(
                javafx.scene.input.KeyCombination.keyCombination("Shortcut+R"),
                this::handleProcessButtonClick);
        stage.setScene(scene);
        configurationPanel.setFontSizeChangeListener(this::applyFontSize);
        loadPreferences();
        debugPreferences();
        applyFontSize(prefManager.getFontSize());
        restoreWindowState();

        // Moved setupTimeUpdater() before restoreBatchQueueState()
        setupTimeUpdater();        // <-- now called earlier

        restoreBatchQueueState();  // may call startBatchProcessing()

        setupEventHandlers();

        if (fileSelectionPanel != null && batchProcessor != null) {
            fileSelectionPanel.getClearQueueButton().disableProperty().bind(
                batchProcessor.isRunningProperty() 
            );
        }

        stage.show();

        CompletableFuture.runAsync(this::checkDependencies);
    }

    private void applyFontSize(double size) {
        LOGGER.debug("Applying font size: {}px", size);

        Scene scene = stage.getScene();
        if (scene != null) {
            String style = String.format("-fx-font-size: %spx;", (int) size);
            scene.getRoot().setStyle("-fx-font-family: 'Segoe UI', 'Roboto', 'Arial'; " + style);
            LOGGER.debug("Applied font size to scene root");
        }

        if (logArea != null) {
            logArea.setStyle(String.format("-fx-font-size: %spx;", (int) size));
            LOGGER.debug("Applied font size to log area");
        }

        applyFontSizeToUIComponents(size);
    }

    /**
     * FIX: previously this called {@code node.setStyle(String.format(
     * "-fx-font-size: %spx;", size))} for every button/label/text-field/
     * text-area in the whole scene. {@code Node.setStyle()} REPLACES the
     * entire inline style string — it doesn't merge with what's already
     * there. Since most buttons throughout this app get their color/shape
     * from an inline style set elsewhere (e.g.
     * {@code "-fx-background-color: #e67e22; -fx-text-fill: white; ..."}),
     * every one of those was being silently wiped back to default Modena
     * styling on every "Apply" in Preferences — for ANY setting, not
     * specifically the log level (that was just what the user happened to
     * be changing when they noticed the whole window revert to plain
     * default-looking buttons). Now it strips only a prior
     * {@code -fx-font-size:...;} rule (if any) from the existing style and
     * appends the new one, leaving every other inline rule untouched.
     */
    private static final java.util.regex.Pattern FONT_SIZE_RULE_PATTERN =
            java.util.regex.Pattern.compile("-fx-font-size:\\s*[^;]+;?\\s*");

    private void applyFontSizeRule(javafx.scene.Node node, double size) {
        String existing = node.getStyle();
        String withoutFontSize = (existing == null || existing.isBlank())
                ? ""
                : FONT_SIZE_RULE_PATTERN.matcher(existing).replaceAll("").trim();
        String separator = withoutFontSize.isEmpty() || withoutFontSize.endsWith(";") ? " " : "; ";
        node.setStyle(withoutFontSize + separator + String.format("-fx-font-size: %spx;", (int) size));
    }

    private void applyFontSizeToUIComponents(double size) {
        try {
            MenuBar menuBar = (MenuBar) stage.getScene().lookup(".menu-bar");
            if (menuBar != null) {
                applyFontSizeRule(menuBar, size);
            }

            stage.getScene().getRoot().lookupAll(".button").forEach(node -> {
                if (node instanceof Button) {
                    applyFontSizeRule(node, size);
                }
            });

            stage.getScene().getRoot().lookupAll(".label").forEach(node -> {
                if (node instanceof Label) {
                    applyFontSizeRule(node, size);
                }
            });

            stage.getScene().getRoot().lookupAll(".text-field").forEach(node -> {
                if (node instanceof TextField) {
                    applyFontSizeRule(node, size);
                }
            });

            stage.getScene().getRoot().lookupAll(".text-area").forEach(node -> {
                if (node instanceof TextArea) {
                    applyFontSizeRule(node, size);
                }
            });

            LOGGER.debug("Applied font size to all UI components");
        } catch (Exception e) {
            LOGGER.warn("Failed to apply font size to some UI components", e);
        }
    }

    private void configureStage() {
        stage.setTitle(AppConstants.APP_TITLE + " - " + AppConstants.APP_SUBTITLE);
        stage.setMinWidth(AppConstants.MIN_WINDOW_WIDTH);
        stage.setMinHeight(AppConstants.MIN_WINDOW_HEIGHT);
        
        try (InputStream iconStream = getClass().getResourceAsStream(AppConstants.ICON_PATH)) {
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            } else {
                LOGGER.warn("Application icon not found");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load application icon", e);
        }
    }

    private Scene createScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(0));

        MenuBar menuBar = createMenuBar();

        fileSelectionPanel = new FileSelectionPanel(
            batchFiles, 
            prefManager,
            audioSplitter,
            fileCombiner,
            this::log
        );

        // FIX (cleanup #4): removed the redundant "Analyze Volume" toolbar
        // button (and its Tools-menu twin, removed below in createMenuBar())
        // per cleanup request — a niche single-file action that cluttered
        // the main file-selection toolbar.

        configurationPanel = new ConfigurationPanel(prefManager);

        controlPanel = new ControlPanel(
            this::handleProcessButtonClick,
            this::handleExitButtonClick,
            timeEstimator
        );

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setWrapText(true);
        logArea.setStyle("-fx-control-inner-background: #2c3e50; -fx-text-fill: white; -fx-font-family: 'Consolas', 'Monaco', monospace;");

        VBox toolsSection = createToolsSection();

        VBox logSection = new VBox(0);
        Label logLabel = new Label("📝 Terminal");
        logLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #2c3e50; -fx-padding: 10 15 5 15;");
        logSection.getChildren().addAll(logLabel, logArea);
        logSection.setStyle("-fx-border-color: #bdc3c7; -fx-border-width: 1 0 0 0; -fx-background-color: white;");

        VBox mainContent = new VBox(0);
        mainContent.getChildren().addAll(
            createStyledFileSelectionSection(),
            toolsSection,
            createStyledBatchQueueSection(),
            createStyledControlSection(),
            logSection
        );

        ScrollPane scrollPane = new ScrollPane(mainContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background-color: #ecf0f1; -fx-border-width: 0;");

        root.setTop(menuBar);
        root.setCenter(scrollPane);

        Scene scene = new Scene(root, AppConstants.DEFAULT_WINDOW_WIDTH, AppConstants.DEFAULT_WINDOW_HEIGHT);
        applyCSSIfAvailable(scene);

        return scene;
    }

    // ========== NEW METHOD: Get selected file from batch queue ==========
    private File getSelectedFile() {
        if (batchFiles.isEmpty()) {
            return null;
        }

        // Get the ListView from FileSelectionPanel
        ListView<BatchFileItem> listView = fileSelectionPanel.getBatchListView();
        if (listView != null) {
            BatchFileItem selectedItem = listView.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                return selectedItem.getFile();
            }
        }

        // If nothing selected but files exist, show choice dialog
        if (batchFiles.size() == 1) {
            return batchFiles.get(0).getFile();
        } else {
            // Let user choose which file to analyze
            ChoiceDialog<BatchFileItem> dialog = new ChoiceDialog<>(batchFiles.get(0), batchFiles);
            dialog.setTitle("Select File");
            dialog.setHeaderText("Multiple files in queue");
            dialog.setContentText("Choose a file to analyze:");

            // Custom renderer to show filenames clearly
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(false);

            Optional<BatchFileItem> result = dialog.showAndWait();
            if (result.isPresent()) {
                return result.get().getFile();
            }
        }

        return null;
    }
    // ========== END NEW METHOD ==========

    // ========== NEW METHOD: Analyze selected file volume ==========
    private void analyzeSelectedFileVolume() {
        File selectedFile = getSelectedFile();
        if (selectedFile == null) {
            log("❌ Please add at least one file to the batch queue first");

            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No File Available");
            alert.setHeaderText("Cannot Analyze Volume");
            alert.setContentText("Please add at least one file to the batch queue before analyzing.");
            alert.showAndWait();
            return;
        }

        try {
            log("🔍 Analyzing volume for: " + selectedFile.getName());

            // Update UI to show analysis in progress
            Platform.runLater(() -> {
                controlPanel.updateProgress(0, 0, 1);
                controlPanel.setProcessingState(true);
                // Access statusLabel from ControlPanel (you may need to add a getter)
                // For now, just use the log
            });

            // Run analysis in background to prevent UI freeze
            CompletableFuture.supplyAsync(() -> {
                try {
                    return audioProcessor.analyzeVolume(selectedFile.getAbsolutePath());
                } catch (FfmpegException e) {
                    // FIX: previously wrapped as a bare RuntimeException, losing the
                    // FfmpegException's userMessage()/getStderrTail() by the time
                    // .exceptionally() below ran. CompletableFuture's Supplier can't
                    // declare a checked exception, so this wrap is unavoidable, but
                    // wrapping the typed exception itself (not just its message)
                    // lets .exceptionally() unwrap and show the specific FFmpeg
                    // failure message instead of a generic one.
                    throw new CompletionException(e);
                }
            }).thenAccept(analysis -> {
                Platform.runLater(() -> {
                    // Restore UI state
                    controlPanel.setProcessingState(false);
                    controlPanel.updateProgress(0, 0, 0);

                    // Show results in dialog
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Volume Analysis Results");
                    alert.setHeaderText("Volume Analysis for: " + selectedFile.getName());

                    // Create expandable content for detailed view
                    TextArea textArea = new TextArea(audioProcessor.formatVolumeAnalysis(analysis));
                    textArea.setEditable(false);
                    textArea.setWrapText(true);
                    textArea.setPrefWidth(500);
                    textArea.setPrefHeight(300);

                    alert.getDialogPane().setContent(textArea);
                    alert.getDialogPane().setPrefSize(550, 400);

                    // Add action buttons
                    ButtonType closeButton = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);

                    // If amplification is needed, add an "Apply Gain" button
                    if (analysis.needsAmplification()) {
                        ButtonType applyGainButton = new ButtonType(
                            "Apply " + String.format("%.1f", analysis.getRecommendedGain()) + "dB Gain", 
                            ButtonBar.ButtonData.OK_DONE);
                        alert.getButtonTypes().setAll(applyGainButton, closeButton);

                        alert.setResultConverter(buttonType -> {
                            if (buttonType == applyGainButton) {
                                applyRecommendedGain(selectedFile, analysis.getRecommendedGain());
                            }
                            return null;
                        });
                    } else {
                        alert.getButtonTypes().setAll(closeButton);
                    }

                    alert.showAndWait();

                    log("✅ Volume analysis complete for: " + selectedFile.getName());
                });
            }).exceptionally(ex -> {
                Platform.runLater(() -> {
                    // Restore UI state on error
                    controlPanel.setProcessingState(false);
                    controlPanel.updateProgress(0, 0, 0);

                    LOGGER.error("Volume analysis failed", ex);
                    log("❌ Volume analysis failed: " + ex.getMessage());
                    showFfmpegAwareErrorAlert("Analysis Failed", "Volume Analysis Failed", ex);
                });
                return null;
            });
        } catch (Exception ex) {
            LOGGER.error("Volume analysis failed", ex);
            log("❌ Volume analysis failed: " + ex.getMessage());

            Platform.runLater(() -> {
                controlPanel.setProcessingState(false);
                controlPanel.updateProgress(0, 0, 0);
            });
        }
    }
    // ========== END NEW METHOD ==========

    // ========== NEW METHOD: Apply recommended gain ==========
    private void applyRecommendedGain(File file, double gainDb) {
        try {
            log("🔊 Applying " + String.format("%.1f", gainDb) + "dB gain to: " + file.getName());

            // Update UI to show amplification in progress
            Platform.runLater(() -> {
                controlPanel.setProcessingState(true);
                // Update detailed status if available
            });

            // Run amplification in background
            CompletableFuture.supplyAsync(() -> {
                try {
                    return audioProcessor.amplifyAudio(file.getAbsolutePath(), gainDb);
                } catch (FfmpegException e) {
                    // See analyzeSelectedFileVolume() above for why this wrap is
                    // necessary (Supplier can't declare checked exceptions) and
                    // why it preserves the typed exception rather than discarding it.
                    throw new CompletionException(e);
                }
            }).thenAccept(outputPath -> {
                Platform.runLater(() -> {
                    // Restore UI state
                    controlPanel.setProcessingState(false);

                    log("✅ Amplification complete: " + outputPath);

                    // Ask if user wants to add amplified file to batch queue
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("Add to Batch Queue?");
                    alert.setHeaderText("Amplification Complete");
                    alert.setContentText("Add the amplified file to the batch queue?");

                    ButtonType yesButton = new ButtonType("Yes", ButtonBar.ButtonData.YES);
                    ButtonType noButton = new ButtonType("No", ButtonBar.ButtonData.NO);
                    alert.getButtonTypes().setAll(yesButton, noButton);

                    Optional<ButtonType> result = alert.showAndWait();
                    if (result.isPresent() && result.get() == yesButton) {
                        File amplifiedFile = new File(outputPath);
                        BatchFileItem newItem = new BatchFileItem(amplifiedFile);
                        batchFiles.add(newItem);
                        log("➕ Added amplified file to batch queue: " + amplifiedFile.getName());

                        // Update the batch queue display
                        fileSelectionPanel.updateBatchStatus(batchFiles);
                    }
                });
            }).exceptionally(ex -> {
                Platform.runLater(() -> {
                    // Restore UI state on error
                    controlPanel.setProcessingState(false);

                    LOGGER.error("Amplification failed", ex);
                    log("❌ Amplification failed: " + ex.getMessage());
                    showFfmpegAwareErrorAlert("Amplification Failed", "Could Not Amplify File", ex);
                });
                return null;
            });

        } catch (Exception ex) {
            LOGGER.error("Amplification failed", ex);
            log("❌ Amplification failed: " + ex.getMessage());

            Platform.runLater(() -> {
                controlPanel.setProcessingState(false);
            });
        }
    }
    // ========== END NEW METHOD ==========

    private VBox createToolsSection() {
        VBox toolsContainer = new VBox(0);
        toolsContainer.setStyle("-fx-background-color: white; -fx-border-color: #bdc3c7; -fx-border-width: 1 0 1 0;");

        TitledPane splitterPane = createAudioSplitterPane();
        TitledPane combinerPane = createTextFileCombinerPane();

        toolsContainer.getChildren().addAll(splitterPane, combinerPane);

        return toolsContainer;
    }

    private TitledPane createAudioSplitterPane() {
        TitledPane pane = new TitledPane();
        pane.setText("🎵 Audio Splitter");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        Node splitterUI = audioSplitter.createUI();

        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(splitterUI);
        contentContainer.setMinHeight(320);
        contentContainer.setPrefHeight(320);

        pane.setContent(contentContainer);
        pane.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0; -fx-background-color: #f8f9fa;");

        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded) {
                if (pane.getParent() instanceof VBox parent) {
                    parent.getChildren().forEach(node -> {
                        if (node instanceof TitledPane otherPane && otherPane != pane) {
                            otherPane.setExpanded(false);
                        }
                    });
                }
            }
        });

        return pane;
    }

    private TitledPane createTextFileCombinerPane() {
        TitledPane pane = new TitledPane();
        pane.setText("📄 Text File Combiner");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        Node combinerUI = fileCombiner.createUI();

        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(combinerUI);
        contentContainer.setMinHeight(270);
        contentContainer.setPrefHeight(270);

        pane.setContent(contentContainer);
        pane.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 0; -fx-background-color: #f8f9fa;");

        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded) {
                if (pane.getParent() instanceof VBox parent) {
                    parent.getChildren().forEach(node -> {
                        if (node instanceof TitledPane otherPane && otherPane != pane) {
                            otherPane.setExpanded(false);
                        }
                    });
                }
            }
        });

        return pane;
    }

    private VBox createStyledFileSelectionSection() {
        VBox fileSelection = fileSelectionPanel.getFileSelectionControls();
        fileSelection.setStyle("-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; -fx-padding: 15; -fx-background-color: white;");
        return fileSelection;
    }

    private VBox createStyledBatchQueueSection() {
        VBox batchQueue = fileSelectionPanel.getBatchQueueSection();
        batchQueue.setStyle("-fx-padding: 10; -fx-background-color: white;");
        return batchQueue;
    }

    private VBox createStyledControlSection() {
        VBox controlSection = controlPanel.getRoot();
        controlSection.setStyle("-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; -fx-background-color: white;");
        return controlSection;
    }

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.setStyle("-fx-padding: 0; -fx-background-color: #ecf0f1; -fx-border-width: 0 0 1 0; -fx-border-color: #bdc3c7;");

        Menu fileMenu = new Menu("File");
        fileMenu.setStyle("-fx-text-fill: #2c3e50;");

        MenuItem preferencesItem = new MenuItem("Preferences...");
        preferencesItem.setOnAction(e -> showPreferencesDialog());
        preferencesItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+COMMA"));

        MenuItem clearSessionItem = new MenuItem("Clear Session Data");
        clearSessionItem.setOnAction(e -> clearSessionData());

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> handleExitButtonClick());
        exitItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Q"));

        fileMenu.getItems().addAll(preferencesItem, clearSessionItem, new SeparatorMenuItem(), exitItem);

        // FIX (UI improvement — undo/redo menu): mirrors the Ctrl+Z/Ctrl+Shift+Z
        // shortcuts already wired into the file queue's TableView
        // (FileSelectionPanel.setupKeyboardShortcutsForTableView); this gives
        // mouse-driven access to the same commands, and doubles as
        // documentation of the shortcut for anyone who doesn't discover it
        // by trying Ctrl+Z.
        Menu editMenu = new Menu("Edit");
        editMenu.setStyle("-fx-text-fill: #2c3e50;");

        MenuItem undoItem = new MenuItem("Undo");
        undoItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Z"));
        undoItem.setOnAction(e -> {
            if (fileSelectionPanel != null && !fileSelectionPanel.undo()) {
                log("Nothing to undo.");
            }
        });

        MenuItem redoItem = new MenuItem("Redo");
        redoItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+Z"));
        redoItem.setOnAction(e -> {
            if (fileSelectionPanel != null && !fileSelectionPanel.redo()) {
                log("Nothing to redo.");
            }
        });

        editMenu.getItems().addAll(undoItem, redoItem);

        Menu toolsMenu = new Menu("Tools");
        toolsMenu.setStyle("-fx-text-fill: #2c3e50;");

        MenuItem batchSettingsItem = new MenuItem("Batch Processing Settings...");
        batchSettingsItem.setOnAction(e -> showBatchSettingsDialog());
        batchSettingsItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+B"));

        MenuItem whisperSettingsItem = new MenuItem("Transcription Settings...");
        whisperSettingsItem.setOnAction(e -> showWhisperSettingsDialog());

        MenuItem audioSettingsItem = new MenuItem("Audio Processing Settings...");
        audioSettingsItem.setOnAction(e -> showAudioSettingsDialog());

        MenuItem clearTimeDataItem = new MenuItem("Clear Time Estimation Data");
        clearTimeDataItem.setOnAction(e -> clearTimeEstimationData());

        watchFolderMenuItem = new MenuItem("📁 Watch Folder...");
        watchFolderMenuItem.setOnAction(e -> toggleFolderWatch());
        watchFolderMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+W"));

        MenuItem performanceReportItem = new MenuItem("📊 Performance Report...");
        performanceReportItem.setOnAction(e -> showPerformanceReportDialog());
        performanceReportItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+P"));

        toolsMenu.getItems().addAll(
            batchSettingsItem, whisperSettingsItem, audioSettingsItem,
            new SeparatorMenuItem(), clearTimeDataItem,
            new SeparatorMenuItem(), watchFolderMenuItem, performanceReportItem
        );

        // FIX (UI improvement — dark mode): a best-effort dark theme. This
        // app styles most components with inline setStyle(...) calls rather
        // than CSS style-class selectors, and inline styles take priority
        // over stylesheet rules in JavaFX — so this stylesheet reliably
        // re-themes the scene background, MenuBar, TextArea/log, and any
        // component that doesn't set its own inline background/text-fill,
        // but can't override every hardcoded color throughout ControlPanel/
        // FileSelectionPanel without migrating those to CSS classes (a
        // larger, separate refactor). Treat this as a real but partial dark
        // mode, not full coverage — panels with heavy inline styling (the
        // Queue table, colored status buttons) will keep their light-theme
        // colors for now.
        Menu viewMenu = new Menu("View");
        viewMenu.setStyle("-fx-text-fill: #2c3e50;");

        CheckMenuItem darkModeItem = new CheckMenuItem("🌙 Dark Mode");
        darkModeItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+D"));
        darkModeItem.setSelected("Dark".equals(prefManager.getTheme()));
        darkModeItem.setOnAction(e -> toggleTheme(darkModeItem.isSelected()));

        viewMenu.getItems().add(darkModeItem);

        Menu helpMenu = new Menu("Help");
        helpMenu.setStyle("-fx-text-fill: #2c3e50;");

        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAboutDialog());

        MenuItem dependenciesItem = new MenuItem("Check Dependencies");
        dependenciesItem.setOnAction(e -> checkDependencies());
        dependenciesItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("F5"));

        MenuItem setupAssistantItem = new MenuItem("Setup Assistant...");
        setupAssistantItem.setOnAction(e -> showSetupAssistantDialog());

        MenuItem userManualItem = new MenuItem("User Manual...");
        userManualItem.setOnAction(e -> new audiomanager.ui.DocumentationLauncher().open("USER_MANUAL.md"));

        MenuItem troubleshootingItem = new MenuItem("Troubleshooting Guide...");
        troubleshootingItem.setOnAction(e -> new audiomanager.ui.DocumentationLauncher().open("TROUBLESHOOTING.md"));
        troubleshootingItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("F1"));

        helpMenu.getItems().addAll(aboutItem, dependenciesItem, setupAssistantItem,
                new SeparatorMenuItem(), userManualItem, troubleshootingItem);

        menuBar.getMenus().addAll(fileMenu, editMenu, toolsMenu, viewMenu, helpMenu);

        return menuBar;
    }

    /**
     * Toggles the dark stylesheet on the current scene and persists the
     * choice via PreferenceManager so it's restored on next launch. See the
     * "FIX (UI improvement — dark mode)" note above createMenuBar() for the
     * honest scope of what this does and doesn't re-theme.
     */
    private void toggleTheme(boolean dark) {
        Scene scene = stage.getScene();
        if (scene == null) return;

        String stylesheetUri = getClass().getResource("/styles/dark.css") != null
                ? getClass().getResource("/styles/dark.css").toExternalForm()
                : null;

        if (dark) {
            if (stylesheetUri != null && !scene.getStylesheets().contains(stylesheetUri)) {
                scene.getStylesheets().add(stylesheetUri);
            } else if (stylesheetUri == null) {
                LOGGER.warn("dark.css not found on the classpath at /styles/dark.css — dark mode stylesheet not applied. " +
                        "Make sure dark.css is placed under src/main/resources/styles/.");
            }
            prefManager.setTheme("Dark");
        } else {
            if (stylesheetUri != null) {
                scene.getStylesheets().remove(stylesheetUri);
            }
            prefManager.setTheme("Light");
        }
        prefManager.flush();
        LOGGER.info("Theme set to: {}", prefManager.getTheme());
    }

    private void clearSessionData() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Clear Session Data");
        alert.setHeaderText("Clear Current Session");
        alert.setContentText("This will clear the current batch queue and reset the application state. Continue?");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            batchFiles.clear();

            if (prefManager != null) {
                try {
                    prefManager.remove("batch_queue_files");
                    prefManager.remove("last_processing_state");
                    prefManager.remove("processing_state_timestamp");
                    prefManager.flush();
                } catch (Exception e) {
                    LOGGER.error("Failed to clear session data from preferences", e);
                }
            }

            if (fileSelectionPanel != null) {
                fileSelectionPanel.updateBatchStatus(batchFiles);
            }
            if (controlPanel != null) {
                controlPanel.updateProgress(0, 0, 0);
            }

            log("🧹 Session data cleared");
        }
    }

    private void clearTimeEstimationData() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Clear Time Estimation Data");
        alert.setHeaderText("Clear Machine Learning Data");
        alert.setContentText("""
                             This will reset all learned time estimates to default values. This action cannot be undone.
                             
                             Are you sure you want to continue?""");
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            if (timeEstimator != null) {
                timeEstimator.clearSavedData();
                log("🧹 Time estimation data cleared - using default estimates");
            }
        }
    }

    /**
     * Start or stop watching a folder for newly-created audio files. Files
     * dropped into the watched folder are automatically added to the batch
     * queue (not auto-started — the user still presses "Start Processing").
     */
    private void toggleFolderWatch() {
        if (folderWatcher != null) {
            stopFolderWatch();
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Folder to Watch");
        File dir = chooser.showDialog(stage);
        if (dir == null) return;

        try {
            folderWatcher = new FolderWatcher(dir.getAbsolutePath(), file ->
                    Platform.runLater(() -> {
                        fileSelectionPanel.addFile(file);
                        log("📁 Watch folder: added new file " + file.getName());
                    }));
            folderWatcherThread = new Thread(folderWatcher, "FolderWatcher");
            folderWatcherThread.setDaemon(true);
            folderWatcherThread.start();

            watchFolderMenuItem.setText("📁 Stop Watching Folder");
            log("📁 Watching folder for new files: " + dir.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to start folder watcher", e);
            log("❌ Could not watch folder: " + e.getMessage());
            folderWatcher = null;
        }
    }

    /** Stop the active folder watcher, if any. Safe to call even if nothing is being watched. */
    private void stopFolderWatch() {
        if (folderWatcher == null && folderWatcherThread == null) {
            return;
        }
        if (folderWatcher != null) {
            folderWatcher.stop();
            folderWatcher = null;
        }
        if (folderWatcherThread != null) {
            folderWatcherThread.interrupt();
            folderWatcherThread = null;
        }
        if (watchFolderMenuItem != null) {
            watchFolderMenuItem.setText("📁 Watch Folder...");
        }
        log("📁 Stopped watching folder");
    }
    
    private void showPreferencesDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Preferences");
        dialog.setHeaderText("User Interface Settings");

        configurationPanel.refreshAllComponents();

        VBox uiSection = configurationPanel.createUISection();

        ButtonType applyButton = new ButtonType("Apply", ButtonBar.ButtonData.APPLY);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButton, cancelButton);

        ScrollPane scrollPane = new ScrollPane(uiSection);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(150);
        dialog.getDialogPane().setContent(scrollPane);

        applyFontSizeToDialog(dialog, prefManager.getFontSize());

        dialog.showAndWait().ifPresent(result -> {
            if (result == applyButton) {
                configurationPanel.savePreferences();
                double newFontSize = prefManager.getFontSize();
                applyFontSize(newFontSize);
                log("✅ UI preferences updated - Font size: " + (int)newFontSize + "px");
            }
        });
    }

    private void applyFontSizeToDialog(Dialog<?> dialog, double size) {
        Platform.runLater(() -> {
            try {
                String style = String.format("-fx-font-size: %spx;", (int) size);
                dialog.getDialogPane().setStyle(style);
            } catch (Exception e) {
                LOGGER.warn("Failed to apply font size to dialog", e);
            }
        });
    }

    private void showBatchSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Batch Processing Settings");
        dialog.setHeaderText("Configure batch processing behavior");

        configurationPanel.refreshAllComponents();

        VBox batchSection = configurationPanel.createBatchSection();

        ButtonType applyButton = new ButtonType("Apply", ButtonBar.ButtonData.APPLY);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButton, cancelButton);

        ScrollPane scrollPane = new ScrollPane(batchSection);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(120);
        dialog.getDialogPane().setContent(scrollPane);

        dialog.showAndWait().ifPresent(result -> {
            if (result == applyButton) {
                configurationPanel.savePreferences();
                log("✅ Batch processing settings updated");
            }
        });
    }

    private void showWhisperSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Transcription Settings");
        dialog.setHeaderText("Configure Whisper transcription parameters");

        configurationPanel.refreshAllComponents();

        VBox whisperSection = configurationPanel.createWhisperSection();

        ButtonType applyButton = new ButtonType("Apply", ButtonBar.ButtonData.APPLY);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButton, cancelButton);

        ScrollPane scrollPane = new ScrollPane(whisperSection);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(200);
        dialog.getDialogPane().setContent(scrollPane);

        dialog.showAndWait().ifPresent(result -> {
            if (result == applyButton) {
                configurationPanel.savePreferences();
                log("✅ Transcription settings updated");
            }
        });
    }
    
    private void showAudioSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Audio Processing Settings");
        dialog.setHeaderText("Configure FFmpeg audio processing parameters");

        configurationPanel.refreshAllComponents();

        VBox audioSection = configurationPanel.createAudioSection();

        ButtonType applyButton = new ButtonType("Apply", ButtonBar.ButtonData.APPLY);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButton, cancelButton);

        ScrollPane scrollPane = new ScrollPane(audioSection);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(300);
        dialog.getDialogPane().setContent(scrollPane);

        dialog.showAndWait().ifPresent(result -> {
            if (result == applyButton) {
                configurationPanel.savePreferences();
                log("✅ Audio processing settings updated");
            }
        });
    }
    
    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Studio Audio Manager");
        alert.setHeaderText("Studio Audio Manager v3.9");
        alert.setContentText("""
                             A comprehensive audio processing tool with transcription capabilities.
                             
                             Features:
                             \u2022 Batch audio file processing
                             \u2022 Whisper transcription integration
                             \u2022 Audio splitting and combining tools
                             \u2022 Real-time progress tracking
                             \u2022 Volume analysis and optimization
                             
                             Built with JavaFX and FFmpeg""");
        alert.showAndWait();
    }

    /**
     * A guided, non-technical way to set the HuggingFace token needed for
     * speaker diarisation — the alternative today is knowing to set an
     * HF_TOKEN environment variable or hand-write a dotfile, which is a
     * developer-shaped setup step in an otherwise end-user-facing app.
     * Writes to the same {@code ~/.audiomanager/hf_token} file
     * {@code WhisperXTranscriptionService} already reads from, so no other
     * wiring is needed for a saved token to take effect (on next launch, or
     * immediately for any file not yet started).
     */
    private void showSetupAssistantDialog() {
        Path tokenFile = Paths.get(System.getProperty("user.home"), ".audiomanager", "hf_token");
        boolean tokenFileExists = Files.exists(tokenFile);
        boolean envTokenSet = System.getenv("HF_TOKEN") != null;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Setup Assistant");
        dialog.setHeaderText("Guided setup for speaker diarisation and dependencies");

        VBox content = new VBox(12);
        content.setPadding(new Insets(10));

        Label ffmpegStatus = new Label("Checking FFmpeg...");
        Label whisperStatus = new Label("Checking Whisper...");
        CompletableFuture.runAsync(() -> {
            DependencyManager.DependencyStatus ffmpeg = dependencyManager.checkFFmpeg();
            DependencyManager.DependencyStatus whisper = dependencyManager.checkWhisper();
            Platform.runLater(() -> {
                ffmpegStatus.setText((ffmpeg.isAvailable() ? "✅ " : "❌ ") + ffmpeg.getMessage());
                whisperStatus.setText((whisper.isAvailable() ? "✅ " : "❌ ") + whisper.getMessage());
            });
        });

        Label hfLabel = new Label("HuggingFace Token (for speaker diarisation):");
        hfLabel.setStyle("-fx-font-weight: bold;");

        String currentStateText = envTokenSet
                ? "✅ Currently set via the HF_TOKEN environment variable."
                : tokenFileExists
                    ? "✅ Currently set via ~/.audiomanager/hf_token."
                    : "❌ Not set — speaker diarisation is disabled.";
        Label hfCurrentState = new Label(currentStateText);

        PasswordField tokenField = new PasswordField();
        tokenField.setPromptText("Paste your HuggingFace access token here...");
        tokenField.setPrefWidth(350);

        Label hfHint = new Label(
                "Get a token at huggingface.co/settings/tokens, accept the pyannote model terms, "
                        + "then paste it here. It's saved locally to ~/.audiomanager/hf_token — never "
                        + "transmitted anywhere by this app except to HuggingFace itself during diarisation.");
        hfHint.setWrapText(true);
        hfHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        Button saveTokenButton = new Button("Save Token");
        Label saveResultLabel = new Label();
        saveTokenButton.setOnAction(e -> {
            String token = tokenField.getText();
            if (token == null || token.isBlank()) {
                saveResultLabel.setText("❌ Enter a token first.");
                saveResultLabel.setStyle("-fx-text-fill: #d32f2f;");
                return;
            }
            try {
                Files.createDirectories(tokenFile.getParent());
                Files.writeString(tokenFile, token.trim());
                saveResultLabel.setText("✅ Saved. Takes effect for new transcriptions.");
                saveResultLabel.setStyle("-fx-text-fill: #2e7d32;");
                log("🔑 HuggingFace token saved to " + tokenFile);
                tokenField.clear();
            } catch (IOException ex) {
                LOGGER.error("Failed to save HF token", ex);
                saveResultLabel.setText("❌ Could not save: " + ex.getMessage());
                saveResultLabel.setStyle("-fx-text-fill: #d32f2f;");
            }
        });

        content.getChildren().addAll(
                new Label("Dependency Status:") {{ setStyle("-fx-font-weight: bold;"); }},
                ffmpegStatus, whisperStatus,
                new Separator(),
                hfLabel, hfCurrentState, tokenField, saveTokenButton, saveResultLabel, hfHint
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefSize(450, 400);
        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        dialog.showAndWait();
    }

    /**
     * Build and show an error alert that recognizes {@link FfmpegException}
     * specifically: uses its user-friendly message plus (if recognized) a
     * specific hint for its exit code, with the raw stderr tail tucked into
     * an expandable "Show Details" section rather than dumped straight into
     * the main dialog text. Falls back to a generic message for any other
     * exception type. Previously this logic was duplicated between the
     * volume-analysis and amplification error handlers with no exit-code
     * hint or stderr detail at all — just {@code getUserMessage()}.
     */
    private void showFfmpegAwareErrorAlert(String title, String header, Throwable ex) {
        Throwable cause = ex.getCause();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);

        if (cause instanceof FfmpegException fe) {
            String hint = fe.getExitCodeHint();
            String message = fe.getUserMessage() + (hint != null ? "\n\n" + hint : "");
            alert.setContentText(message);

            if (fe.getStderrTail() != null && !fe.getStderrTail().isBlank()) {
                TextArea detailsArea = new TextArea(fe.getStderrTail());
                detailsArea.setEditable(false);
                detailsArea.setWrapText(true);
                detailsArea.setPrefSize(500, 200);
                Label detailsLabel = new Label("FFmpeg output (exit code "
                        + (fe.getExitCode() >= 0 ? fe.getExitCode() : "unknown") + "):");
                VBox expandableContent = new VBox(5, detailsLabel, detailsArea);
                alert.getDialogPane().setExpandableContent(expandableContent);
            }
        } else {
            alert.setContentText(cause != null ? cause.getMessage() : ex.getMessage());
        }

        alert.showAndWait();
    }

    /**
     * Show a table of recent per-file stage-timing reports — model load,
     * audio load, transcription, alignment, diarization (from the Python
     * script's STAGE_TIMING lines), plus preprocessing/output-saving/total
     * (measured in Java) and a peak-heap/CPU snapshot. This is the "surfaced
     * in the UI, not just the log file" requirement: previously this data
     * either didn't exist at all (no Python-side timing instrumentation) or
     * only ever reached a human as scattered log lines.
     */
    private void showPerformanceReportDialog() {
        new audiomanager.ui.PerformanceReportDialog().show(batchProcessor.getRecentTimingReports());
    }

    private void applyCSSIfAvailable(Scene scene) {
        String defaultStyle = "-fx-font-family: 'Segoe UI', 'Roboto', 'Arial';";
        scene.getRoot().setStyle(defaultStyle);
        
        try {
            String cssPath = getClass().getResource(AppConstants.CSS_PATH).toExternalForm();
            scene.getStylesheets().add(cssPath);
        } catch (Exception e) {
            LOGGER.debug("CSS file not found, using default styling");
        }
    }

    private void restoreWindowState() {
        PreferenceManager.WindowState state = prefManager.getWindowState();
        
        if (state.hasPosition()) {
            stage.setX(state.getX());
            stage.setY(state.getY());
        }
        
        stage.setWidth(state.getWidth());
        stage.setHeight(state.getHeight());
    }

    private void setupEventHandlers() {
        stage.setOnCloseRequest(e -> {
            if (batchProcessor.isProcessing()) {
                e.consume();
                handleExitButtonClick();
            } else {
                saveApplicationState();
            }
        });
    }

    private void saveApplicationState() {
        LOGGER.info("Saving application state...");

        stopFolderWatch();

        try {
            savePreferences();

            if (timeEstimator != null) {
                try {
                    timeEstimator.saveData();
                    LOGGER.info("Time estimation data saved");
                } catch (Exception e) {
                    LOGGER.error("Failed to save time estimation data", e);
                }
            }

            saveWindowState();

            if (prefManager != null) {
                prefManager.flush();
                LOGGER.info("All preferences flushed to disk");
            }

            LOGGER.info("Application state saved successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to save application state", e);
        } finally {
            Platform.exit();
        }
    }

    private void savePreferences() {
        LOGGER.info("Starting preference save process...");

        if (configurationPanel != null) {
            try {
                configurationPanel.savePreferences();
                LOGGER.info("Configuration panel preferences saved");
            } catch (Exception e) {
                LOGGER.error("Failed to save configuration panel preferences", e);
            }
        }

        if (fileSelectionPanel != null) {
            try {
                fileSelectionPanel.saveFilePreferences();
                LOGGER.info("File selection preferences saved");
            } catch (Exception e) {
                LOGGER.error("Failed to save file selection preferences", e);
            }
        }

        if (batchFiles != null && !batchFiles.isEmpty()) {
            try {
                saveBatchQueueState();
                LOGGER.info("Batch queue state saved");
            } catch (Exception e) {
                LOGGER.error("Failed to save batch queue state", e);
            }
        }

        if (prefManager != null) {
            try {
                prefManager.flush();
                LOGGER.info("Preferences flushed to disk");
            } catch (Exception e) {
                LOGGER.error("Failed to flush preferences", e);
            }
        }
    }

    private void saveBatchQueueState() {
        try {
            StringBuilder fileList = new StringBuilder();
            for (BatchFileItem item : batchFiles) {
                if (fileList.length() > 0) {
                    fileList.append(";");
                }
                fileList.append(item.getFile().getAbsolutePath());
            }
            prefManager.putString("batch_queue_files", fileList.toString());
            LOGGER.debug("Saved batch queue with {} files", batchFiles.size());
        } catch (Exception e) {
            LOGGER.error("Failed to save batch queue state", e);
        }
    }

    private void restoreBatchQueueState() {
        try {
            String savedFiles = prefManager.getString("batch_queue_files", "");
            if (!savedFiles.isEmpty()) {
                String[] filePaths = savedFiles.split(";");
                int restoredCount = 0;

                for (String filePath : filePaths) {
                    File file = new File(filePath);
                    if (file.exists() && file.isFile()) {
                        BatchFileItem item = new BatchFileItem(file);
                        // FIX: restored files never had duration probed at
                        // all — this loop only ever reconstructed a bare
                        // BatchFileItem from the saved path, unlike
                        // addFilesToBatch() which calls getAudioDuration()
                        // for freshly-added files. The Duration column (and
                        // anything relying on the real value rather than
                        // resetBatchStatus()'s file-size-based fallback)
                        // showed 0/blank for any file that survived an app
                        // restart.
                        fileSelectionPanel.probeAndSetDuration(item);
                        batchFiles.add(item);
                        restoredCount++;
                    }
                }

                if (restoredCount > 0) {
                    log("📁 Restored " + restoredCount + " files from previous session");
                }
    
                // After restoring files, try to load batch state
                BatchState savedState = batchProcessor.loadBatchState();
                if (savedState != null && !savedState.getFiles().isEmpty()) {
                    // Restore statuses and progress for each file
                    for (int i = 0; i < batchFiles.size() && i < savedState.getFiles().size(); i++) {
                        BatchFileItem item = batchFiles.get(i);
                        BatchState.FileState fs = savedState.getFiles().get(i);
                        item.setStatus(fs.getStatus());
                        item.setProgress(fs.getProgress());
                        item.setErrorMessage(fs.getErrorMessage());
                    }

                    // If a file was in PROCESSING state, we need to resume it.
                    // For simplicity, we'll just mark it as PENDING and let the user restart.
                    // To truly resume mid-file, we'd need to recreate the segment processor with the saved work directory.
                    // This is more complex; we'll handle it by resetting to PENDING and logging a warning.
                    if (savedState.getCurrentFileIndex() >= 0) {
                        BatchFileItem processingItem = batchFiles.get(savedState.getCurrentFileIndex());
                        processingItem.setStatus(ProcessingStatus.PENDING.name());
                        processingItem.setProgress(0.0);
                        log("⚠️ Previous batch was interrupted mid-file. That file will be restarted from beginning.");
                    }

                    // Ask user if they want to resume
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("Resume Previous Batch");
                    alert.setHeaderText("An incomplete batch was found");
                    alert.setContentText("Do you want to resume processing from where you left off?");
                    Optional<ButtonType> result = alert.showAndWait();
                    if (result.isPresent() && result.get() == ButtonType.OK) {
                        // Restart batch processing
                        startBatchProcessing();
                    } else {
                        // Clear state and reset all files
                        batchFiles.forEach(item -> {
                            // FIX: this condition previously only excluded COMPLETED
                            // and FAILED, so a CANCELLED item (confirmed as a real
                            // ProcessingStatus value) was reset to PENDING here and
                            // silently re-queued on restart — even though the user had
                            // explicitly cancelled it. CANCELLED is a terminal,
                            // user-chosen state and should be respected the same way
                            // COMPLETED/FAILED are: left alone, not reset.
                            if (!"COMPLETED".equals(item.getStatus())
                                    && !"FAILED".equals(item.getStatus())
                                    && !"CANCELLED".equals(item.getStatus())) {
                                item.setStatus(ProcessingStatus.PENDING.name());
                                item.setProgress(0.0);
                            }
                        });
                        batchProcessor.deleteStateFile();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to restore batch queue state", e);
        }
    }

    private void debugPreferences() {
        if (prefManager != null) {
            LOGGER.info("=== PREFERENCE DEBUG ===");
            LOGGER.info("Font size: {}", prefManager.getFontSize());
            LOGGER.info("Output dir: {}", prefManager.getOutputDirectory());
            LOGGER.info("Transcription enabled: {}", prefManager.isTranscriptionEnabled());
            LOGGER.info("Theme: {}", prefManager.getTheme());
            LOGGER.info("Noise reduction: {}", prefManager.isNoiseReductionEnabled());
            LOGGER.info("Normalize audio: {}", prefManager.isNormalizeAudioEnabled());
            LOGGER.info("Auto remove: {}", prefManager.isAutoRemoveCompleted());

            Scene scene = stage.getScene();
            if (scene != null && scene.getRoot() != null) {
                String currentStyle = scene.getRoot().getStyle();
                LOGGER.info("Current root style: {}", currentStyle);
            }
            LOGGER.info("========================");
        } else {
            LOGGER.error("PreferenceManager is NULL!");
        }
    }
    
    private void setupTimeUpdater() {
        timeUpdateTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (batchProcessor.isProcessing() && timeEstimator != null) {
                controlPanel.updateTimeEstimates(batchFiles, System.currentTimeMillis());

                // FIX: was gated on batchProcessor.isProcessing(), which
                // never becomes true during a parallel batch — this whole
                // block, including the calls below, silently never ran for
                // the "10x+ speed" parallel path, which is why time labels,
                // the per-row progress bars (updateBatchStatus now also
                // refreshes the table), and the overall bar all sat frozen
                // until the batch finished.
                //
                // FIX: COMPLETED/FAILED are no longer tallied here — that
                // scan raced against onFileCompleted()'s immediate removal
                // and routinely missed items (see the fix note on
                // onFileCompleted() for the full explanation). Counting now
                // happens exactly once, synchronously, at the real completion
                // event. CANCELLED is still scanned here since cancellation
                // doesn't go through onFileCompleted() at all (BatchProcessor.cancel()
                // resets items to PENDING rather than firing the completion
                // callback), so there's no equivalent single authoritative
                // signal to hook for it.
                for (BatchFileItem item : batchFiles) {
                    if ("CANCELLED".equals(item.getStatus())) countedFailed.add(item);
                }
                int liveCompleted = countedCompleted.size();
                int liveFailed = countedFailed.size();
                int livePending = Math.max(0, originalBatchSize - liveCompleted - liveFailed);

                fileSelectionPanel.updateBatchStatus(batchFiles, liveCompleted, liveFailed, livePending, originalBatchSize);

                // FIX: ControlPanel.updateProgress now takes the authoritative
                // cumulative counts directly (see ControlPanel.java for the
                // root-cause fix — it previously recomputed completed/failed/
                // total by scanning batchFiles, which shrinks the moment
                // auto-remove deletes a COMPLETED item, undercounting on every
                // subsequent tick). No second call needed any more.
                controlPanel.updateProgress(batchFiles, liveCompleted, liveFailed, originalBatchSize);

                // FIX (request #4): previously auto-remove only ran once,
                // in a bulk sweep, after the ENTIRE batch finished — not
                // "as each file completes" like the setting implies. Since
                // completed/failed are now tracked cumulatively above
                // (independent of list membership), it's safe to remove
                // COMPLETED items from the live queue right here, every
                // second, without losing their contribution to the counts.
                // Only COMPLETED items are removed — FAILED ones stay
                // visible so they can be investigated.
                if (configurationPanel.isAutoRemoveCompleted()) {
                    batchFiles.removeIf(item -> "COMPLETED".equals(item.getStatus()));
                }
            }
        }));
        timeUpdateTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    private void initializeBatchStatusLabels() {
        String totalDuration = calculateTotalDuration(batchFiles);

        if (fileSelectionPanel != null) {
            fileSelectionPanel.initializeBatchStatus(batchFiles.size(), totalDuration);
        }

        if (controlPanel != null) {
            controlPanel.updateProgress(0, 0, batchFiles.size());
        }

        // FIX: this used to also call log("📊 Batch initialized: " + ... +
        // totalDuration) here — a second, near-identical "Batch initialized"
        // line logged right after FileSelectionPanel.resetBatchStatus()
        // (called one line above this method, in startBatchProcessing())
        // already logs an equivalent message. The two disagreed: resetBatchStatus()
        // falls back to estimateDurationFromFileSize() per item when a
        // duration isn't known yet, so it reported the real total; this
        // method's calculateTotalDuration() has no such fallback and only
        // guards against totalSeconds==0 by printing "N files" — which then
        // appeared in the log looking like a duration ("total duration: 24
        // files"). Removed the redundant, inferior log call rather than
        // duplicating the fallback logic in a second place.
    }

    private String calculateTotalDuration(ObservableList<BatchFileItem> files) {
        long totalSeconds = files.stream()
            .mapToLong(item -> (long) item.getTotalAudioDurationSeconds())
            .sum();

        if (totalSeconds == 0) {
            return String.format("%d files", files.size());
        }
    
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long hours = minutes / 60;
        minutes = minutes % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public void updateBatchProgress(int completed, int failed, int total) {
        Platform.runLater(() -> {
            if (controlPanel != null) {
                controlPanel.updateProgress(completed, failed, total);
            }

            if (fileSelectionPanel != null) {
                fileSelectionPanel.updateCompletedFailedCounts(completed, failed);
            }

            LOGGER.debug("UI Progress updated: {}/{}/{}", completed, failed, total);
        });
    }

    private void loadPreferences() {
        configurationPanel.loadPreferences();

        fileSelectionPanel.updateOutputDirectory(prefManager.getOutputDirectory());

        LOGGER.info("Loaded font size: {}", prefManager.getFontSize());
    }

    private void saveWindowState() {
        prefManager.setWindowState(
            stage.getX(),
            stage.getY(),
            stage.getWidth(),
            stage.getHeight()
        );
    }

    /**
     * Runs {@link DependencyManager#checkFFprobe()} and swallows any
     * exception into a {@code null} result (treated as "not checked"),
     * rather than letting it propagate. Kept as a separate method — rather
     * than inline in {@link #checkDependencies()} — specifically so the
     * result can be assigned to an effectively-final local there, since it's
     * captured by the {@code Platform.runLater} lambda a few lines below.
     *
     * @param ffmpegAvailable only worth checking FFprobe if FFmpeg itself resolved
     * @return the check result, or {@code null} if FFmpeg wasn't available or the check itself threw
     */
    private DependencyManager.DependencyStatus safeCheckFFprobe(boolean ffmpegAvailable) {
        if (!ffmpegAvailable) return null;
        try {
            return dependencyManager.checkFFprobe();
        } catch (Exception e) {
            LOGGER.error("FFprobe check threw an exception", e);
            return null;
        }
    }

    private void checkDependencies() {
        log("🔍 Checking system dependencies...");
        controlPanel.setDependencyCheckInProgress(true);
        
        try {
            DependencyManager.DependencyStatus ffmpegStatus = dependencyManager.checkFFmpeg();
            DependencyManager.DependencyStatus whisperStatus = dependencyManager.checkWhisper();

            // FIX: wrapped in its own try/catch via a helper method, separate
            // from ffmpegStatus/whisperStatus above. This check was
            // originally added inline in the same try block as those two —
            // meaning any exception from this new, less-battle-tested check
            // would fall through to the outer catch and report BOTH ffmpeg
            // and whisper as failed (`updateDependencyStatus(null, null)`)
            // too, even though they had already succeeded. One optional
            // check failing must not be able to erase results other checks
            // already obtained.
            DependencyManager.DependencyStatus ffprobeStatus = safeCheckFFprobe(ffmpegStatus.isAvailable());

            // FIX: Java being able to launch FFmpeg doesn't mean WhisperX's own
            // Python interpreter can find it — WhisperX shells out to ffmpeg
            // internally and fails deep inside its own pipeline if it can't.
            // Check this explicitly, up front, instead of letting transcription
            // start and fail with a confusing error later. Only worth checking
            // if both the JVM-side ffmpeg check passed and transcription is
            // actually going to be used.
            DependencyManager.DependencyStatus ffmpegVisibleToWhisperX =
                    (ffmpegStatus.isAvailable() && configurationPanel.isTranscriptionEnabled())
                            ? dependencyManager.checkFFmpegVisibleToWhisperX()
                            : null;

            Platform.runLater(() -> {
                controlPanel.updateDependencyStatus(ffmpegStatus, whisperStatus);

                // FIX (reapplied — contradictory messaging + over-blocking):
                // log the authoritative checks first, in the order they
                // actually happened — "Checking FFmpeg visibility... FFmpeg
                // verified. FFprobe verified." — instead of interleaving a
                // ❌ from the advisory Python-visibility probe ahead of the
                // ✅ from the real FFmpeg check below. Previously the ❌
                // line for ffmpegVisibleToWhisperX appeared BEFORE the ✅
                // line for ffmpegStatus, even though ffmpegStatus is
                // authoritative and already known true at this point —
                // reading as "FFmpeg is installed but is not visible"
                // immediately followed by "FFmpeg found", exactly the
                // confusing sequence reported.
                log("Checking FFmpeg visibility...");
                log("✅ " + ffmpegStatus.getMessage());
                if (ffprobeStatus != null) {
                    log((ffprobeStatus.isAvailable() ? "✅ " : "❌ ") + ffprobeStatus.getMessage());
                }

                if (!ffmpegStatus.isAvailable()) {
                    controlPanel.setProcessingEnabled(false);
                    if (ffmpegStatus.hasInstallationHint()) {
                        log("❌ REQUIRED: " + ffmpegStatus.getInstallationHint());
                    }
                } else if (ffprobeStatus != null && !ffprobeStatus.isAvailable()) {
                    // FIX: ffprobe is required for every file (duration
                    // probing happens before any transcription decision is
                    // made), so treat it the same severity as ffmpeg itself
                    // being missing — block processing and show the same
                    // kind of actionable hint, instead of letting the batch
                    // start and fail on every file with a raw process error.
                    controlPanel.setProcessingEnabled(false);
                    log("❌ REQUIRED: " + ffprobeStatus.getInstallationHint());
                } else if (!whisperStatus.isAvailable() && configurationPanel.isTranscriptionEnabled()) {
                    log("⚠️ WARNING: " + whisperStatus.getMessage());
                    if (whisperStatus.hasInstallationHint()) {
                        log("💡 INFO: " + whisperStatus.getInstallationHint());
                    }
                }

                log("✅ " + whisperStatus.getMessage());

                // FIX (reapplied): this is an ADVISORY check only —
                // DependencyManager's own comment on
                // checkFFmpegVisibleToWhisperX() states it "has been seen to
                // report 'not visible' immediately before the actual
                // transcription run ... succeeds moments later" and is
                // explicitly "not a guarantee of failure". Disabling
                // processing on it (the previous behaviour) blocked users
                // from transcribing on setups where it would have worked
                // fine. It's now reported as a heads-up (⚠️, not ❌) and
                // never blocks the Start Processing button.
                if (ffmpegVisibleToWhisperX != null) {
                    if (ffmpegVisibleToWhisperX.isAvailable()) {
                        log("✅ " + ffmpegVisibleToWhisperX.getMessage());
                    } else {
                        log("⚠️ " + ffmpegVisibleToWhisperX.getMessage());
                        if (ffmpegVisibleToWhisperX.hasInstallationHint()) {
                            log("💡 INFO: " + ffmpegVisibleToWhisperX.getInstallationHint());
                        }
                    }
                }

                log("🎉 Dependency check complete.");
            });
            
        } catch (Exception e) {
            LOGGER.error("Dependency check failed", e);
            Platform.runLater(() -> {
                controlPanel.updateDependencyStatus(null, null);
                log("❌ ERROR: Dependency check failed - " + e.getMessage());
            });
        } finally {
            Platform.runLater(() -> {
                controlPanel.setDependencyCheckInProgress(false);
                fileSelectionPanel.updateBatchStatus(batchFiles);
            });
        }
    }

    private void handleProcessButtonClick() {
        if (batchProcessor.isProcessing()) {
            showCancelConfirmation();
            // After confirmation, call batchProcessor.cancel()
            // Immediately set the button state to "Start Processing"
            controlPanel.setProcessingState(false);
        } else {
            startBatchProcessing();
        }
    }

    private void startBatchProcessing() {
        if (timeUpdateTimeline == null) {
            setupTimeUpdater(); // re-initialize if somehow missing
        }
        
        if (batchFiles.isEmpty()) {
            log("❌ ERROR: Batch queue is empty. Please add files before starting.");
            return;
        }

        fileSelectionPanel.resetBatchStatus();

        initializeBatchStatusLabels();

        // FIX: reset the cumulative completed/failed tracking and snapshot
        // the queue size for this run, so Total/Completed/Failed/Pending
        // start clean and "Pending" is measured against the size of this
        // batch, not left over from a previous run.
        countedCompleted.clear();
        countedFailed.clear();
        originalBatchSize = batchFiles.size();

        log("🚀 Starting batch processing...");
        if (timeEstimator != null) {
            timeEstimator.reset();
        }
        controlPanel.setProcessingState(true);
        controlPanel.startBatchProcessing();
        fileSelectionPanel.setProcessingState(true);
        configurationPanel.setEnabled(false);

        timeUpdateTimeline.play();

        ProcessingConfig processingConfig = configurationPanel.getProcessingConfig();
        TranscriptionConfig transcriptionConfig = configurationPanel.getTranscriptionConfig();
        int maxParallel = configurationPanel.getMaxParallelFiles();

        // FIX (consolidation): was a branch here between
        // startStandardBatchProcessing() (maxParallel <= 1) and
        // startParallelBatchProcessing() (maxParallel > 1) — two separate
        // implementations that called into two separate processing engines
        // (BatchProcessor's own executeBatch() vs a direct call into
        // MainWindow's own ParallelProcessingManager instance), each with
        // its own bugs and its own UI wiring that had to be kept in sync by
        // hand. BatchProcessor.processBatch() now handles every maxParallel
        // value itself by delegating internally, so there's only one call
        // here regardless.
        log(maxParallel > 1 ? "⚡ Starting batch (parallel, up to " + maxParallel + " at once)..."
                             : "⚡ Starting batch...");

        // Wire real-time progress bar: fires on every file completion/failure
        // FIX (done-count bug, reapplied): this called the 3-arg
        // ControlPanel.updateProgress(completed, failed, total), which only
        // updates the progress bar and percentage label — it never touches
        // detailedStatusLabel, the "📊 Queue: N total | ... | ✅ N done |
        // ❌ N failed" text in the Ready to Process section. That text was
        // ONLY ever refreshed by the 1-second setupTimeUpdater() polling
        // Timeline, which lags up to 1s behind what the Terminal shows
        // immediately, and can miss the very last file of a batch entirely
        // since finishBatchProcessing() stops the timeline essentially the
        // same instant the final completion callback fires. Calling the
        // 4-arg overload here makes the "done" count update immediately, on
        // every single completion, independent of the polling timeline.
        batchProcessor.setStatisticsCallback(stats -> {
            // BatchProcessor already wraps this in Platform.runLater
            if (controlPanel != null) {
                controlPanel.updateProgress(
                    batchFiles,
                    stats.getCompletedFiles(),
                    stats.getFailedFiles(),
                    stats.getTotalFiles()
                );
            }
            if (fileSelectionPanel != null) {
                fileSelectionPanel.updateCompletedFailedCounts(
                    stats.getCompletedFiles(),
                    stats.getFailedFiles()
                );
            }
        });

        batchProcessor.processBatch(batchFiles, processingConfig, transcriptionConfig, maxParallel)
            .thenAccept(result -> {
                Platform.runLater(() -> {
                    finishBatchProcessing(result);
                });
            })
            .exceptionally(ex -> {
                LOGGER.error("Batch processing error", ex);
                Platform.runLater(() -> {
                    log("❌ ERROR: Batch processing failed - " + ex.getMessage());
                    finishBatchProcessing(null);
                });
                return null;
            });
    }

    private void finishBatchProcessing(BatchProcessor.BatchResult result) {
        timeUpdateTimeline.stop();
        controlPanel.setProcessingState(false);
        fileSelectionPanel.setProcessingState(false);
        configurationPanel.setEnabled(true);

        if (result != null) {
            updateBatchProgress(result.getCompleted(), result.getFailed(), result.getTotal());
        } else {
            BatchProcessor.BatchStatistics stats = batchProcessor.getCurrentBatchStatistics();
            updateBatchProgress(stats.getCompletedFiles(), stats.getFailedFiles(), stats.getTotalFiles());
        }

        if (result != null) {
            String message = result.wasCancelled() 
                ? "⏹️ Batch processing cancelled."
                : String.format("✅ Batch complete: %d succeeded, %d failed", 
                               result.getCompleted(), result.getFailed());
            log(message);

            if (configurationPanel.isAutoRemoveCompleted() && !result.wasCancelled()) {
                batchFiles.removeIf(item -> 
                    "COMPLETED".equals(item.getStatus())
                );
            }

            // FIX (request #9/#10): surface batch completion beyond the log
            // area — a system-tray notification for anyone who's alt-tabbed
            // away, plus an on-demand statistics report. Skipped for a
            // cancelled batch since "processed X files" would be misleading.
            if (!result.wasCancelled()) {
                showNotification("Batch Complete",
                        String.format("%d of %d files succeeded, %d failed",
                                result.getCompleted(), result.getTotal(), result.getFailed()));
                showBatchStatistics(result);
            }
        }

        fileSelectionPanel.updateBatchStatus(batchFiles);
    }

    /**
     * Show a best-effort OS-level tray notification. Silently falls back to
     * the on-screen log if the platform doesn't support a tray (headless
     * environments, some Linux desktops) or the icon resource is missing —
     * a missing notification should never interrupt or fail batch completion.
     */
    private void showNotification(String title, String message) {
        if (!SystemTray.isSupported()) {
            log("🔔 " + title + ": " + message);
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            InputStream iconStream = getClass().getResourceAsStream(AppConstants.ICON_PATH);
            java.awt.Image image = (iconStream != null)
                    ? Toolkit.getDefaultToolkit().createImage(iconStream.readAllBytes())
                    : Toolkit.getDefaultToolkit().createImage(new byte[0]);
            TrayIcon trayIcon = new TrayIcon(image, AppConstants.APP_TITLE);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            // Remove the icon shortly after so repeated batches don't pile up
            // multiple tray icons.
            CompletableFuture.delayedExecutor(10, java.util.concurrent.TimeUnit.SECONDS)
                    .execute(() -> tray.remove(trayIcon));
        } catch (Exception e) {
            LOGGER.debug("Tray notification unavailable, falling back to log: {}", e.getMessage());
            log("🔔 " + title + ": " + message);
        }
    }

    /** Format a millisecond duration as {@code H:MM:SS} (or {@code M:SS} under an hour). */
    private String formatDurationMillis(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }

    /** Show a summary dialog with completion counts, duration, and output directory. */
    private void showBatchStatistics(BatchProcessor.BatchResult result) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Batch Processing Report");
        alert.setHeaderText("Processing Complete");

        String stats = String.format(
                "📊 Batch Processing Report%n" +
                "===========================%n" +
                "Total Files:     %d%n" +
                "Completed:       %d ✅%n" +
                "Failed:          %d ❌%n" +
                "Duration:        %s%n" +
                "Success Rate:    %.1f%%%n%n" +
                "Output Directory:%n%s",
                result.getTotal(),
                result.getCompleted(),
                result.getFailed(),
                formatDurationMillis(result.getDurationMillis()),
                result.getTotal() > 0 ? (result.getCompleted() * 100.0 / result.getTotal()) : 0,
                configurationPanel.getProcessingConfig().getOutputDirectory()
        );

        TextArea textArea = new TextArea(stats);
        textArea.setEditable(false);
        textArea.setPrefSize(450, 250);
        alert.getDialogPane().setContent(textArea);
        alert.showAndWait();
    }

    // FIX (consolidation): cancelAnyRunningBatch() is gone —
    // BatchProcessor.cancel() now handles cancelling its internal
    // parallelManager itself (including running that off a background
    // thread, since shutdown() can block for up to ~2 minutes waiting on
    // executor termination), so callers just call batchProcessor.cancel()
    // directly again, the same as before parallel processing was added as
    // a second, separately-cancelled path.

    private void showCancelConfirmation() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Cancel Processing");
        alert.setHeaderText("Batch processing is currently running");
        alert.setContentText("Are you sure you want to cancel all running processes?");
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            log("⏹️ Cancelling batch processing...");
            batchProcessor.cancel();
        }
    }

    private void handleExitButtonClick() {
        if (batchProcessor.isProcessing()) {
            // Confirm exit
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Exit Confirmation");
            alert.setHeaderText("Processing is active");
            alert.setContentText("Exiting will cancel all running processes. Continue?");
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                batchProcessor.cancel();
                // Give it a moment to clean up
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                saveApplicationState();
            }
        } else {
            saveApplicationState();
        }
    }

    /**
     * Severity for a log line shown in the on-screen log area.
     *
     * <p>Previously {@code log(String)} guessed severity by scanning the
     * message text for words like "error"/"failed"/"success" and prepending
     * an emoji accordingly. But nearly every call site already embeds its
     * own semantic emoji directly in the message (e.g. {@code "❌ File
     * failed processing: " + name}), so a "failed" message got its ❌
     * auto-prepended a second time — a visible double-emoji on every error
     * line. Worse, the guess was purely lexical: a message like "Retry
     * failed-count: 0" (a success case) would still get flagged ❌ just for
     * containing the substring "failed". Severity is now an explicit
     * parameter instead of an inference from message text.</p>
     */
    private enum LogLevel {
        INFO(""), SUCCESS("✅ "), WARNING("⚠️ "), ERROR("❌ "), PROCESSING("🔄 ");

        private final String prefix;
        LogLevel(String prefix) { this.prefix = prefix; }
    }

    /** Emoji/pictographic characters stripped from the plain-text logger line. */
    private static final java.util.regex.Pattern EMOJI_PATTERN =
            java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\uFE0F]");

    /**
     * Log at INFO with no auto-derived styling. Call sites that already embed
     * their own emoji (the vast majority in this class) get exactly what they
     * wrote, once — no guessed prefix is added on top.
     */
    private void log(String message) {
        log(message, LogLevel.INFO);
    }

    /**
     * Maximum characters retained in the on-screen log area before older
     * lines are trimmed from the front.
     *
     * <p>FIX: {@code logArea.appendText()} previously ran with no cap at
     * all for the life of the session. Over a long batch with many retries
     * (each retry re-logs several lines, and some call sites — like the
     * WhisperX dependency check — used to also dump multi-kilobyte CLI
     * output, see the separate fix in {@code WhisperXTranscriptionService}),
     * this could accumulate into the megabytes of retained text sitting in
     * a single JavaFX {@code TextArea}, contributing to JVM heap exhaustion
     * on long-running batches. 500,000 characters (~500KB) keeps a
     * generous scrollback while giving growth a ceiling.</p>
     */
    private static final int LOG_AREA_MAX_CHARS = 500_000;

    private void log(String message, LogLevel level) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String displayMessage = level.prefix + message;
        Platform.runLater(() -> {
            logArea.appendText(String.format("[%s] %s%n", timestamp, displayMessage));
            int length = logArea.getLength();
            if (length > LOG_AREA_MAX_CHARS) {
                // Trim from the front, keeping the most recent content.
                logArea.deleteText(0, length - LOG_AREA_MAX_CHARS);
            }
            logArea.setScrollTop(Double.MAX_VALUE);
        });

        String cleanMessage = EMOJI_PATTERN.matcher(displayMessage).replaceAll("").trim();
        LOGGER.info(cleanMessage);
    }
}