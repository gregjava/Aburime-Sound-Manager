/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.Studio;
import audiomanager.constants.AppConstants;
import audiomanager.core.*;
import audiomanager.exceptions.FfmpegException;
import audiomanager.model.*;
import audiomanager.plugins.AudioSplitterTool;
import audiomanager.plugins.FileCombinerTool;
import audiomanager.util.PreferenceManager;
import audiomanager.util.SoundManager;
import audiomanager.util.TimeLeftEstimator;
import java.io.File;
import java.io.IOException;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Main application window - orchestrates UI and business logic.
 */
public class MainWindow {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindow.class);

    // ===== Dependencies =====
    private final Stage stage;
    private PreferenceManager prefManager;
    private final DependencyManager dependencyManager;
    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final TimeLeftEstimator timeEstimator;
    private final BatchProcessor batchProcessor;

    // ===== Controllers =====
    private final MainWindowController controller;
    private final MainWindowUICreator uiCreator;

    // ===== UI Components (from uiCreator) =====
    private FileSelectionPanel fileSelectionPanel;
    private ConfigurationPanel configurationPanel;
    private ControlPanel controlPanel;
    private TextArea logArea;

    // ===== State =====
    private final ObservableList<BatchFileItem> batchFiles;
    private Timeline timeUpdateTimeline;
    private ErrorReporter errorReporter;

    // ===== Other =====
    private FolderWatcher folderWatcher;
    private Thread folderWatcherThread;
    private MenuItem watchFolderMenuItem;
    private RestApiServer restApiServer;
    private final AppState appState = AppState.getInstance();
    private final GpuConfig gpuConfig = GpuConfig.getInstance();

    // ===== Constants =====
    private static final int LOG_AREA_MAX_CHARS = 500_000;
    private static final java.util.regex.Pattern EMOJI_PATTERN =
            java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\uFE0F]");
    private static final int PRIVACY_DISCLOSURE_VERSION = 2;

    // ========================================================================
    //  Construction
    // ========================================================================

    public MainWindow(Stage stage, PreferenceManager prefManager) {
        this.stage = stage;
        this.prefManager = prefManager;

        if (prefManager == null) {
            LOGGER.error("PreferenceManager is null - creating fallback instance");
            this.prefManager = new PreferenceManager(MainWindow.class);
        }

        Studio studio = Studio.getInstance();
        this.errorReporter = studio != null ? studio.getErrorReporter() : null;

        this.dependencyManager = new DependencyManager();
        this.audioProcessor = new AudioProcessor(dependencyManager, errorReporter);
        this.timeEstimator = new TimeLeftEstimator(10, this.prefManager);
        this.transcriptionService = new WhisperXTranscriptionService(
                dependencyManager, timeEstimator, null, errorReporter);

        this.batchFiles = appState.getBatchFiles();

        this.batchProcessor = new BatchProcessor(
                audioProcessor,
                transcriptionService,
                timeEstimator,
                prefManager,
                this::log,
                this::onFileCompleted,
                batchFiles,
                errorReporter
        );

        this.controller = new MainWindowController(
                appState,
                batchProcessor,
                dependencyManager,
                timeEstimator,
                prefManager,
                this::log,
                errorReporter
        );

        AudioSplitterTool audioSplitter = new AudioSplitterTool(dependencyManager, prefManager);
        audioSplitter.setLogger(this::log);

        FileCombinerTool fileCombiner = new FileCombinerTool(prefManager);
        fileCombiner.setLogger(this::log);

        SoundRecorderPanel soundRecorderPanel = new SoundRecorderPanel(batchFiles, prefManager, this::log, errorReporter);

        this.uiCreator = new MainWindowUICreator(
                stage,
                prefManager,
                appState,
                this::log,
                audioSplitter,
                fileCombiner,
                soundRecorderPanel
        );

        wireUICallbacks();

        LOGGER.info("Application components initialized with refactored architecture");
    }

    // ========================================================================
    //  Initialization
    // ========================================================================

    public void initialize() {
        LicenseManager license = LicenseManager.getInstance();
        license.loadLicense();
        
        // ✅ Preload sound effects
        SoundManager.preload();

        configureStage();

        Scene scene = uiCreator.createScene();
        
        // ===== APPLY CSS =====
        applyCSSIfAvailable(scene);
        
        stage.setScene(scene);

        fileSelectionPanel = uiCreator.getFileSelectionPanel();
        configurationPanel = uiCreator.getConfigurationPanel();
        controlPanel = uiCreator.getControlPanel();
        logArea = uiCreator.getLogArea();

        toggleTheme("Dark".equals(prefManager.getTheme()));

        configurationPanel.setFontSizeChangeListener(this::applyFontSize);
        loadPreferences();
        applyFontSize(prefManager.getFontSize());
        restoreWindowState();

        setupTimeUpdater();
        restoreBatchQueueState();
        setupEventHandlers();
        
        // ===== SETUP KEYBOARD SHORTCUTS =====
        setupKeyboardShortcuts(scene);

        if (fileSelectionPanel != null && batchProcessor != null) {
            fileSelectionPanel.getClearQueueButton().disableProperty().bind(
                    batchProcessor.isRunningProperty()
            );
        }

        initializeGpu();

        showPrivacyDisclosureIfNeeded();

        stage.show();

        CompletableFuture.runAsync(() -> controller.checkDependencies());

        String titleSuffix = license.isPro() ? " - Pro" : " - Free";
        stage.setTitle(AppConstants.APP_TITLE + " v" + AppConstants.APP_VERSION + titleSuffix);
    }

    // ========================================================================
    //  Keyboard Shortcuts
    // ========================================================================

    /**
     * Sets up global keyboard shortcuts for the application.
     */
    private void setupKeyboardShortcuts(Scene scene) {
        // Ctrl+Shift+D - Toggle Dark Mode
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.D && event.isShortcutDown() && event.isShiftDown()) {
                SoundManager.playClick();  // ✅ ADDED
                toggleTheme(!"Dark".equals(prefManager.getTheme()));
                event.consume();
                log("🌙 Theme toggled via keyboard shortcut");
                return;
            }
            
            // F5 - Check Dependencies
            if (event.getCode() == KeyCode.F5 && !event.isShortcutDown()) {
                if (controller != null) {
                    SoundManager.playClick();  // ✅ ADDED
                    controller.checkDependencies();
                    event.consume();
                    log("🔍 Dependency check triggered via keyboard shortcut");
                }
                return;
            }
            
            // Ctrl+Shift+W - Toggle Folder Watch
            if (event.getCode() == KeyCode.W && event.isShortcutDown() && event.isShiftDown()) {
                SoundManager.playClick();  // ✅ ADDED
                toggleFolderWatch();
                event.consume();
                return;
            }
            
            // Ctrl+Shift+P - Performance Report
            if (event.getCode() == KeyCode.P && event.isShortcutDown() && event.isShiftDown()) {
                SoundManager.playClick();  // ✅ ADDED
                showPerformanceReportDialog();
                event.consume();
                return;
            }
            
            // Ctrl+B - Batch Settings
            if (event.getCode() == KeyCode.B && event.isShortcutDown() && !event.isShiftDown()) {
                SoundManager.playClick();  // ✅ ADDED
                showBatchSettingsDialog();
                event.consume();
                return;
            }
            
            // Ctrl+Q - Exit
            if (event.getCode() == KeyCode.Q && event.isShortcutDown() && !event.isShiftDown()) {
                SoundManager.playClick();  // ✅ ADDED
                handleExitButtonClick();
                event.consume();
                return;
            }
            
            // Ctrl+Comma - Preferences
            if (event.getCode() == KeyCode.COMMA && event.isShortcutDown() && !event.isShiftDown()) {
                SoundManager.playClick();  // ✅ ADDED
                showPreferencesDialog();
                event.consume();
                return;
            }
            
            // Ctrl+Z - Undo (handled by FileSelectionPanel)
            if (event.getCode() == KeyCode.Z && event.isShortcutDown() && !event.isShiftDown()) {
                if (fileSelectionPanel != null && fileSelectionPanel.undo()) {
                    SoundManager.playClick();  // ✅ ADDED
                    log("↩️ Undo performed via keyboard shortcut");
                    event.consume();
                }
                return;
            }
            
            // Ctrl+Shift+Z or Ctrl+Y - Redo
            if ((event.getCode() == KeyCode.Z && event.isShortcutDown() && event.isShiftDown()) ||
                (event.getCode() == KeyCode.Y && event.isShortcutDown() && !event.isShiftDown())) {
                if (fileSelectionPanel != null && fileSelectionPanel.redo()) {
                    SoundManager.playClick();  // ✅ ADDED
                    log("↪️ Redo performed via keyboard shortcut");
                    event.consume();
                }
                return;
            }
            
            // Ctrl+A - Select All in table (handled by table itself)
            if (event.getCode() == KeyCode.A && event.isShortcutDown() && !event.isShiftDown()) {
                if (!(event.getTarget() instanceof TableView)) {
                    if (event.getTarget() instanceof TextArea && logArea != null) {
                        logArea.selectAll();
                        event.consume();
                    }
                }
                return;
            }
            
            // Escape - Clear selection or close dialogs
            if (event.getCode() == KeyCode.ESCAPE) {
                if (event.getTarget() instanceof TableView) {
                    TableView<?> table = (TableView<?>) event.getTarget();
                    table.getSelectionModel().clearSelection();
                    event.consume();
                }
                return;
            }
        });
        
        LOGGER.info("✅ Keyboard shortcuts initialized");
    }

    // ========================================================================
    //  Dialog Methods (for keyboard shortcuts)
    // ========================================================================

    private void showPreferencesDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Preferences");
        dialog.setHeaderText("User Interface Settings");
        dialog.getDialogPane().setContent(new Label("Preferences dialog - coming soon"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);
        dialog.showAndWait();
    }

    private void showBatchSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Batch Processing Settings");
        dialog.setHeaderText("Configure batch processing behavior");
        dialog.getDialogPane().setContent(new Label("Batch settings - coming soon"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);
        dialog.showAndWait();
    }

    // ========================================================================
    //  CSS Application
    // ========================================================================

    /**
     * Applies the main stylesheet to the scene.
     * This is the LIGHT theme stylesheet.
     */
    private void applyCSSIfAvailable(Scene scene) {
        try {
            String cssPath = getClass().getResource("/styles/styles.css").toExternalForm();
            if (cssPath != null && !scene.getStylesheets().contains(cssPath)) {
                scene.getStylesheets().add(cssPath);
                LOGGER.info("✅ Applied light theme CSS: {}", cssPath);
            }
        } catch (Exception e) {
            LOGGER.debug("CSS file not found, using default styling: {}", e.getMessage());
        }
    }

    // ========================================================================
    //  GPU Initialization
    // ========================================================================

    private void initializeGpu() {
        CompletableFuture.runAsync(() -> {
            try {
                gpuConfig.detectGpu();
                if (gpuConfig.isGpuAvailable()) {
                    LOGGER.info("✅ GPU detected: {}", gpuConfig.getGpuSummary());
                    Platform.runLater(() -> {
                        if (logArea != null) {
                            log("✅ " + gpuConfig.getGpuSummary());
                        }
                    });
                } else {
                    LOGGER.info("ℹ️ Running in CPU mode");
                    LOGGER.debug("  No NVIDIA GPU detected (nvidia-smi not found) - this is normal if you don't have an NVIDIA GPU");
                    Platform.runLater(() -> {
                        if (logArea != null) {
                            log("ℹ️ Running in CPU mode (no GPU detected)");
                        }
                    });
                }
            } catch (Exception e) {
                LOGGER.warn("GPU detection failed: {}", e.getMessage());
                Platform.runLater(() -> {
                    if (logArea != null) {
                        log("⚠️ GPU detection failed - running in CPU mode");
                    }
                });
            }
        });
    }

    // ========================================================================
    //  UI Callback Wiring
    // ========================================================================

    private void wireUICallbacks() {
        uiCreator.setOnProcessClick(this::handleProcessButtonClick);
        uiCreator.setOnExitClick(this::handleExitButtonClick);
        uiCreator.setOnCheckDependencies(() -> controller.checkDependencies());
        uiCreator.setOnToggleTheme(() -> toggleTheme(!"Dark".equals(prefManager.getTheme())));
        uiCreator.setOnScheduleClick(this::showScheduleDialog);
        uiCreator.setOnPerformanceReport(this::showPerformanceReportDialog);
        uiCreator.setOnWatchFolder(this::toggleFolderWatch);
        uiCreator.setOnClearTimeData(this::clearTimeEstimationData);
        uiCreator.setOnRestApiToggle(this::toggleRestApi);
        uiCreator.setOnClearSessionData(this::clearSessionData);
    }

    // ========================================================================
    //  File Completion Callback
    // ========================================================================

    private void onFileCompleted(BatchFileItem item, boolean wasSuccessful) {
        controller.onFileCompleted(item, wasSuccessful);
        if (wasSuccessful && fileSelectionPanel != null) {
            fileSelectionPanel.removeItemFromBatchQueue(item);
        }
    }

    // ========================================================================
    //  Batch Processing
    // ========================================================================

    private void handleProcessButtonClick() {
        SoundManager.playClick();  // ✅ ADDED - Always play click for process button
        if (controller.isProcessing()) {
            showCancelConfirmation();
            controller.cancelBatch();
            if (controlPanel != null) {
                controlPanel.setProcessingState(false);
            }
        } else {
            startBatchProcessing();
        }
    }

    private void startBatchProcessing() {
        if (batchFiles.isEmpty()) {
            appState.setStatus("Error", "❌ Batch queue is empty. Please add files first.");
            return;
        }

        if (fileSelectionPanel != null) {
            fileSelectionPanel.resetBatchStatus();
        }

        ProcessingConfig processingConfig = configurationPanel.getProcessingConfig();
        TranscriptionConfig transcriptionConfig = configurationPanel.getTranscriptionConfig();
        int maxParallel = configurationPanel.getMaxParallelFiles();

        batchProcessor.setExportWordCopy(configurationPanel.isExportWordCopyEnabled());
        batchProcessor.setExportPdfCopy(configurationPanel.isExportPdfCopyEnabled());
        batchProcessor.setAutoRemoveCompleted(configurationPanel.isAutoRemoveCompleted());

        if (configurationPanel.isGpuEnabled() && gpuConfig.isGpuAvailable()) {
            log("⚡ GPU acceleration ENABLED for this batch: " + gpuConfig.getGpuName());
        } else if (configurationPanel.isGpuEnabled() && !gpuConfig.isGpuAvailable()) {
            log("⚠️ GPU acceleration enabled but no GPU detected — running on CPU");
        } else {
            log("ℹ️ GPU acceleration disabled — running on CPU");
        }

        controller.startBatch(batchFiles, processingConfig, transcriptionConfig, maxParallel);

        if (timeUpdateTimeline == null) {
            setupTimeUpdater();
        }
        timeUpdateTimeline.play();
    }

    // ========================================================================
    //  Time Updater
    // ========================================================================

    private void setupTimeUpdater() {
        timeUpdateTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (controller.isProcessing() && timeEstimator != null) {
                appState.updateTimeEstimates(
                    timeEstimator.getCurrentFileTimeSpent(),
                    timeEstimator.getLiveCurrentFileTimeLeftMs(),
                    timeEstimator.getTotalTimeSpent(),
                    timeEstimator.getLiveTotalTimeLeftMs()
                );

                double progress = BatchProgressAggregator.compute(batchFiles)
                    .getOverallProgressPercent() / 100.0;
                appState.setOverallProgress(progress);

                if (configurationPanel.isAutoRemoveCompleted()) {
                    batchFiles.removeIf(item -> "COMPLETED".equals(item.getStatus()));
                }
            }
        }));
        timeUpdateTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    // ========================================================================
    //  Public Methods
    // ========================================================================

    public void showStatusMessage(String message) {
        Platform.runLater(() -> {
            if (controlPanel != null) {
                controlPanel.updateStatus(message, null);
            }
            log(message);
        });
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

    // ========================================================================
    //  Theme
    // ========================================================================

    private void toggleTheme(boolean dark) {
        SoundManager.playClick();  // ✅ Click sound when toggling theme
        Scene scene = stage.getScene();
        if (scene == null) return;

        prefManager.setTheme(dark ? "Dark" : "Light");
        prefManager.flush();

        if (uiCreator != null && uiCreator.getDarkModeMenuItem() != null) {
            uiCreator.getDarkModeMenuItem().setSelected(dark);
        }

        // Apply theme - no stripping, no flickering
        ThemeManager.sweep(scene, dark);
        ThemeManager.forceRefresh(scene);

        if (uiCreator != null) {
            uiCreator.updateTheme(dark);
        }

        LOGGER.info("🌙 Theme toggled to: {}", dark ? "Dark" : "Light");
    }

    // ========================================================================
    //  UI Methods
    // ========================================================================

    private void loadPreferences() {
        configurationPanel.loadPreferences();
        if (fileSelectionPanel != null) {
            fileSelectionPanel.updateOutputDirectory(prefManager.getOutputDirectory());
        }
    }

    private void applyFontSize(double size) {
        LOGGER.debug("Applying font size: {}px", size);

        Scene scene = stage.getScene();
        if (scene != null) {
            String style = String.format("-fx-font-size: %spx;", (int) size);
            scene.getRoot().setStyle("-fx-font-family: 'Segoe UI', 'Roboto', 'Arial'; " + style);
        }

        if (logArea != null) {
            logArea.setStyle(String.format("-fx-font-size: %spx;", (int) size));
        }

        applyFontSizeToUIComponents(size);
    }

    private void applyFontSizeToUIComponents(double size) {
        try {
            Scene scene = stage.getScene();
            if (scene == null) return;

            scene.getRoot().lookupAll(".button").forEach(node -> {
                if (node instanceof Button) applyFontSizeRule(node, size);
            });
            scene.getRoot().lookupAll(".label").forEach(node -> {
                if (node instanceof Label) applyFontSizeRule(node, size);
            });
            scene.getRoot().lookupAll(".text-field").forEach(node -> {
                if (node instanceof TextField) applyFontSizeRule(node, size);
            });
            scene.getRoot().lookupAll(".text-area").forEach(node -> {
                if (node instanceof TextArea) applyFontSizeRule(node, size);
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to apply font size to some UI components", e);
        }
    }

    private void applyFontSizeRule(javafx.scene.Node node, double size) {
        String existing = node.getStyle();
        String withoutFontSize = (existing == null || existing.isBlank())
                ? "" : java.util.regex.Pattern.compile("-fx-font-size:\\s*[^;]+;?\\s*")
                    .matcher(existing).replaceAll("").trim();
        String separator = withoutFontSize.isEmpty() || withoutFontSize.endsWith(";") ? " " : "; ";
        node.setStyle(withoutFontSize + separator + String.format("-fx-font-size: %spx;", (int) size));
        ThemeManager.stripForCurrentTheme(node);
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

    private void restoreBatchQueueState() {
        try {
            String savedFiles = prefManager.getString("batch_queue_files", "");
            if (!savedFiles.isEmpty()) {
                String[] filePaths = savedFiles.split(";");
                for (String filePath : filePaths) {
                    File file = new File(filePath);
                    if (file.exists() && file.isFile()) {
                        BatchFileItem item = new BatchFileItem(file);
                        if (fileSelectionPanel != null) {
                            fileSelectionPanel.probeAndSetDuration(item);
                        }
                        batchFiles.add(item);
                    }
                }
            }

            BatchState savedState = batchProcessor.loadBatchState();
            if (savedState != null && !savedState.getFiles().isEmpty()) {
                showResumeConfirmation(savedState);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to restore batch queue state", e);
        }
    }

    private void showResumeConfirmation(BatchState savedState) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Resume Previous Batch");
        alert.setHeaderText("An incomplete batch was found");
        alert.setContentText("Do you want to resume processing from where you left off?");
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            startBatchProcessing();
        } else {
            batchFiles.forEach(item -> {
                if (!"COMPLETED".equals(item.getStatus())
                        && !"FAILED".equals(item.getStatus())
                        && !"CANCELLED".equals(item.getStatus())) {
                    item.setStatus(ProcessingStatus.PENDING.name());
                    item.setProgress(0.0);
                    item.setErrorMessage(null);
                }
            });
            batchProcessor.deleteStateFile();
        }
    }

    private void setupEventHandlers() {
        stage.setOnCloseRequest(e -> {
            if (controller.isProcessing()) {
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
        if (restApiServer != null && restApiServer.isRunning()) {
            restApiServer.stop();
        }
        try {
            if (configurationPanel != null) {
                configurationPanel.savePreferences();
            }
            if (fileSelectionPanel != null) {
                fileSelectionPanel.saveFilePreferences();
            }
            if (timeEstimator != null) {
                timeEstimator.saveData();
            }
            saveWindowState();
            if (prefManager != null) {
                prefManager.flush();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save application state", e);
        } finally {
            Platform.exit();
        }
    }

    private void saveWindowState() {
        prefManager.setWindowState(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    private void handleExitButtonClick() {
        SoundManager.playClick();  // ✅ Click sound for exit
        if (controller.isProcessing()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Exit Confirmation");
            alert.setHeaderText("Processing is active");
            alert.setContentText("Exiting will cancel all running processes. Continue?");
            ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                controller.cancelBatch();
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                saveApplicationState();
            }
        } else {
            saveApplicationState();
        }
    }

    // ========================================================================
    //  Dialogs
    // ========================================================================

    private void showCancelConfirmation() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Cancel Processing");
        alert.setHeaderText("Batch processing is currently running");
        alert.setContentText("Are you sure you want to cancel all running processes?");
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            SoundManager.playClick();  // ✅ ADDED - Click sound for cancel confirmation
            log("⏹️ Cancelling batch processing...");
            controller.cancelBatch();
        }
    }

    private void showScheduleDialog() {
        SoundManager.playClick();  // ✅ ADDED - Click sound for schedule dialog
        // Check if batch queue is empty
        if (batchFiles.isEmpty()) {
            log("❌ Cannot schedule: batch queue is empty");
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Cannot Schedule");
            alert.setHeaderText("Batch Queue is Empty");
            alert.setContentText("Please add files to the batch queue before scheduling.");
            ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
            alert.showAndWait();
            return;
        }

        Studio studio = Studio.getInstance();
        if (studio == null) {
            log("❌ Studio instance not available");
            showError("Scheduler Error", "The application studio is not available. Please restart the application.");
            return;
        }

        BatchScheduler scheduler = studio.getBatchScheduler();
        if (scheduler == null) {
            log("❌ Batch scheduler not available");
            showError("Scheduler Error", "The batch scheduler is not available. Please restart the application.");
            return;
        }

        // Check if a batch is already scheduled
        if (scheduler.isScheduled()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Scheduled Batch Exists");
            confirm.setHeaderText("A batch is already scheduled");
            confirm.setContentText("Do you want to replace the existing scheduled batch?");
            confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
            ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);

            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.YES) {
                log("📅 Schedule operation cancelled - existing batch preserved");
                return;
            }
            // Cancel the existing schedule
            scheduler.cancelScheduledBatch();
            log("📅 Existing scheduled batch cancelled");
        }

        // Show the scheduling dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Schedule Batch Processing");
        dialog.setHeaderText("Schedule the current batch to run later");
        dialog.setResizable(true);

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setMinWidth(400);

        // Batch info
        Label batchInfo = new Label(String.format("📊 Current batch: %d files, total duration: %s", 
            batchFiles.size(), appState.getTotalDuration()));
        batchInfo.setWrapText(true);
        batchInfo.setStyle("-fx-font-size: 13px;");

        // Separator
        Separator separator = new Separator();

        // Time selection
        Label timeLabel = new Label("Select start time:");
        timeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        HBox timeBox = new HBox(10);
        timeBox.setAlignment(Pos.CENTER_LEFT);

        // Hour combo
        ComboBox<String> hourCombo = new ComboBox<>();
        for (int i = 1; i <= 12; i++) {
            hourCombo.getItems().add(String.format("%02d", i));
        }
        hourCombo.setValue("12");
        hourCombo.setPrefWidth(70);

        // Minute combo
        ComboBox<String> minuteCombo = new ComboBox<>();
        for (int i = 0; i < 60; i += 5) {
            minuteCombo.getItems().add(String.format("%02d", i));
        }
        minuteCombo.setValue("00");
        minuteCombo.setPrefWidth(70);

        // AM/PM combo
        ComboBox<String> amPmCombo = new ComboBox<>();
        amPmCombo.getItems().addAll("AM", "PM");
        amPmCombo.setValue("AM");
        amPmCombo.setPrefWidth(70);

        timeBox.getChildren().addAll(
            new Label("at"),
            hourCombo,
            new Label(":"),
            minuteCombo,
            amPmCombo
        );

        // Current time display
        Label currentTimeLabel = new Label("Current time: " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
        currentTimeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        // Status label for existing schedule
        Label statusLabel = new Label();
        if (scheduler.isScheduled()) {
            LocalDateTime scheduledTime = scheduler.getScheduledTime();
            if (scheduledTime != null) {
                statusLabel.setText("⚠️ Currently scheduled for: " +
                        scheduledTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a")));
                statusLabel.setStyle("-fx-text-fill: #ed6c02; -fx-font-weight: bold;");
            }
        }

        // Estimated completion time
        Label completionLabel = new Label();
        completionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        // Add live preview of estimated completion
        timeBox.lookupAll(".combo-box").forEach(node -> {
            if (node instanceof ComboBox) {
                ((ComboBox<?>) node).valueProperty().addListener((obs, oldVal, newVal) -> {
                    updateCompletionLabel(hourCombo, minuteCombo, amPmCombo, completionLabel);
                });
            }
        });

        content.getChildren().addAll(
            batchInfo,
            separator,
            timeLabel,
            timeBox,
            currentTimeLabel,
            statusLabel,
            completionLabel
        );

        dialog.getDialogPane().setContent(content);

        ButtonType scheduleBtn = new ButtonType("Schedule", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType clearBtn = new ButtonType("Clear Schedule", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(scheduleBtn, cancelBtn, clearBtn);

        // Set default button
        Button scheduleButton = (Button) dialog.getDialogPane().lookupButton(scheduleBtn);
        if (scheduleButton != null) {
            scheduleButton.setDefaultButton(true);
        }

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        // Show dialog and handle response
        dialog.showAndWait().ifPresent(response -> {
            if (response == scheduleBtn) {
                handleScheduleConfirmation(hourCombo, minuteCombo, amPmCombo, scheduler);
            } else if (response == clearBtn) {
                scheduler.cancelScheduledBatch();
                SoundManager.playClick();  // ✅ ADDED - Click sound for clearing schedule
                log("📅 Scheduled batch cancelled");
                showInfo("Schedule Cancelled", "The scheduled batch has been cancelled.");
            }
        });
    }

    /**
    * Updates the estimated completion time label.
    */
   private void updateCompletionLabel(ComboBox<String> hourCombo, ComboBox<String> minuteCombo,
                                      ComboBox<String> amPmCombo, Label completionLabel) {
       try {
           int hour = Integer.parseInt(hourCombo.getValue());
           int minute = Integer.parseInt(minuteCombo.getValue());
           boolean isPM = "PM".equals(amPmCombo.getValue());

           if (isPM && hour != 12) hour += 12;
           if (!isPM && hour == 12) hour = 0;

           LocalTime time = LocalTime.of(hour, minute);
           LocalDateTime scheduledTime = LocalDateTime.of(LocalDate.now(), time);

           if (scheduledTime.isBefore(LocalDateTime.now())) {
               scheduledTime = scheduledTime.plusDays(1);
           }

           long estimatedProcessingMs = estimateBatchProcessingTime();
           LocalDateTime estimatedCompletion = scheduledTime.plus(java.time.Duration.ofMillis(estimatedProcessingMs));

           completionLabel.setText("Estimated completion: " + 
               estimatedCompletion.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a")));
           completionLabel.setVisible(true);
           completionLabel.setManaged(true);
       } catch (Exception e) {
           completionLabel.setVisible(false);
           completionLabel.setManaged(false);
       }
   }

    /**
     * Estimates total batch processing time in milliseconds.
     */
    private long estimateBatchProcessingTime() {
        if (batchFiles.isEmpty()) return 0;

        if (timeEstimator != null) {
            long estimatedTotal = timeEstimator.getBatchTimeEstimate().totalTimeMs;
            if (estimatedTotal > 0) {
                return estimatedTotal;
            }
        }

        double totalSizeMB = 0;
        for (BatchFileItem item : batchFiles) {
            totalSizeMB += item.getFile().length() / (1024.0 * 1024.0);
        }

        return (long) (totalSizeMB * 2000);
    }

    /**
     * Handles the schedule confirmation.
     */
    private void handleScheduleConfirmation(ComboBox<String> hourCombo, ComboBox<String> minuteCombo,
                                           ComboBox<String> amPmCombo, BatchScheduler scheduler) {
        try {
            int hour = Integer.parseInt(hourCombo.getValue());
            int minute = Integer.parseInt(minuteCombo.getValue());
            boolean isPM = "PM".equals(amPmCombo.getValue());

            if (isPM && hour != 12) hour += 12;
            if (!isPM && hour == 12) hour = 0;

            LocalTime time = LocalTime.of(hour, minute);
            LocalDateTime scheduledTime = LocalDateTime.of(LocalDate.now(), time);

            if (scheduledTime.isBefore(LocalDateTime.now())) {
                scheduledTime = scheduledTime.plusDays(1);
                log("📅 Scheduled time is in the past - scheduling for tomorrow");
            }

            scheduler.scheduleBatch(scheduledTime, batchFiles, batchProcessor);
            log("📅 Batch scheduled for: " +
                    scheduledTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a")));

            Alert confirm = new Alert(Alert.AlertType.INFORMATION);
            confirm.setTitle("Batch Scheduled");
            confirm.setHeaderText("✅ Batch processing scheduled");
            confirm.setContentText(String.format(
                "Your batch of %d files will start automatically at:\n%s\n\n" +
                "Estimated completion: %s",
                batchFiles.size(),
                scheduledTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a")),
                estimateCompletionTime(scheduledTime)
            ));
            ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);
            confirm.showAndWait();

        } catch (NumberFormatException e) {
            log("❌ Invalid time format");
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Invalid Time");
            alert.setHeaderText("Invalid time format");
            alert.setContentText("Please select valid hour and minute values.");
            ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
            alert.showAndWait();
        }
    }

    /**
    * Estimates completion time for a scheduled batch.
    */
   private String estimateCompletionTime(LocalDateTime scheduledTime) {
       long estimatedMs = estimateBatchProcessingTime();
       LocalDateTime completion = scheduledTime.plus(java.time.Duration.ofMillis(estimatedMs));
       return completion.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a"));
   }

    private void showPerformanceReportDialog() {
        SoundManager.playClick();  // ✅ ADDED - Click sound for performance report
        new PerformanceReportDialog().show(batchProcessor.getRecentTimingReports());
    }

    private void clearTimeEstimationData() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear Time Estimation Data");
        confirm.setHeaderText("Clear all learned time estimation data?");
        confirm.setContentText("This will reset the time estimator to use default values. Continue?");
        ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK && timeEstimator != null) {
                SoundManager.playClick();  // ✅ ADDED - Click sound for clearing time data
                timeEstimator.clearSavedData();
                log("🗑️ Time estimation data cleared");
                if (controlPanel != null) {
                    controlPanel.updateTimeEstimates(batchFiles, System.currentTimeMillis());
                }
            }
        });
    }

    private void toggleRestApi() {
        SoundManager.playClick();  // ✅ ADDED - Click sound for REST API toggle
        if (restApiServer != null && restApiServer.isRunning()) {
            restApiServer.stop();
            restApiServer = null;
            log("🌐 REST API stopped.");
            showInfo("REST API Stopped", "The REST API server has been stopped.");
            updateRestApiMenuState(false);
            return;
        }

        // Show port selection dialog with documentation
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Start REST API");
        dialog.setHeaderText("🌐 Start the local REST API for headless/scripted operation");
        dialog.setResizable(true);

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setMinWidth(450);

        // API Documentation
        Label docTitle = new Label("API Documentation:");
        docTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        TextArea docArea = new TextArea(
            "POST /api/jobs    - Submit a file for transcription\n" +
            "  Body: {\"filePath\": \"/path/to/audio.mp3\"}\n" +
            "  Response: {\"jobId\": \"...\", \"status\": \"queued\"}\n\n" +
            "GET /api/jobs     - List all jobs\n" +
            "  Response: {\"jobs\": [...]}\n\n" +
            "GET /api/jobs/{id} - Get job status\n" +
            "  Response: {\"jobId\": \"...\", \"status\": \"completed|failed|processing\", \"outputDirectory\": \"...\"}\n\n" +
            "GET /api/health   - Check if API is running\n" +
            "  Response: {\"status\": \"ok\"}"
        );
        docArea.setEditable(false);
        docArea.setWrapText(true);
        docArea.setPrefHeight(180);
        docArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; -fx-font-size: 12px;");

        // Port input
        HBox portBox = new HBox(10);
        portBox.setAlignment(Pos.CENTER_LEFT);

        Label portLabel = new Label("Port:");
        portLabel.setStyle("-fx-font-weight: bold;");

        TextField portField = new TextField("8756");
        portField.setPrefWidth(100);
        portField.setPromptText("Port number");

        Label noteLabel = new Label("(localhost only - not reachable from other machines)");
        noteLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        portBox.getChildren().addAll(portLabel, portField, noteLabel);

        content.getChildren().addAll(
            docTitle,
            docArea,
            new Separator(),
            portBox
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(
            new ButtonType("Start", ButtonBar.ButtonData.OK_DONE),
            ButtonType.CANCEL
        );

        // Set default button
        Button startButton = (Button) dialog.getDialogPane().lookupButton(
            dialog.getDialogPane().getButtonTypes().stream()
                .filter(bt -> "Start".equals(bt.getText()))
                .findFirst()
                .orElse(null)
        );
        if (startButton != null) {
            startButton.setDefaultButton(true);
        }

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != dialog.getDialogPane().getButtonTypes().get(0)) {
            return;
        }

        // Parse port
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                showError("Invalid Port", "Port must be between 1 and 65535.");
                return;
            }
        } catch (NumberFormatException e) {
            showError("Invalid Port", "Please enter a valid port number.");
            return;
        }

        // Check if port is already in use
        if (isPortInUse(port)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Port in Use");
            confirm.setHeaderText("⚠️ Port " + port + " is already in use");
            confirm.setContentText("Do you want to try a different port?");
            confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
            ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);

            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isPresent() && choice.get() == ButtonType.YES) {
                toggleRestApi();
            }
            return;
        }

        // Start the API
        restApiServer = new RestApiServer(
                batchProcessor,
                () -> configurationPanel.getProcessingConfig(),
                () -> configurationPanel.getTranscriptionConfig()
        );

        try {
            restApiServer.start(port);
            log("🌐 REST API started on http://127.0.0.1:" + port);
            showInfo("REST API Started", 
                "✅ REST API is running on http://127.0.0.1:" + port + "\n\n" +
                "📋 Use the endpoints shown in the dialog to submit jobs.\n" +
                "🔒 The API is only accessible from localhost for security."
            );
            updateRestApiMenuState(true);

            Platform.runLater(() -> {
                if (logArea != null) {
                    logArea.appendText("[🌐] REST API server running on port " + port + "\n");
                }
            });
        } catch (IOException e) {
            LOGGER.error("Failed to start REST API on port {}: {}", port, e.getMessage());
            showError("Could Not Start REST API", 
                "Failed to start REST API on port " + port + ":\n" + e.getMessage() + 
                "\n\nTry a different port or check if the port is already in use.");
            restApiServer = null;
            updateRestApiMenuState(false);
        }
    }

    /**
     * Checks if a port is already in use.
     */
    private boolean isPortInUse(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            socket.setReuseAddress(true);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Updates the REST API menu item state.
     */
    private void updateRestApiMenuState(boolean running) {
        if (uiCreator != null) {
            uiCreator.setRestApiMenuItem(running);
        }
    }

    private void showPrivacyDisclosureIfNeeded() {
        if (prefManager.getInt("privacy_disclosure_acknowledged_version", 0) >= PRIVACY_DISCLOSURE_VERSION) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Before You Start");
        alert.setHeaderText("What This App Does Over the Network");
        alert.getButtonTypes().setAll(new ButtonType("I Understand", ButtonBar.ButtonData.OK_DONE));

        TextArea body = new TextArea(
                "Audio processing and transcription run entirely on this computer. "
                + "Your audio files and their transcripts are never sent anywhere for these steps.\n\n"
                + "Two optional features do send data externally, only if you turn them on:\n\n"
                + "• Speaker diarization — if you add your own HuggingFace token in "
                + "Preferences, audio segments are sent to HuggingFace's pyannote models "
                + "to identify who's speaking. Off by default.\n\n"
                + "• Translation — nothing is sent anywhere unless you configure a "
                + "translation server endpoint yourself in Preferences.");
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefSize(520, 340);

        alert.getDialogPane().setContent(body);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();

        prefManager.putInt("privacy_disclosure_acknowledged_version", PRIVACY_DISCLOSURE_VERSION);
        prefManager.flush();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    private void showFfmpegAwareErrorAlert(String title, String header, Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
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
        } else if (cause instanceof audiomanager.exceptions.AudioManagerException ame) {
            String message = ame.getUserMessage();
            if (!ame.isRecoverable()) {
                message += "\n\nThis batch/file cannot be retried automatically — please check the "
                        + "issue above before trying again.";
            }
            alert.setContentText(message);
        } else if (cause instanceof audiomanager.exceptions.ModelDownloadException mde) {
            alert.setContentText(mde.getUserFriendlyMessage());
        } else {
            alert.setContentText(cause.getMessage() != null ? cause.getMessage() : ex.getMessage());
        }

        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
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

    // ========================================================================
    //  Batch Statistics
    // ========================================================================

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
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    private String formatDurationMillis(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }

    // ========================================================================
    //  Folder Watch
    // ========================================================================

    private void toggleFolderWatch() {
        SoundManager.playClick();  // ✅ ADDED - Click sound for folder watch toggle
        if (folderWatcher != null) {
            stopFolderWatch();
            return;
        }

        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Select Folder to Watch");
        File dir = chooser.showDialog(stage);
        if (dir == null) return;

        try {
            folderWatcher = new FolderWatcher(dir.getAbsolutePath(), file ->
                Platform.runLater(() -> {
                    if (fileSelectionPanel != null) {
                        fileSelectionPanel.addFile(file);
                        log("📁 Watch folder: added new file " + file.getName());
                    }
                }), errorReporter);

            folderWatcherThread = new Thread(folderWatcher, "FolderWatcher");
            folderWatcherThread.setDaemon(true);
            folderWatcherThread.start();

            if (watchFolderMenuItem != null) {
                watchFolderMenuItem.setText("📁 Stop Watching Folder");
            }
            log("📁 Watching folder for new files: " + dir.getAbsolutePath());

        } catch (Exception e) {
            LOGGER.error("Failed to start folder watcher", e);
            log("❌ Could not watch folder: " + e.getMessage());
            folderWatcher = null;
        }
    }

    private void stopFolderWatch() {
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

    // ========================================================================
    //  Logging
    // ========================================================================

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            if (logArea != null) {
                logArea.appendText(String.format("[%s] %s%n", timestamp, message));
                int length = logArea.getLength();
                if (length > LOG_AREA_MAX_CHARS) {
                    logArea.deleteText(0, length - LOG_AREA_MAX_CHARS);
                }
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });
        String cleanMessage = EMOJI_PATTERN.matcher(message).replaceAll("").trim();
        LOGGER.info(cleanMessage);
    }

    private void setStyled(javafx.scene.Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    // ========================================================================
    //  Stage Configuration
    // ========================================================================

    private void configureStage() {
        LicenseManager license = LicenseManager.getInstance();
        String titleSuffix = license.isPro() ? " - Pro" : " - Free";
        stage.setTitle(AppConstants.APP_TITLE + titleSuffix);
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

    // ========================================================================
    //  Getters
    // ========================================================================

    public BatchProcessor getBatchProcessor() {
        return batchProcessor;
    }

    public PreferenceManager getPreferenceManager() {
        return prefManager;
    }

    public AppState getAppState() {
        return appState;
    }
    
    /**
    * Clears all session data with confirmation.
    */
    private void clearSessionData() {
        // First, check if processing is active
        if (controller.isProcessing()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Cannot Clear Session");
            alert.setHeaderText("⚠️ Processing is Active");
            alert.setContentText("Cannot clear session data while batch processing is running.\n" +
                                "Please wait for processing to complete or cancel it first.");
            ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
            alert.showAndWait();
            return;
        }

        // Show confirmation dialog with details
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear Session Data");
        confirm.setHeaderText("🗑️ Clear All Session Data?");

        // Build detailed content
        StringBuilder details = new StringBuilder();
        details.append("This will clear:\n\n");
        details.append("• ").append(batchFiles.size()).append(" files from the batch queue\n");

        if (timeEstimator != null) {
            int learnedPatterns = timeEstimator.getLearnedPatternCount();
            if (learnedPatterns > 0) {
                details.append("• ").append(learnedPatterns).append(" learned time estimation patterns\n");
            }
        }

        details.append("• All log messages from the terminal\n");
        details.append("• Application state (will reset to initial state)\n\n");
        details.append("⚠️ This action cannot be undone!");

        TextArea detailsArea = new TextArea(details.toString());
        detailsArea.setEditable(false);
        detailsArea.setWrapText(true);
        detailsArea.setPrefSize(400, 180);
        detailsArea.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-font-size: 13px;");

        confirm.getDialogPane().setContent(detailsArea);
        confirm.getButtonTypes().setAll(
            new ButtonType("Clear All", ButtonBar.ButtonData.OK_DONE),
            ButtonType.CANCEL
        );

        // Style the clear button
        Button clearButton = (Button) confirm.getDialogPane().lookupButton(
            confirm.getDialogPane().getButtonTypes().stream()
                .filter(bt -> "Clear All".equals(bt.getText()))
                .findFirst()
                .orElse(null)
        );
        if (clearButton != null) {
            clearButton.getStyleClass().add("action-btn-danger");
        }

        ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
            log("🗑️ Session data clearing cancelled");
            return;
        }

        // Perform the clearing
        try {
            SoundManager.playClick();  // ✅ ADDED - Click sound for session clear

            // 1. Clear batch queue
            int clearedFiles = batchFiles.size();
            batchFiles.clear();
            log("🗑️ Cleared " + clearedFiles + " files from batch queue");

            // 2. Clear time estimation data
            if (timeEstimator != null) {
                timeEstimator.clearSavedData();
                log("🗑️ Cleared time estimation data");
            }

            // 3. Clear log area
            if (logArea != null) {
                Platform.runLater(() -> {
                    logArea.clear();
                    logArea.appendText("[Session cleared at " + 
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "]\n");
                });
            }

            // 4. Reset application state
            appState.reset();
            log("🗑️ Application state reset");

            // 5. Reset batch stats in UI
            if (fileSelectionPanel != null) {
                fileSelectionPanel.updateBatchQueueTotals();
                fileSelectionPanel.updateBatchStatus(batchFiles);
            }

            // 6. Clear control panel status
            if (controlPanel != null) {
                controlPanel.updateStatus("Ready", "Session data cleared");
            }

            // 7. Update UI
            Platform.runLater(() -> {
                if (configurationPanel != null) {
                    configurationPanel.refreshUI();
                }
            });

            // Show success message
            showInfo("Session Cleared", 
                "✅ Session data has been cleared successfully.\n\n" +
                "• " + clearedFiles + " files removed from queue\n" +
                "• Time estimation data reset\n" +
                "• Log area cleared\n" +
                "• Application state reset to default\n\n" +
                "The application is ready for a fresh session.");

        } catch (Exception e) {
            LOGGER.error("Failed to clear session data", e);
            showError("Error Clearing Session", 
                "An error occurred while clearing session data:\n" + e.getMessage());
        }
    }
}