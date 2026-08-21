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
import audiomanager.util.TimeLeftEstimator;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Main application window.
 * 
 * <p>This class is now focused primarily on UI construction and event handling.
 * All business logic (batch processing, dependency checks, state management)
 * is delegated to MainController.</p>
 */
public class MainWindow implements BatchProcessor.FileCompletionCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindow.class);

    // ===== Dependencies =====
    private final Stage stage;
    private PreferenceManager prefManager;
    private final DependencyManager dependencyManager;
    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final TimeLeftEstimator timeEstimator;
    private final BatchProcessor batchProcessor;
    private final MainController mainController;
    private final AppState appState = AppState.getInstance();

    // ===== UI Components =====
    private FileSelectionPanel fileSelectionPanel;
    private ConfigurationPanel configurationPanel;
    private ControlPanel controlPanel;
    private TextArea logArea;

    // ===== Tools =====
    private final AudioSplitterTool audioSplitter;
    private final FileCombinerTool fileCombiner;
    private final SoundRecorderPanel soundRecorderPanel;

    // ===== State =====
    private final ObservableList<BatchFileItem> batchFiles;
    private Timeline timeUpdateTimeline;

    private final Set<BatchFileItem> countedCompleted =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BatchFileItem> countedFailed =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int originalBatchSize = 0;

    // ===== Folder Watch =====
    private FolderWatcher folderWatcher;
    private Thread folderWatcherThread;
    private MenuItem watchFolderMenuItem;

    // ===== REST API =====
    private RestApiServer restApiServer;

    // ===== Constants =====
    private static final int PRIVACY_DISCLOSURE_VERSION = 2;
    private static final int LOG_AREA_MAX_CHARS = 500_000;
    private static final java.util.regex.Pattern EMOJI_PATTERN =
            java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\uFE0F]");
    private static final java.util.regex.Pattern FONT_SIZE_RULE_PATTERN =
            java.util.regex.Pattern.compile("-fx-font-size:\\s*[^;]+;?\\s*");
    
    private ErrorReporter errorReporter;

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
        this.configurationPanel = new ConfigurationPanel(prefManager);

        // BatchProcessor now uses simplified architecture internally
        this.batchProcessor = new BatchProcessor(
                audioProcessor,
                transcriptionService,
                timeEstimator,
                prefManager,
                this::log,
                this,  // FileCompletionCallback
                batchFiles,
                errorReporter
        );

        // Create MainController
        this.mainController = new MainController(
            appState,
            batchProcessor,
            dependencyManager,
            timeEstimator,
            prefManager,
            this::log,
            errorReporter
        );

        // Tools
        this.audioSplitter = new AudioSplitterTool(dependencyManager, prefManager);
        this.audioSplitter.setLogger(this::log);

        this.fileCombiner = new FileCombinerTool(prefManager);
        this.fileCombiner.setLogger(this::log);

        this.soundRecorderPanel = new SoundRecorderPanel(batchFiles, prefManager, this::log, errorReporter);

        LOGGER.info("Application components initialized with simplified architecture");
    }

    // ========================================================================
    //  Initialization
    // ========================================================================

    public void initialize() {
        // Load license
        LicenseManager license = LicenseManager.getInstance();
        license.loadLicense();

        configureStage();
        Scene scene = createScene();
        setupKeyboardShortcuts(scene);
        stage.setScene(scene);

        // Apply theme
        toggleTheme("Dark".equals(prefManager.getTheme()));

        // Load preferences
        configurationPanel.setFontSizeChangeListener(this::applyFontSize);
        loadPreferences();
        applyFontSize(prefManager.getFontSize());
        restoreWindowState();

        // Setup timers and state
        setupTimeUpdater();
        restoreBatchQueueState();
        setupEventHandlers();

        // Bind clear queue button
        if (fileSelectionPanel != null && batchProcessor != null) {
            fileSelectionPanel.getClearQueueButton().disableProperty().bind(
                    batchProcessor.isRunningProperty()
            );
        }

        // Show privacy disclosure if needed
        showPrivacyDisclosureIfNeeded();

        // Show the window
        stage.show();

        // Check dependencies in background
        CompletableFuture.runAsync(this::checkDependencies);

        // Update title with license status
        String titleSuffix = license.isPro() ? " - Pro" : " - Free";
        stage.setTitle(AppConstants.APP_TITLE + " v" + AppConstants.APP_VERSION + titleSuffix);
    }

    // ========================================================================
    //  UI Creation
    // ========================================================================

    private void setupControlPanel() {
        controlPanel = new ControlPanel(
            appState,
            this::handleProcessButtonClick,
            this::handleExitButtonClick,
            timeEstimator
        );
        controlPanel.setScheduleAction(this::showScheduleDialog);
    }

    private void setupKeyboardShortcuts(Scene scene) {
        scene.getAccelerators().put(
                javafx.scene.input.KeyCombination.keyCombination("Shortcut+R"),
                this::handleProcessButtonClick
        );
        scene.getAccelerators().put(
                javafx.scene.input.KeyCombination.keyCombination("Shortcut+O"),
                () -> { if (fileSelectionPanel != null) fileSelectionPanel.triggerBrowse(); }
        );
        scene.getAccelerators().put(
                javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+O"),
                () -> { if (fileSelectionPanel != null) fileSelectionPanel.triggerOutputDirectoryChooser(); }
        );
    }

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
    //  Batch Processing - Delegated to MainController
    // ========================================================================

    @Override
    public void onFileCompleted(BatchFileItem item, boolean wasSuccessful) {
        Platform.runLater(() -> {
            if (wasSuccessful) {
                countedCompleted.add(item);
                appState.setStatus("File Completed", "✅ " + item.getFileName());
                if (fileSelectionPanel != null) {
                    fileSelectionPanel.removeItemFromBatchQueue(item);
                }
            } else {
                countedFailed.add(item);
                appState.setStatus("File Failed", "❌ " + item.getFileName());
            }

            int completed = countedCompleted.size();
            int failed = countedFailed.size();
            int total = originalBatchSize;
            int pending = Math.max(0, total - completed - failed);
            appState.updateBatchStats(total, completed, failed, pending);
        });
    }

    private void handleProcessButtonClick() {
        if (mainController.isProcessing()) {
            showCancelConfirmation();
            mainController.cancelBatch();
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

        // Reset cumulative tracking
        countedCompleted.clear();
        countedFailed.clear();
        originalBatchSize = batchFiles.size();

        // Reset UI state
        if (fileSelectionPanel != null) {
            fileSelectionPanel.resetBatchStatus();
        }

        // Get configuration
        ProcessingConfig processingConfig = configurationPanel.getProcessingConfig();
        TranscriptionConfig transcriptionConfig = configurationPanel.getTranscriptionConfig();
        int maxParallel = configurationPanel.getMaxParallelFiles();

        // Delegate to MainController
        mainController.startBatch(batchFiles, processingConfig, transcriptionConfig, maxParallel);

        // Start time updater
        if (timeUpdateTimeline == null) {
            setupTimeUpdater();
        }
        timeUpdateTimeline.play();
    }

    // ========================================================================
    //  Dependency Management - Delegated to MainController
    // ========================================================================

    private void checkDependencies() {
        mainController.checkDependencies();
    }

    // ========================================================================
    //  Time Updater
    // ========================================================================

    private void setupTimeUpdater() {
        timeUpdateTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (mainController.isProcessing() && timeEstimator != null) {
                // Update time estimates
                appState.updateTimeEstimates(
                    timeEstimator.getCurrentFileTimeSpent(),
                    timeEstimator.getLiveCurrentFileTimeLeftMs(),
                    timeEstimator.getTotalTimeSpent(),
                    timeEstimator.getLiveTotalTimeLeftMs()
                );

                // Update progress via aggregator
                double progress = BatchProgressAggregator.compute(batchFiles)
                    .getOverallProgressPercent() / 100.0;
                appState.setOverallProgress(progress);

                // Count cancelled files as failed
                for (BatchFileItem item : batchFiles) {
                    if ("CANCELLED".equals(item.getStatus())) {
                        countedFailed.add(item);
                    }
                }

                int completed = countedCompleted.size();
                int failed = countedFailed.size();
                int total = originalBatchSize;
                int pending = Math.max(0, total - completed - failed);

                appState.updateBatchStats(total, completed, failed, pending);

                // Auto-remove completed files if enabled
                if (configurationPanel.isAutoRemoveCompleted()) {
                    batchFiles.removeIf(item -> "COMPLETED".equals(item.getStatus()));
                }
            }
        }));
        timeUpdateTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    // ========================================================================
    //  Folder Watch
    // ========================================================================

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

        } catch (IOException e) {
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
    //  REST API
    // ========================================================================

    private void toggleRestApi(MenuItem menuItem) {
        if (restApiServer != null && restApiServer.isRunning()) {
            restApiServer.stop();
            restApiServer = null;
            menuItem.setText("🌐 Start REST API...");
            log("🌐 REST API stopped.");
            return;
        }

        TextInputDialog portDialog = new TextInputDialog("8756");
        portDialog.setTitle("Start REST API");
        portDialog.setHeaderText("Start the local REST API for headless/scripted operation.\n"
                + "Binds to 127.0.0.1 only — not reachable from other machines.");
        portDialog.setContentText("Port:");
        Optional<String> portInput = portDialog.showAndWait();
        if (portInput.isEmpty()) return;

        int port;
        try {
            port = Integer.parseInt(portInput.get().trim());
        } catch (NumberFormatException e) {
            showInfoAlert("Invalid port", "Enter a numeric port, e.g. 8756.");
            return;
        }

        restApiServer = new RestApiServer(
                batchProcessor,
                () -> configurationPanel.getProcessingConfig(),
                () -> configurationPanel.getTranscriptionConfig()
        );

        try {
            restApiServer.start(port);
            menuItem.setText("🌐 Stop REST API (port " + port + ")");
            log("🌐 REST API started on http://127.0.0.1:" + port
                    + " — POST /api/jobs with {\"filePath\":\"...\"} to submit a file using current settings.");
        } catch (Exception e) {
            LOGGER.error("Failed to start REST API on port {}: {}", port, e.getMessage());
            showInfoAlert("Could not start REST API", "Port " + port + " may already be in use: " + e.getMessage());
            restApiServer = null;
        }
    }

    // ========================================================================
    //  Error Handling
    // ========================================================================

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

    // ========================================================================
    //  UI Styling Helpers
    // ========================================================================

    private void setStyled(Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    private void applyFontSize(double size) {
        LOGGER.debug("Applying font size: {}px", size);

        Scene scene = stage.getScene();
        if (scene != null) {
            String style = String.format("-fx-font-size: %spx;", (int) size);
            scene.getRoot().setStyle("-fx-font-family: 'Segoe UI', 'Roboto', 'Arial'; " + style);
        }

        if (logArea != null) {
            setStyled(logArea, String.format("-fx-font-size: %spx;", (int) size));
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

            LOGGER.debug("Applied font size to all UI components");
        } catch (Exception e) {
            LOGGER.warn("Failed to apply font size to some UI components", e);
        }
    }

    private void applyFontSizeRule(Node node, double size) {
        String existing = node.getStyle();
        String withoutFontSize = (existing == null || existing.isBlank())
                ? "" : FONT_SIZE_RULE_PATTERN.matcher(existing).replaceAll("").trim();
        String separator = withoutFontSize.isEmpty() || withoutFontSize.endsWith(";") ? " " : "; ";
        setStyled(node, withoutFontSize + separator + String.format("-fx-font-size: %spx;", (int) size));
    }

    // ========================================================================
    //  Theme
    // ========================================================================

    private void toggleTheme(boolean dark) {
        Scene scene = stage.getScene();
        if (scene == null) return;

        String stylesheetUri = getClass().getResource("/styles/dark.css") != null
                ? getClass().getResource("/styles/dark.css").toExternalForm()
                : null;

        if (dark) {
            if (stylesheetUri != null && !scene.getStylesheets().contains(stylesheetUri)) {
                scene.getStylesheets().add(stylesheetUri);
            }
            prefManager.setTheme("Dark");
        } else {
            if (stylesheetUri != null) {
                scene.getStylesheets().remove(stylesheetUri);
            }
            prefManager.setTheme("Light");
        }

        ThemeManager.sweep(scene, dark);
        prefManager.flush();
        LOGGER.info("Theme set to: {}", prefManager.getTheme());
    }

    // ========================================================================
    //  Logging
    // ========================================================================

    private enum LogLevel {
        INFO(""), SUCCESS("✅ "), WARNING("⚠️ "), ERROR("❌ "), PROCESSING("🔄 ");
        private final String prefix;
        LogLevel(String prefix) { this.prefix = prefix; }
    }

    private void log(String message) {
        log(message, LogLevel.INFO);
    }

    private void log(String message, LogLevel level) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String displayMessage = level.prefix + message;
        Platform.runLater(() -> {
            logArea.appendText(String.format("[%s] %s%n", timestamp, displayMessage));
            int length = logArea.getLength();
            if (length > LOG_AREA_MAX_CHARS) {
                logArea.deleteText(0, length - LOG_AREA_MAX_CHARS);
            }
            logArea.setScrollTop(Double.MAX_VALUE);
        });

        String cleanMessage = EMOJI_PATTERN.matcher(displayMessage).replaceAll("").trim();
        LOGGER.info(cleanMessage);
    }

    public void showStatusMessage(String message) {
        Platform.runLater(() -> {
            if (controlPanel != null) {
                controlPanel.updateStatus(message, null);
            }
            log(message);
        });
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
            log("⏹️ Cancelling batch processing...");
            mainController.cancelBatch();
        }
    }

    private void showScheduleDialog() {
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
        if (studio == null || studio.getBatchScheduler() == null) {
            log("❌ Batch scheduler not available");
            return;
        }

        BatchScheduler scheduler = studio.getBatchScheduler();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Schedule Batch Processing");
        dialog.setHeaderText("Schedule the current batch to run later");

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setMinWidth(350);

        Label batchInfo = new Label(String.format("📊 Current batch: %d files",
                batchFiles.size()));
        batchInfo.setWrapText(true);

        HBox timeBox = new HBox(10);
        timeBox.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> hourCombo = new ComboBox<>();
        for (int i = 1; i <= 12; i++) {
            hourCombo.getItems().add(String.format("%02d", i));
        }
        hourCombo.setValue("12");
        hourCombo.setPrefWidth(70);

        ComboBox<String> minuteCombo = new ComboBox<>();
        for (int i = 0; i < 60; i += 5) {
            minuteCombo.getItems().add(String.format("%02d", i));
        }
        minuteCombo.setValue("00");
        minuteCombo.setPrefWidth(70);

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

        Label currentTimeLabel = new Label("Current time: " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm a")));
        setStyled(currentTimeLabel, "-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        Label statusLabel = new Label();
        if (scheduler.isScheduled()) {
            LocalDateTime scheduledTime = scheduler.getScheduledTime();
            if (scheduledTime != null) {
                statusLabel.setText("⚠️ Currently scheduled for: " +
                        scheduledTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a")));
                statusLabel.setStyle("-fx-text-fill: #ed6c02;");
            }
        }

        content.getChildren().addAll(
                batchInfo,
                new Separator(),
                new Label("Select start time:"),
                timeBox,
                currentTimeLabel,
                statusLabel
        );

        dialog.getDialogPane().setContent(content);

        ButtonType scheduleBtn = new ButtonType("Schedule", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType clearBtn = new ButtonType("Clear Schedule", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(scheduleBtn, cancelBtn, clearBtn);

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(response -> {
            if (response == scheduleBtn) {
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
                    confirm.setHeaderText("Batch processing scheduled");
                    confirm.setContentText(String.format(
                            "Your batch of %d files will start automatically at:\n%s",
                            batchFiles.size(),
                            scheduledTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a"))
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
            } else if (response == clearBtn) {
                scheduler.cancelScheduledBatch();
                log("📅 Scheduled batch cancelled");
            }
        });
    }

    private void showPerformanceReportDialog() {
        new PerformanceReportDialog().show(batchProcessor.getRecentTimingReports());
    }

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
        setStyled(hfLabel, "-fx-font-weight: bold;");

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
        setStyled(hfHint, "-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        Button saveTokenButton = new Button("Save Token");
        Label saveResultLabel = new Label();
        saveTokenButton.setOnAction(e -> {
            String token = tokenField.getText();
            if (token == null || token.isBlank()) {
                saveResultLabel.setText("❌ Enter a token first.");
                setStyled(saveResultLabel, "-fx-text-fill: #d32f2f;");
                return;
            }
            try {
                Files.createDirectories(tokenFile.getParent());
                Files.writeString(tokenFile, token.trim());
                saveResultLabel.setText("✅ Saved. Takes effect for new transcriptions.");
                setStyled(saveResultLabel, "-fx-text-fill: #2e7d32;");
                log("🔑 HuggingFace token saved to " + tokenFile);
                tokenField.clear();
            } catch (IOException ex) {
                LOGGER.error("Failed to save HF token", ex);
                saveResultLabel.setText("❌ Could not save: " + ex.getMessage());
                setStyled(saveResultLabel, "");
                saveResultLabel.getStyleClass().add("status-negative");
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

        
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait();
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
                        + "One thing to expect: the first time you transcribe audio in a given "
                        + "language, WhisperX automatically downloads a one-time alignment model "
                        + "for that language (roughly 300–400MB) to enable word-level timestamps. "
                        + "This happens once per language, not per file — after that first download, "
                        + "transcribing more audio in the same language uses what's already cached "
                        + "and needs no further download. There's currently no progress indicator for "
                        + "this specific download, so a first run in a new language may pause longer "
                        + "than usual before transcription visibly starts — that's this download "
                        + "happening in the background, not the app hanging.\n\n"
                        + "Two optional features do send data externally, only if you turn them on:\n\n"
                        + "• Speaker diarization — if you add your own HuggingFace token in "
                        + "Preferences, audio segments are sent to HuggingFace's pyannote models "
                        + "to identify who's speaking. Off by default.\n\n"
                        + "• Translation — nothing is sent anywhere unless you configure a "
                        + "translation server endpoint yourself in Preferences. This app has no "
                        + "built-in translation provider — you choose where transcribed text goes, "
                        + "if anywhere.\n\n"
                        + "The REST API, if you start it from the Tools menu, only listens on "
                        + "localhost — it's for local automation, not a network service.");
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefSize(520, 340);

        alert.getDialogPane().setContent(body);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();

        prefManager.putInt("privacy_disclosure_acknowledged_version", PRIVACY_DISCLOSURE_VERSION);
        prefManager.flush();
    }

    private void showInfoAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    // ========================================================================
    //  Preferences & State
    // ========================================================================

    private void loadPreferences() {
        configurationPanel.loadPreferences();
        if (fileSelectionPanel != null) {
            fileSelectionPanel.updateOutputDirectory(prefManager.getOutputDirectory());
        }
        LOGGER.info("Loaded font size: {}", prefManager.getFontSize());
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
                int restoredCount = 0;

                for (String filePath : filePaths) {
                    File file = new File(filePath);
                    if (file.exists() && file.isFile()) {
                        BatchFileItem item = new BatchFileItem(file);
                        fileSelectionPanel.probeAndSetDuration(item);
                        batchFiles.add(item);
                        restoredCount++;
                    }
                }

                if (restoredCount > 0) {
                    log("📁 Restored " + restoredCount + " files from previous session");
                }

                BatchState savedState = batchProcessor.loadBatchState();
                if (savedState != null && !savedState.getFiles().isEmpty()) {

                    // ========== FIX: Match by file path, NOT by array position ==========
                    Map<String, BatchState.FileState> savedByPath = new HashMap<>();
                    for (BatchState.FileState fs : savedState.getFiles()) {
                        if (fs.getFilePath() != null) {
                            savedByPath.put(fs.getFilePath(), fs);
                        }
                    }

                    // Apply saved states ONLY to matching files
                    for (BatchFileItem item : batchFiles) {
                        String absPath = item.getFile().getAbsolutePath();
                        BatchState.FileState fs = savedByPath.get(absPath);
                        if (fs != null) {
                            String status = fs.getStatus();
                            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                                item.setStatus(status);
                                item.setProgress(fs.getProgress());
                                item.setErrorMessage(fs.getErrorMessage());
                            } else {
                                // Reset non-terminal states to PENDING
                                item.setStatus(ProcessingStatus.PENDING.name());
                                item.setProgress(0.0);
                                item.setErrorMessage(null);
                            }
                        } else {
                            // No saved state - ensure PENDING
                            item.setStatus(ProcessingStatus.PENDING.name());
                            item.setProgress(0.0);
                            item.setErrorMessage(null);
                        }
                    }

                    boolean hasProcessingFile = false;
                    for (BatchState.FileState fs : savedState.getFiles()) {
                        if (ProcessingStatus.PROCESSING.name().equals(fs.getStatus())) {
                            hasProcessingFile = true;
                            break;
                        }
                    }

                    if (hasProcessingFile) {
                        log("⚠️ Previous batch was interrupted mid-file. That file will be restarted from beginning.");
                    }

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
            }
        } catch (Exception e) {
            LOGGER.error("Failed to restore batch queue state", e);
            if (errorReporter != null && errorReporter.isEnabled()) {
                errorReporter.reportError(e, "Restore batch queue state failed");
            }
        }
    }
	
    private void saveApplicationState() {
        LOGGER.info("Saving application state...");

        stopFolderWatch();
        if (restApiServer != null && restApiServer.isRunning()) {
            restApiServer.stop();
        }
        if (soundRecorderPanel != null) {
            // Releases the OS microphone line and disposes any open
            // playback -- an open TargetDataLine left dangling on app exit
            // can keep the audio input device locked on some platforms.
            soundRecorderPanel.shutdown();
        }

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

    private void saveWindowState() {
        prefManager.setWindowState(
            stage.getX(),
            stage.getY(),
            stage.getWidth(),
            stage.getHeight()
        );
    }

    private void savePreferences() {
        LOGGER.info("Starting preference save process...");

        if (configurationPanel != null) {
            try {
                configurationPanel.savePreferences();
                LOGGER.info("Configuration panel preferences saved");
            } catch (Exception e) {
                LOGGER.error("Failed to save application state", e);
                if (errorReporter != null && errorReporter.isEnabled()) {
                    errorReporter.reportError(e, "Save application state failed");
                }
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

    private void setupEventHandlers() {
        stage.setOnCloseRequest(e -> {
            if (mainController.isProcessing()) {
                e.consume();
                handleExitButtonClick();
            } else {
                saveApplicationState();
            }
        });
    }

    private void handleExitButtonClick() {
        if (mainController.isProcessing()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Exit Confirmation");
            alert.setHeaderText("Processing is active");
            alert.setContentText("Exiting will cancel all running processes. Continue?");
            ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                mainController.cancelBatch();
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                saveApplicationState();
            }
        } else {
            saveApplicationState();
        }
    }

    // ========================================================================
    //  UI Creation - Updated with Collapsible Panels
    // ========================================================================

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

        configurationPanel = new ConfigurationPanel(prefManager);
        setupControlPanel();

        // Wire configuration panel to batch processor
        batchProcessor.setConfigurationPanel(configurationPanel);

        // Register UI components with MainController
        mainController.registerUIComponents(controlPanel, fileSelectionPanel, configurationPanel);

        // Setup callbacks
        mainController.setOnBatchStart(() -> {
            LOGGER.debug("Batch started via MainController");
        });

        mainController.setOnBatchComplete(() -> {
            LOGGER.debug("Batch completed via MainController");
        });

        mainController.setOnError(ex -> {
            LOGGER.error("Error in batch processing", ex);
            showFfmpegAwareErrorAlert("Processing Error", "An error occurred during processing", ex);
        });

        // Log area
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setWrapText(true);
        setStyled(logArea, "-fx-control-inner-background: #2c3e50; -fx-text-fill: white; -fx-font-family: 'Consolas', 'Monaco', monospace;");

        // ========== CREATE COLLAPSIBLE SECTIONS ==========
        
        // 1. Sound Recorder Section (Collapsible)
        TitledPane recorderPane = createSoundRecorderPane();
        
        // 2. File Selection Section (Collapsible)
        TitledPane fileSelectionPane = createFileSelectionPane();
        
        // 3. Audio Tools Section (Collapsible) - already exists
        TitledPane toolsPane = createToolsPane();
        
        // 4. Batch Queue Section (Collapsible)
        TitledPane batchQueuePane = createBatchQueuePane();
        
        // 5. Control Section (Collapsible)
        TitledPane controlPane = createControlPane();

        // Log section (not collapsible - always visible at bottom)
        VBox logSection = new VBox(0);
        Label logLabel = new Label("📝 Terminal");
        setStyled(logLabel, "-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 15 5 15;");
        logLabel.getStyleClass().add("panel-heading");
        logSection.getChildren().addAll(logLabel, logArea);
        setStyled(logSection, "-fx-border-color: #bdc3c7; -fx-border-width: 1 0 0 0; -fx-background-color: white;");
        logSection.getStyleClass().add("theme-fix-surface");

        // Main content with all collapsible panes
        VBox mainContent = new VBox(5);
        mainContent.getChildren().addAll(
            recorderPane,
            fileSelectionPane,
            toolsPane,
            batchQueuePane,
            controlPane,
            logSection
        );

        // Make all panes start collapsed except the batch queue and control
        // This gives a clean initial view
        recorderPane.setExpanded(false);
        fileSelectionPane.setExpanded(false);
        toolsPane.setExpanded(false);
        batchQueuePane.setExpanded(true);  // Show queue by default
        controlPane.setExpanded(true);     // Show controls by default

        ScrollPane scrollPane = new ScrollPane(mainContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setStyled(scrollPane, "-fx-background-color: #ecf0f1; -fx-border-width: 0;");

        root.setTop(menuBar);
        root.setCenter(scrollPane);

        Scene scene = new Scene(root, AppConstants.DEFAULT_WINDOW_WIDTH, AppConstants.DEFAULT_WINDOW_HEIGHT);
        applyCSSIfAvailable(scene);

        return scene;
    }

    // ========================================================================
    //  Collapsible Pane Creators
    // ========================================================================

    /**
     * Creates the Sound Recorder collapsible pane.
     */
    private TitledPane createSoundRecorderPane() {
        TitledPane pane = new TitledPane();
        pane.setText("🎙️ Sound Recorder");
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        Node recorderUI = soundRecorderPanel.getRecorderSection();

        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(recorderUI);
        contentContainer.setMinHeight(200);
        contentContainer.setPrefHeight(250);

        pane.setContent(contentContainer);
        setStyled(pane, "-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0; -fx-background-color: #f8f9fa;");

        // Auto-collapse other panes when this one expands
        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && pane.getParent() instanceof VBox parent) {
                parent.getChildren().forEach(node -> {
                    if (node instanceof TitledPane otherPane && otherPane != pane) {
                        otherPane.setExpanded(false);
                    }
                });
            }
        });

        return pane;
    }

    /**
     * Creates the File Selection collapsible pane.
     */
    private TitledPane createFileSelectionPane() {
        TitledPane pane = new TitledPane();
        pane.setText("🎵 Audio File Selection");
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        // Get the file selection controls (without the title label since we have the pane title)
        VBox fileSelectionContent = fileSelectionPanel.getFileSelectionControlsWithoutTitle();
        
        // Remove the title label from the content since the pane title handles it
        // The file selection content already has its own layout

        pane.setContent(fileSelectionContent);
        setStyled(pane, "-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0; -fx-background-color: #f8f9fa;");

        // Auto-collapse other panes when this one expands
        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && pane.getParent() instanceof VBox parent) {
                parent.getChildren().forEach(node -> {
                    if (node instanceof TitledPane otherPane && otherPane != pane) {
                        otherPane.setExpanded(false);
                    }
                });
            }
        });

        return pane;
    }

    /**
     * Creates the Audio Tools collapsible pane.
     */
    private TitledPane createToolsPane() {
        TitledPane pane = new TitledPane();
        pane.setText("🛠️ Audio Tools");
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        // Get the existing tools section
        VBox toolsContainer = new VBox(0);
        setStyled(toolsContainer, "-fx-background-color: white; -fx-border-color: #bdc3c7; -fx-border-width: 1 0 1 0;");

        TitledPane splitterPane = createAudioSplitterPane();
        TitledPane combinerPane = createTextFileCombinerPane();

        toolsContainer.getChildren().addAll(splitterPane, combinerPane);
        
        // Wrap in a container with scroll if needed
        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(toolsContainer);
        contentContainer.setMinHeight(100);
        contentContainer.setPrefHeight(200);

        pane.setContent(contentContainer);
        setStyled(pane, "-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0; -fx-background-color: #f8f9fa;");

        // Auto-collapse other panes when this one expands
        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && pane.getParent() instanceof VBox parent) {
                parent.getChildren().forEach(node -> {
                    if (node instanceof TitledPane otherPane && otherPane != pane) {
                        otherPane.setExpanded(false);
                    }
                });
            }
        });

        return pane;
    }

    /**
     * Creates the Batch Queue collapsible pane.
     */
    private TitledPane createBatchQueuePane() {
        TitledPane pane = new TitledPane();
        pane.setText("📊 Batch Queue Status");
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(true); // Expanded by default

        VBox batchQueue = fileSelectionPanel.getBatchQueueSectionWithoutTitle();
        
        // Remove the title from the content since the pane title handles it
        // But keep the status grid, table, and action buttons
        // The getBatchQueueSection() already contains the title - we need to remove it
        
        // We'll reconstruct the batch queue content without the title
        VBox contentWithoutTitle = new VBox(10);
        contentWithoutTitle.setPadding(new Insets(10));
        contentWithoutTitle.getStyleClass().add("theme-fix-surface");
        
        // Get the status grid, table, and action buttons from the existing section
        // We'll use a helper method in FileSelectionPanel to get just the content
        // For now, we'll use the existing method and style it
        VBox batchContent = fileSelectionPanel.getBatchQueueSection();
        
        // Remove the title label (first child) if it exists
        if (!batchContent.getChildren().isEmpty() && batchContent.getChildren().get(0) instanceof Label) {
            batchContent.getChildren().remove(0);
        }
        
        // Make it look clean within the pane
        setStyled(batchContent, "-fx-padding: 0; -fx-background-color: white;");
        batchContent.getStyleClass().add("theme-fix-surface");

        pane.setContent(batchContent);
        setStyled(pane, "-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0; -fx-background-color: #f8f9fa;");

        // Auto-collapse other panes when this one expands
        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && pane.getParent() instanceof VBox parent) {
                parent.getChildren().forEach(node -> {
                    if (node instanceof TitledPane otherPane && otherPane != pane) {
                        otherPane.setExpanded(false);
                    }
                });
            }
        });

        return pane;
    }

    /**
     * Creates the Control Panel collapsible pane.
     */
    private TitledPane createControlPane() {
        TitledPane pane = new TitledPane();
        pane.setText("🎮 Controls");
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(true); // Expanded by default

        VBox controlSection = controlPanel.getRoot();
        
        // Remove any extra padding to fit nicely in the pane
        setStyled(controlSection, "-fx-border-color: #bdc3c7; -fx-border-width: 0; -fx-background-color: white;");
        controlSection.getStyleClass().add("theme-fix-surface");

        pane.setContent(controlSection);
        setStyled(pane, "-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0; -fx-background-color: #f8f9fa;");

        // Auto-collapse other panes when this one expands
        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && pane.getParent() instanceof VBox parent) {
                parent.getChildren().forEach(node -> {
                    if (node instanceof TitledPane otherPane && otherPane != pane) {
                        otherPane.setExpanded(false);
                    }
                });
            }
        });

        return pane;
    }

    // ========================================================================
    //  Audio Splitter & Text Combiner Panes (already exist)
    // ========================================================================

    private TitledPane createAudioSplitterPane() {
        TitledPane pane = new TitledPane();
        pane.setText("🎵 Audio Splitter");
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        Node splitterUI = audioSplitter.createUI();
        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(splitterUI);
        contentContainer.setMinHeight(320);
        contentContainer.setPrefHeight(320);

        pane.setContent(contentContainer);
        setStyled(pane, "-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0; -fx-background-color: #f8f9fa;");

        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && pane.getParent() instanceof VBox parent) {
                parent.getChildren().forEach(node -> {
                    if (node instanceof TitledPane otherPane && otherPane != pane) {
                        otherPane.setExpanded(false);
                    }
                });
            }
        });

        return pane;
    }

    private TitledPane createTextFileCombinerPane() {
        TitledPane pane = new TitledPane();
        pane.setText("📄 Text File Combiner");
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(false);

        Node combinerUI = fileCombiner.createUI();
        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(combinerUI);
        contentContainer.setMinHeight(270);
        contentContainer.setPrefHeight(270);

        pane.setContent(contentContainer);
        setStyled(pane, "-fx-border-color: #e0e0e0; -fx-border-width: 0; -fx-background-color: #f8f9fa;");

        pane.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && pane.getParent() instanceof VBox parent) {
                parent.getChildren().forEach(node -> {
                    if (node instanceof TitledPane otherPane && otherPane != pane) {
                        otherPane.setExpanded(false);
                    }
                });
            }
        });

        return pane;
    }

    // ========================================================================
    //  Menu Bar
    // ========================================================================

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();
        setStyled(menuBar, "-fx-padding: 0; -fx-background-color: #ecf0f1; -fx-border-width: 0 0 1 0; -fx-border-color: #bdc3c7;");

        // File Menu
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

        // Edit Menu
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

        // Tools Menu
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

        MenuItem restApiMenuItem = new MenuItem("🌐 Start REST API...");
        restApiMenuItem.setOnAction(e -> toggleRestApi(restApiMenuItem));

        MenuItem performanceReportItem = new MenuItem("📊 Performance Report...");
        performanceReportItem.setOnAction(e -> showPerformanceReportDialog());
        performanceReportItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+P"));

        toolsMenu.getItems().addAll(
                batchSettingsItem, whisperSettingsItem, audioSettingsItem,
                new SeparatorMenuItem(), clearTimeDataItem,
                new SeparatorMenuItem(), watchFolderMenuItem, restApiMenuItem, performanceReportItem
        );

        // View Menu
        Menu viewMenu = new Menu("View");
        viewMenu.setStyle("-fx-text-fill: #2c3e50;");

        CheckMenuItem darkModeItem = new CheckMenuItem("🌙 Dark Mode");
        darkModeItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+D"));
        darkModeItem.setSelected("Dark".equals(prefManager.getTheme()));
        darkModeItem.setOnAction(e -> toggleTheme(darkModeItem.isSelected()));

        viewMenu.getItems().add(darkModeItem);

        // Help Menu
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
        userManualItem.setOnAction(e -> new DocumentationLauncher().open("USER_MANUAL.md"));

        MenuItem troubleshootingItem = new MenuItem("Troubleshooting Guide...");
        troubleshootingItem.setOnAction(e -> new DocumentationLauncher().open("TROUBLESHOOTING.md"));
        troubleshootingItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("F1"));

        MenuItem licenseItem = new MenuItem("License Status");
        licenseItem.setOnAction(e -> showLicenseStatusDialog());

        MenuItem activateItem = new MenuItem("Activate Pro License...");
        activateItem.setOnAction(e -> showActivationDialog());

        helpMenu.getItems().addAll(aboutItem, dependenciesItem, setupAssistantItem,
                new SeparatorMenuItem(), licenseItem, activateItem, new SeparatorMenuItem(),
                userManualItem, troubleshootingItem);

        menuBar.getMenus().addAll(fileMenu, editMenu, toolsMenu, viewMenu, helpMenu);
        return menuBar;
    }

    // ========================================================================
    //  Dialog Methods (Mostly unchanged - kept for completeness)
    // ========================================================================

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

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(result -> {
            if (result == applyButton) {
                configurationPanel.savePreferences();
                double newFontSize = prefManager.getFontSize();
                applyFontSize(newFontSize);
                log("✅ UI preferences updated - Font size: " + (int) newFontSize + "px");
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

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

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

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

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

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(result -> {
            if (result == applyButton) {
                configurationPanel.savePreferences();
                log("✅ Audio processing settings updated");
            }
        });
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Aburime Sound Manager");
        alert.setHeaderText("AudioManager v4.0.0 - Phoenix");
        alert.setContentText("""
            A professional audio processing tool with advanced transcription capabilities.

            Features:
            \u2022 Batch audio file processing with adaptive concurrency
            \u2022 WhisperX transcription with speaker diarization
            \u2022 Advanced performance monitoring and reporting
            \u2022 REST API for headless automation
            \u2022 Real-time progress tracking and time estimation
            \u2022 Volume analysis and optimization

            Version: 4.0.0
            Release: Phoenix

            Built with JavaFX and WhisperX
            """);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    private void showLicenseStatusDialog() {
        LicenseManager license = LicenseManager.getInstance();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("License Status");
        alert.setHeaderText(license.isPro() ? "💎 Pro License - Active" : "📄 Free Version");
        alert.setContentText(license.isPro() ?
            "You have an active Pro license with full access to all features.\n\n" +
            "Pro Features:\n" +
            "• Batch processing (unlimited files)\n" +
            "• File size limit: 750MB\n" +
            "• Parallel processing (up to 8 files)\n" +
            "• Advanced performance reporting\n" +
            "• REST API access" :
            "You are using the Free version.\n\n" +
            "Free Features:\n" +
            "• Single file processing\n" +
            "• File size limit: 100MB\n" +
            "• Basic audio conversion\n" +
            "• Audio splitting & combining\n\n" +
            "Upgrade to Pro for batch processing and more features."
        );
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    private void showActivationDialog() {
        LicenseManager license = LicenseManager.getInstance();

        if (license.isPro()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Deactivate Pro");
            confirm.setHeaderText("Deactivate Pro License?");
            confirm.setContentText("This will revert the application to the Free version.");
            ThemeManager.applyCurrentThemeToDialog(confirm.getDialogPane(), null);
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                license.deactivateLicense();
                showInfo("License Deactivated", "You are now using the Free version.");
                // Refresh UI
                initialize();
            }
            return;
        }

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Activate Pro License");
        dialog.setHeaderText("Enter your Pro license key");
        dialog.setContentText("License Key:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(key -> {
            if (license.activateLicense(key)) {
                showInfo("✅ Pro License Activated", 
                    "You now have access to all Pro features.\n\n" +
                    "• Batch processing (unlimited files)\n" +
                    "• File size limit: 750MB\n" +
                    "• Parallel processing (up to 8 files)");
                // Refresh UI
                initialize();
            } else {
                showError("License Key Error","❌ Invalid license key. Please check and try again.");
            }
        });
    }

    private void clearSessionData() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Clear Session Data");
        alert.setHeaderText("Clear Current Session");
        alert.setContentText("This will clear the current batch queue and reset the application state. Continue?");

        
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);

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
        
                
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            if (timeEstimator != null) {
                timeEstimator.clearSavedData();
                log("🧹 Time estimation data cleared - using default estimates");
            }
        }
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

    // ========================================================================
    //  Batch Statistics
    // ========================================================================

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

    /**
     * Shows an information dialog with the given title and message.
     * 
     * @param title the dialog title
     * @param message the message to display
     */
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    /**
     * Shows an error dialog with the given title and message.
     * 
     * @param title the dialog title
     * @param message the error message to display
     */
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
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
                if (errorReporter != null && errorReporter.isEnabled()) {
                    errorReporter.reportError(e, "Apply font size to dialog failed");
                }
            }
        });
    }
}