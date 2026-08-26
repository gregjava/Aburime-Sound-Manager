/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.constants.AppConstants;
import audiomanager.constants.PreferenceKeys;
import audiomanager.core.LicenseManager;
import audiomanager.model.TranscriptionConfig;
import audiomanager.plugins.AudioSplitterTool;
import audiomanager.plugins.FileCombinerTool;
import audiomanager.util.PreferenceManager;
import audiomanager.util.TimeLeftEstimator;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.scene.input.KeyCombination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the UI for the main window.
 * Separated from MainWindow to keep UI construction separate from business logic.
 */
public class MainWindowUICreator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindowUICreator.class);

    private final Stage stage;
    private final PreferenceManager prefManager;
    private final AppState appState;
    private final Consumer<String> logger;

    // UI Components
    private FileSelectionPanel fileSelectionPanel;
    private ConfigurationPanel configurationPanel;
    private ControlPanel controlPanel;
    private TextArea logArea;
    private MenuBar menuBar;
    private CheckMenuItem darkModeMenuItem;

    // Tools
    private final AudioSplitterTool audioSplitter;
    private final FileCombinerTool fileCombiner;
    private final SoundRecorderPanel soundRecorderPanel;

    // Callbacks
    private Runnable onProcessClick;
    private Runnable onExitClick;
    private Runnable onCheckDependencies;
    private Runnable onToggleTheme;
    private Runnable onScheduleClick;
    private Runnable onPerformanceReport;
    private Runnable onWatchFolder;
    private Runnable onClearTimeData;
    private Runnable onRestApiToggle;
    private Runnable onClearSessionData;
    private MenuItem restApiMenuItem;

    // Theme state
    private boolean isDarkMode = false;

    public MainWindowUICreator(Stage stage,
                               PreferenceManager prefManager,
                               AppState appState,
                               Consumer<String> logger,
                               AudioSplitterTool audioSplitter,
                               FileCombinerTool fileCombiner,
                               SoundRecorderPanel soundRecorderPanel) {
        this.stage = stage;
        this.prefManager = prefManager;
        this.appState = appState;
        this.logger = logger;
        this.audioSplitter = audioSplitter;
        this.fileCombiner = fileCombiner;
        this.soundRecorderPanel = soundRecorderPanel;
        
        // Initialize theme state from preferences
        this.isDarkMode = "Dark".equals(prefManager.getTheme());
    }

    // ========================================================================
    //  Callback Registration
    // ========================================================================

    public void setOnProcessClick(Runnable callback) { this.onProcessClick = callback; }
    public void setOnExitClick(Runnable callback) { this.onExitClick = callback; }
    public void setOnCheckDependencies(Runnable callback) { this.onCheckDependencies = callback; }
    public void setOnToggleTheme(Runnable callback) { this.onToggleTheme = callback; }
    public void setOnScheduleClick(Runnable callback) { this.onScheduleClick = callback; }
    public void setOnPerformanceReport(Runnable callback) { this.onPerformanceReport = callback; }
    public void setOnWatchFolder(Runnable callback) { this.onWatchFolder = callback; }
    public void setOnClearTimeData(Runnable callback) { this.onClearTimeData = callback; }
    public void setOnRestApiToggle(Runnable callback) { this.onRestApiToggle = callback; }
    public void setOnClearSessionData(Runnable callback) { this.onClearSessionData = callback; }

    // ========================================================================
    //  Theme Update
    // ========================================================================

    /**
     * Updates the theme state and refreshes UI components.
     */
    public void updateTheme(boolean dark) {
        this.isDarkMode = dark;
        if (darkModeMenuItem != null) {
            darkModeMenuItem.setSelected(dark);
        }
        // Apply theme-aware styling to all panels
        applyThemeToAllPanels();
        // Update log area theme
        applyLogAreaTheme();
        // Update menu bar theme
        updateMenuBarTheme();
        // Update log section theme
        updateLogSectionTheme();
    }

    /**
     * Updates the log section theme.
     */
    private void updateLogSectionTheme() {
        // Find the log section VBox (it's the parent of the log area)
        // This is a bit of a hack, but we can style it directly
        if (logArea != null && logArea.getParent() != null) {
            Node parent = logArea.getParent();
            while (parent != null && !(parent instanceof VBox)) {
                parent = parent.getParent();
            }
            if (parent instanceof VBox logSection) {
                if (isDarkMode) {
                    logSection.setStyle("-fx-border-color: #2a3a6a; -fx-border-width: 1 0 0 0; -fx-background-color: #16213e;");
                } else {
                    logSection.setStyle("-fx-border-color: #d0d0d0; -fx-border-width: 1 0 0 0; -fx-background-color: #ffffff;");
                }
            }
        }
    }

    /**
     * Applies theme-aware styling to all panels.
     */
    private void applyThemeToAllPanels() {
        // Individual panels will handle their own styling via CSS
        // This is a placeholder for future panel-specific theme updates
    }

    /**
     * Updates the menu bar theme.
     */
    private void updateMenuBarTheme() {
        if (menuBar == null) return;
        if (isDarkMode) {
            menuBar.setStyle("-fx-padding: 0; -fx-background-color: #0f3460; -fx-border-width: 0 0 1 0; -fx-border-color: #2a3a6a;");
        } else {
            menuBar.setStyle("-fx-padding: 0; -fx-background-color: #ecf0f1; -fx-border-width: 0 0 1 0; -fx-border-color: #bdc3c7;");
        }
    }

    // ========================================================================
    //  UI Creation
    // ========================================================================

    public Scene createScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(0));
        root.getStyleClass().add("theme-fix-surface");

        menuBar = createMenuBar();

        // Create UI components
        fileSelectionPanel = new FileSelectionPanel(
                appState.getBatchFiles(),
                prefManager,
                audioSplitter,
                fileCombiner,
                logger
        );

        configurationPanel = new ConfigurationPanel(prefManager);

        controlPanel = new ControlPanel(
                appState,
                () -> { if (onProcessClick != null) onProcessClick.run(); },
                () -> { if (onExitClick != null) onExitClick.run(); },
                null
        );
        if (onScheduleClick != null) {
            controlPanel.setScheduleAction(onScheduleClick);
        }

        // Log area - initialize with proper styling
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("log-area");

        // Apply theme-aware styling based on current theme
        applyLogAreaTheme();

        // Build sections
        VBox mainContent = buildMainContent();

        ScrollPane scrollPane = new ScrollPane(mainContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("theme-fix-surface-alt");
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-width: 0;");

        root.setTop(menuBar);
        root.setCenter(scrollPane);

        Scene scene = new Scene(root, AppConstants.DEFAULT_WINDOW_WIDTH, AppConstants.DEFAULT_WINDOW_HEIGHT);
        applyCSSIfAvailable(scene);

        return scene;
    }

    // ========================================================================
    //  Log Area Theme
    // ========================================================================

    private void applyLogAreaTheme() {
        if (logArea == null) return;

        // Remove both classes first
        logArea.getStyleClass().removeAll("log-area-light", "log-area-dark");

        if (isDarkMode) {
            logArea.getStyleClass().add("log-area-dark");
        } else {
            logArea.getStyleClass().add("log-area-light");
        }
    }

    // ========================================================================
    //  UI Building
    // ========================================================================

    private VBox buildMainContent() {
        // Sound Recorder Section - Theme-aware
        TitledPane recorderPane = createThemeAwareTitledPane("🎙️ Sound Recorder", 
            soundRecorderPanel.getRecorderSection(), false);

        // File Selection Section - Theme-aware
        VBox fileSelectionContent = fileSelectionPanel.getFileSelectionControlsWithoutTitle();
        fileSelectionContent.getStyleClass().add("theme-fix-surface");
        TitledPane fileSelectionPane = createThemeAwareTitledPane("🎵 Audio File Selection", 
            fileSelectionContent, false);

        // Audio Tools Section - Theme-aware
        TitledPane toolsPane = createToolsPane();

        // Batch Queue Section - Theme-aware
        VBox batchContent = fileSelectionPanel.getBatchQueueSectionWithoutTitle();
        batchContent.getStyleClass().add("theme-fix-surface");
        TitledPane batchQueuePane = createThemeAwareTitledPane("📊 Batch Queue Status", 
            batchContent, true);

        // Control Section - Theme-aware
        VBox controlSection = controlPanel.getRoot();
        controlSection.getStyleClass().add("theme-fix-surface");
        TitledPane controlPane = createThemeAwareTitledPane("🎮 Controls", 
            controlSection, true);

        // Log section - Theme-aware
        VBox logSection = buildLogSection();

        VBox mainContent = new VBox(5);
        mainContent.getChildren().addAll(
            recorderPane,
            fileSelectionPane,
            toolsPane,
            batchQueuePane,
            controlPane,
            logSection
        );

        // Default expansion states
        recorderPane.setExpanded(false);
        fileSelectionPane.setExpanded(false);
        toolsPane.setExpanded(false);
        batchQueuePane.setExpanded(true);
        controlPane.setExpanded(true);

        return mainContent;
    }

    // ========================================================================
    //  Theme-Aware TitledPane Creation
    // ========================================================================

    /**
    * Creates a TitledPane with theme-aware styling.
    */
    private TitledPane createThemeAwareTitledPane(String title, Node content, boolean expanded) {
        TitledPane pane = new TitledPane();
        pane.setText(title);
        pane.getStyleClass().add("panel-heading-pane");
        pane.setCollapsible(true);
        pane.setExpanded(expanded);

        // Ensure content fits properly
        if (content instanceof Region) {
            ((Region) content).setMaxWidth(Double.MAX_VALUE);
        }

        pane.setContent(content);

        // Theme-aware border and background
        if (isDarkMode) {
            pane.setStyle("-fx-border-color: #2a3a6a; -fx-border-width: 1; -fx-background-color: #16213e; -fx-border-insets: 0; -fx-padding: 0;");
        } else {
            // Light mode - use light border color that matches the toolbar
            pane.setStyle("-fx-border-color: #d0d0d0; -fx-border-width: 1; -fx-background-color: #f8f9fa; -fx-border-insets: 0; -fx-padding: 0;");
        }

        return pane;
    }

    // ========================================================================
    //  Section Creators
    // ========================================================================

    private TitledPane createSoundRecorderPane() {
        Node recorderUI = soundRecorderPanel.getRecorderSection();
        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(recorderUI);
        contentContainer.setMinHeight(200);
        contentContainer.setPrefHeight(250);
        contentContainer.getStyleClass().add("theme-fix-surface");

        return createThemeAwareTitledPane("🎙️ Sound Recorder", contentContainer, false);
    }

    private TitledPane createFileSelectionPane() {
        VBox fileSelectionContent = fileSelectionPanel.getFileSelectionControlsWithoutTitle();
        fileSelectionContent.getStyleClass().add("theme-fix-surface");
        return createThemeAwareTitledPane("🎵 Audio File Selection", fileSelectionContent, false);
    }

    private TitledPane createToolsPane() {
        VBox toolsContainer = new VBox(0);
        toolsContainer.getStyleClass().add("theme-fix-surface");

        if (isDarkMode) {
            toolsContainer.setStyle("-fx-background-color: #16213e; -fx-border-color: #2a3a6a; -fx-border-width: 1 0 1 0;");
        } else {
            // Light mode - use light colors
            toolsContainer.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #d0d0d0; -fx-border-width: 1 0 1 0;");
        }

        TitledPane splitterPane = createAudioSplitterPane();
        TitledPane combinerPane = createTextFileCombinerPane();

        toolsContainer.getChildren().addAll(splitterPane, combinerPane);

        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(toolsContainer);
        contentContainer.setMinHeight(100);
        contentContainer.setPrefHeight(200);
        contentContainer.getStyleClass().add("theme-fix-surface");

        return createThemeAwareTitledPane("🛠️ Audio Tools", contentContainer, false);
    }

    private TitledPane createBatchQueuePane() {
        VBox batchContent = fileSelectionPanel.getBatchQueueSectionWithoutTitle();
        batchContent.setStyle("-fx-padding: 0;");
        batchContent.getStyleClass().add("theme-fix-surface");
        return createThemeAwareTitledPane("📊 Batch Queue Status", batchContent, true);
    }

    private TitledPane createControlPane() {
        VBox controlSection = controlPanel.getRoot();
        controlSection.setStyle("-fx-border-width: 0;");
        controlSection.getStyleClass().add("theme-fix-surface");
        return createThemeAwareTitledPane("🎮 Controls", controlSection, true);
    }

    private VBox buildLogSection() {
        VBox logSection = new VBox(0);
        logSection.getStyleClass().add("theme-fix-surface");

        if (isDarkMode) {
            logSection.setStyle("-fx-border-color: #2a3a6a; -fx-border-width: 1 0 0 0; -fx-background-color: #16213e;");
        } else {
            // Light mode - white background with light border
            logSection.setStyle("-fx-border-color: #d0d0d0; -fx-border-width: 1 0 0 0; -fx-background-color: #ffffff;");
        }

        Label logLabel = new Label("📝 Terminal");
        logLabel.getStyleClass().add("panel-heading");
        if (isDarkMode) {
            logLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 15 5 15; -fx-text-fill: #4CAF50;");
        } else {
            // Light mode - dark text
            logLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 15 5 15; -fx-text-fill: #2c3e50;");
        }

        logSection.getChildren().addAll(logLabel, logArea);
        return logSection;
    }

    // ========================================================================
    //  Helper Panes (Audio Splitter & Text Combiner)
    // ========================================================================

    private TitledPane createAudioSplitterPane() {
        Node splitterUI = audioSplitter.createUI();
        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(splitterUI);
        contentContainer.setMinHeight(320);
        contentContainer.setPrefHeight(320);
        contentContainer.getStyleClass().add("theme-fix-surface");

        return createThemeAwareTitledPane("🎵 Audio Splitter", contentContainer, false);
    }

    private TitledPane createTextFileCombinerPane() {
        Node combinerUI = fileCombiner.createUI();
        VBox contentContainer = new VBox();
        contentContainer.getChildren().add(combinerUI);
        contentContainer.setMinHeight(270);
        contentContainer.setPrefHeight(270);
        contentContainer.getStyleClass().add("theme-fix-surface");

        return createThemeAwareTitledPane("📄 Text File Combiner", contentContainer, false);
    }

    // ========================================================================
    //  Menu Bar
    // ========================================================================

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.getStyleClass().add("theme-fix-surface-alt");
        
        if (isDarkMode) {
            menuBar.setStyle("-fx-padding: 0; -fx-background-color: #0f3460; -fx-border-width: 0 0 1 0; -fx-border-color: #2a3a6a;");
        } else {
            menuBar.setStyle("-fx-padding: 0; -fx-background-color: #ecf0f1; -fx-border-width: 0 0 1 0; -fx-border-color: #bdc3c7;");
        }

        // File Menu
        Menu fileMenu = createFileMenu();

        // Edit Menu
        Menu editMenu = createEditMenu();

        // Tools Menu
        Menu toolsMenu = createToolsMenu();

        // View Menu
        Menu viewMenu = createViewMenu();

        // Help Menu
        Menu helpMenu = createHelpMenu();

        menuBar.getMenus().addAll(fileMenu, editMenu, toolsMenu, viewMenu, helpMenu);
        this.menuBar = menuBar;
        return menuBar;
    }

    private Menu createFileMenu() {
        Menu fileMenu = new Menu("File");
        fileMenu.setStyle(isDarkMode ? "-fx-text-fill: #e0e0e0;" : "-fx-text-fill: #2c3e50;");

        MenuItem preferencesItem = new MenuItem("Preferences...");
        preferencesItem.setOnAction(e -> showPreferencesDialog());
        preferencesItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+COMMA"));

        MenuItem clearSessionItem = new MenuItem("Clear Session Data");
        clearSessionItem.setOnAction(e -> {
            if (onClearSessionData != null) {
                onClearSessionData.run();
            }
        });
        clearSessionItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+C"));

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> { if (onExitClick != null) onExitClick.run(); });
        exitItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Q"));

        fileMenu.getItems().addAll(preferencesItem, clearSessionItem, new SeparatorMenuItem(), exitItem);
        return fileMenu;
    }

    private Menu createEditMenu() {
        Menu editMenu = new Menu("Edit");
        editMenu.setStyle(isDarkMode ? "-fx-text-fill: #e0e0e0;" : "-fx-text-fill: #2c3e50;");

        MenuItem undoItem = new MenuItem("Undo");
        undoItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Z"));
        undoItem.setOnAction(e -> {
            if (fileSelectionPanel != null && !fileSelectionPanel.undo()) {
                logger.accept("Nothing to undo.");
            }
        });

        MenuItem redoItem = new MenuItem("Redo");
        redoItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+Z"));
        redoItem.setOnAction(e -> {
            if (fileSelectionPanel != null && !fileSelectionPanel.redo()) {
                logger.accept("Nothing to redo.");
            }
        });

        editMenu.getItems().addAll(undoItem, redoItem);
        return editMenu;
    }

    private Menu createToolsMenu() {
        Menu toolsMenu = new Menu("Tools");
        toolsMenu.setStyle(isDarkMode ? "-fx-text-fill: #e0e0e0;" : "-fx-text-fill: #2c3e50;");

        // ===== FIX: Open the actual configuration panel sections =====
        MenuItem batchSettingsItem = new MenuItem("Batch Processing Settings...");
        batchSettingsItem.setOnAction(e -> showBatchSettingsDialog());
        batchSettingsItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+B"));

        MenuItem whisperSettingsItem = new MenuItem("Transcription Settings...");
        whisperSettingsItem.setOnAction(e -> showWhisperSettingsDialog());

        MenuItem audioSettingsItem = new MenuItem("Audio Processing Settings...");
        audioSettingsItem.setOnAction(e -> showAudioSettingsDialog());

        MenuItem clearTimeDataItem = new MenuItem("Clear Time Estimation Data");
        clearTimeDataItem.setOnAction(e -> { if (onClearTimeData != null) onClearTimeData.run(); });

        MenuItem watchFolderMenuItem = new MenuItem("📁 Watch Folder...");
        watchFolderMenuItem.setOnAction(e -> { if (onWatchFolder != null) onWatchFolder.run(); });
        watchFolderMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+W"));

        MenuItem restApiMenuItem = new MenuItem("🌐 Start REST API...");
        restApiMenuItem.setOnAction(e -> { if (onRestApiToggle != null) onRestApiToggle.run(); });
        this.restApiMenuItem = restApiMenuItem;

        MenuItem performanceReportItem = new MenuItem("📊 Performance Report...");
        performanceReportItem.setOnAction(e -> { if (onPerformanceReport != null) onPerformanceReport.run(); });
        performanceReportItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+P"));

        toolsMenu.getItems().addAll(
            batchSettingsItem, whisperSettingsItem, audioSettingsItem,
            new SeparatorMenuItem(), clearTimeDataItem,
            new SeparatorMenuItem(), watchFolderMenuItem, restApiMenuItem, performanceReportItem
        );

        return toolsMenu;
    }

    private Menu createViewMenu() {
        Menu viewMenu = new Menu("View");
        viewMenu.setStyle(isDarkMode ? "-fx-text-fill: #e0e0e0;" : "-fx-text-fill: #2c3e50;");

        darkModeMenuItem = new CheckMenuItem("🌙 Dark Mode");
        darkModeMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+Shift+D"));
        darkModeMenuItem.setSelected(isDarkMode);
        darkModeMenuItem.setOnAction(e -> { if (onToggleTheme != null) onToggleTheme.run(); });

        viewMenu.getItems().add(darkModeMenuItem);
        return viewMenu;
    }

    private Menu createHelpMenu() {
        Menu helpMenu = new Menu("Help");
        helpMenu.setStyle(isDarkMode ? "-fx-text-fill: #e0e0e0;" : "-fx-text-fill: #2c3e50;");

        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAboutDialog());

        MenuItem dependenciesItem = new MenuItem("Check Dependencies");
        dependenciesItem.setOnAction(e -> { if (onCheckDependencies != null) onCheckDependencies.run(); });
        dependenciesItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("F5"));

        MenuItem setupAssistantItem = new MenuItem("Setup Assistant...");
        setupAssistantItem.setOnAction(e -> {});

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

        return helpMenu;
    }

    // ========================================================================
    //  Dialog Methods - FIXED to open the actual Configuration Panel
    // ========================================================================

    private void showPreferencesDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Preferences");
        dialog.setHeaderText("User Interface Settings");
        
        // Create a simple preferences panel
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        
        // Font size
        Label fontLabel = new Label("Font Size:");
        Slider fontSlider = new Slider(8, 20, prefManager.getFontSize());
        fontSlider.setShowTickMarks(true);
        fontSlider.setShowTickLabels(true);
        fontSlider.setMajorTickUnit(2);
        fontSlider.setPrefWidth(200);
        
        Label fontValueLabel = new Label(String.format("%.0fpx", fontSlider.getValue()));
        fontSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            fontValueLabel.setText(String.format("%.0fpx", newVal.doubleValue()));
        });
        
        HBox fontBox = new HBox(10, fontLabel, fontSlider, fontValueLabel);
        fontBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        // Theme toggle
        CheckBox darkModeCheck = new CheckBox("Dark Mode");
        darkModeCheck.setSelected("Dark".equals(prefManager.getTheme()));
        darkModeCheck.setOnAction(e -> {
            if (onToggleTheme != null) onToggleTheme.run();
        });
        
        // Auto-update
        CheckBox autoUpdateCheck = new CheckBox("Check for updates automatically");
        autoUpdateCheck.setSelected(prefManager.getBoolean("auto.update.enabled", true));
        autoUpdateCheck.setOnAction(e -> {
            prefManager.putBoolean("auto.update.enabled", autoUpdateCheck.isSelected());
            prefManager.flush();
        });
        
        // Error reporting
        CheckBox errorReportingCheck = new CheckBox("Send anonymous error reports");
        errorReportingCheck.setSelected(prefManager.getBoolean("error.reporting.enabled", false));
        errorReportingCheck.setOnAction(e -> {
            prefManager.putBoolean("error.reporting.enabled", errorReportingCheck.isSelected());
            prefManager.flush();
            audiomanager.Studio studio = audiomanager.Studio.getInstance();
            if (studio != null && studio.getErrorReporter() != null) {
                studio.getErrorReporter().setEnabled(errorReportingCheck.isSelected());
            }
        });
        
        content.getChildren().addAll(fontBox, darkModeCheck, autoUpdateCheck, errorReportingCheck);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);
        dialog.showAndWait();
    }

    /**
     * Shows the Batch Processing Settings dialog.
     * 
     * ===== FIX: Now opens a dialog with the batch settings from ConfigurationPanel =====
     */
    private void showBatchSettingsDialog() {
        if (configurationPanel == null) {
            logger.accept("❌ Configuration panel not available");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Batch Processing Settings");
        dialog.setHeaderText("Configure batch processing behavior");
        dialog.setResizable(true);

        // Create a copy of the batch section from configuration panel
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.setMinWidth(400);

        // Max Parallel Files
        Label parallelLabel = new Label("Max Parallel Files:");
        Spinner<Integer> parallelSpinner = new Spinner<>(1, 16, configurationPanel.getMaxParallelFiles());
        parallelSpinner.setEditable(true);
        parallelSpinner.setPrefWidth(80);

        // Checkboxes
        CheckBox autoRemoveCheck = new CheckBox("Auto-Remove Completed Files");
        autoRemoveCheck.setSelected(configurationPanel.isAutoRemoveCompleted());

        CheckBox adaptiveScalingCheck = new CheckBox("Adaptive Concurrency Scaling");
        adaptiveScalingCheck.setSelected(configurationPanel.isAdaptiveScalingEnabled());

        CheckBox skipSegmentationCheck = new CheckBox("Baseline Mode (Skip Segmentation)");
        skipSegmentationCheck.setSelected(configurationPanel.isSkipSegmentationEnabled());

        // Note: Adaptive Scaling and Baseline Mode are mutually exclusive
        adaptiveScalingCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) skipSegmentationCheck.setSelected(false);
        });
        skipSegmentationCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) adaptiveScalingCheck.setSelected(false);
        });

        content.getChildren().addAll(
            new Label("Batch Processing Settings:"),
            parallelLabel, parallelSpinner,
            autoRemoveCheck,
            adaptiveScalingCheck,
            skipSegmentationCheck
        );

        dialog.getDialogPane().setContent(content);

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, cancelBtn);

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(response -> {
            if (response == saveBtn) {
                // Apply settings
                try {
                    prefManager.setMaxParallelFiles(parallelSpinner.getValue());
                    prefManager.setAutoRemoveCompleted(autoRemoveCheck.isSelected());
                    prefManager.putBoolean("adaptive_scaling_enabled", adaptiveScalingCheck.isSelected());
                    prefManager.putBoolean("skip_segmentation_baseline_mode", skipSegmentationCheck.isSelected());
                    prefManager.flush();
                    
                    // Update configuration panel
                    configurationPanel.loadPreferences();
                    
                    logger.accept("✅ Batch processing settings saved");
                } catch (Exception e) {
                    LOGGER.error("Failed to save batch settings", e);
                    logger.accept("❌ Failed to save batch settings: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Shows the Transcription Settings dialog.
     * 
     * ===== FIX: Now opens a dialog with the transcription settings from ConfigurationPanel =====
     */
    private void showWhisperSettingsDialog() {
        if (configurationPanel == null) {
            logger.accept("❌ Configuration panel not available");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Transcription Settings");
        dialog.setHeaderText("Configure Whisper transcription parameters");
        dialog.setResizable(true);

        // Create a copy of the transcription section from configuration panel
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.setMinWidth(450);

        // Get current values
        TranscriptionConfig currentConfig = configurationPanel.getTranscriptionConfig();

        // Model
        Label modelLabel = new Label("Model:");
        ComboBox<String> modelCombo = new ComboBox<>(FXCollections.observableArrayList(
            "tiny", "base", "small", "medium", "large"
        ));
        modelCombo.setValue(currentConfig.getModel());
        modelCombo.setPrefWidth(120);

        // Language
        Label langLabel = new Label("Language:");
        ComboBox<String> langCombo = new ComboBox<>(FXCollections.observableArrayList(
            "auto", "en", "es", "de", "fr", "it", "zh", "ja", "ru", "ko",
            "pt", "tr", "pl", "ca", "nl", "ar", "sv", "id", "hi", "fi"
        ));
        langCombo.setValue(currentConfig.getLanguage());
        langCombo.setPrefWidth(120);

        // Checkboxes
        CheckBox timestampsCheck = new CheckBox("Enable Timestamps");
        timestampsCheck.setSelected(currentConfig.isTimestampsEnabled());

        CheckBox confidenceCheck = new CheckBox("Include Confidence Scores");
        confidenceCheck.setSelected(currentConfig.isConfidenceEnabled());

        CheckBox diarizeCheck = new CheckBox("Enable Speaker Diarization");
        diarizeCheck.setSelected(currentConfig.isDiarizeEnabled());

        // Translation section
        CheckBox translationCheck = new CheckBox("Enable Translation");
        translationCheck.setSelected(currentConfig.isTranslationEnabled());

        Label targetLangLabel = new Label("Target Language:");
        ComboBox<String> targetLangCombo = new ComboBox<>(FXCollections.observableArrayList(
            "es", "fr", "de", "it", "pt", "ru", "zh", "ja", "ar", "hi",
            "nl", "pl", "tr", "vi", "th", "ko", "sv", "no", "da", "fi"
        ));
        targetLangCombo.setValue(currentConfig.getTranslationTargetLanguage());
        targetLangCombo.setPrefWidth(80);
        targetLangCombo.setDisable(!translationCheck.isSelected());

        translationCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            targetLangCombo.setDisable(!newVal);
        });

        // Grid layout
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        
        grid.add(modelLabel, 0, 0);
        grid.add(modelCombo, 1, 0);
        grid.add(langLabel, 2, 0);
        grid.add(langCombo, 3, 0);
        
        grid.add(timestampsCheck, 0, 1, 2, 1);
        grid.add(confidenceCheck, 2, 1, 2, 1);
        grid.add(diarizeCheck, 0, 2, 2, 1);
        
        grid.add(new Separator(), 0, 3, 4, 1);
        
        grid.add(translationCheck, 0, 4, 4, 1);
        grid.add(targetLangLabel, 0, 5);
        grid.add(targetLangCombo, 1, 5);

        content.getChildren().add(grid);

        dialog.getDialogPane().setContent(content);

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, cancelBtn);

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(response -> {
            if (response == saveBtn) {
                try {
                    // Save preferences
                    prefManager.setWhisperModel(modelCombo.getValue());
                    prefManager.setLanguage(langCombo.getValue());
                    prefManager.setTimestampsEnabled(timestampsCheck.isSelected());
                    prefManager.setConfidenceEnabled(confidenceCheck.isSelected());
                    prefManager.putBoolean("diarize.enabled", diarizeCheck.isSelected());
                    
                    // Translation preferences
                    prefManager.setTranslationEnabled(translationCheck.isSelected());
                    prefManager.setTranslationTargetLanguage(targetLangCombo.getValue());
                    
                    prefManager.flush();
                    
                    // Refresh configuration panel
                    configurationPanel.loadPreferences();
                    
                    logger.accept("✅ Transcription settings saved");
                } catch (Exception e) {
                    LOGGER.error("Failed to save transcription settings", e);
                    logger.accept("❌ Failed to save transcription settings: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Shows the Audio Processing Settings dialog.
     * 
     * ===== FIX: Now opens a dialog with the audio settings from ConfigurationPanel =====
     */
    private void showAudioSettingsDialog() {
        if (configurationPanel == null) {
            logger.accept("❌ Configuration panel not available");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Audio Processing Settings");
        dialog.setHeaderText("Configure FFmpeg audio processing parameters");
        dialog.setResizable(true);

        // Create a copy of the audio section from configuration panel
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.setMinWidth(400);

        // Output format
        Label formatLabel = new Label("Output Format:");
        ComboBox<String> formatCombo = new ComboBox<>(FXCollections.observableArrayList(
            "mp3", "wav", "ogg", "flac", "m4a"
        ));
        formatCombo.setValue(prefManager.getString(PreferenceKeys.OUTPUT_FORMAT, "mp3"));
        formatCombo.setPrefWidth(100);

        // Bitrate
        Label bitrateLabel = new Label("Bitrate:");
        ComboBox<String> bitrateCombo = new ComboBox<>(FXCollections.observableArrayList(
            "64k", "96k", "128k", "192k", "256k", "320k"
        ));
        bitrateCombo.setValue(prefManager.getString(PreferenceKeys.BITRATE, "128k"));
        bitrateCombo.setPrefWidth(100);

        // Volume Boost
        Label volumeLabel = new Label("Volume Boost:");
        Slider volumeSlider = new Slider(0, 10, prefManager.getVolumeBoost());
        volumeSlider.setShowTickMarks(true);
        volumeSlider.setShowTickLabels(true);
        volumeSlider.setMajorTickUnit(2);
        volumeSlider.setPrefWidth(150);
        Label volumeValueLabel = new Label(String.format("%.1f dB", volumeSlider.getValue()));
        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            volumeValueLabel.setText(String.format("%.1f dB", newVal.doubleValue()));
        });
        HBox volumeBox = new HBox(10, volumeSlider, volumeValueLabel);
        volumeBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Checkboxes
        CheckBox noiseReductionCheck = new CheckBox("Enable Noise Reduction");
        noiseReductionCheck.setSelected(prefManager.isNoiseReductionEnabled());

        CheckBox normalizeCheck = new CheckBox("Normalize Audio");
        normalizeCheck.setSelected(prefManager.isNormalizeAudioEnabled());

        CheckBox removeSilenceCheck = new CheckBox("Remove Silence");
        removeSilenceCheck.setSelected(prefManager.getBoolean("remove_silence", false));

        CheckBox id3TaggingCheck = new CheckBox("Generate ID3 Tags (sidecar .meta file)");
        id3TaggingCheck.setSelected(prefManager.isID3TaggingEnabled());

        CheckBox autoVolumeCheck = new CheckBox("Auto Volume Optimization");
        autoVolumeCheck.setSelected(prefManager.isAutoVolumeOptimizationEnabled());

        // Grid layout
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        
        grid.add(formatLabel, 0, 0);
        grid.add(formatCombo, 1, 0);
        grid.add(bitrateLabel, 2, 0);
        grid.add(bitrateCombo, 3, 0);
        
        grid.add(volumeLabel, 0, 1);
        grid.add(volumeBox, 1, 1, 3, 1);
        
        grid.add(noiseReductionCheck, 0, 2, 2, 1);
        grid.add(normalizeCheck, 2, 2, 2, 1);
        grid.add(removeSilenceCheck, 0, 3, 2, 1);
        grid.add(id3TaggingCheck, 2, 3, 2, 1);
        grid.add(autoVolumeCheck, 0, 4, 4, 1);

        content.getChildren().add(grid);

        dialog.getDialogPane().setContent(content);

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, cancelBtn);

        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(response -> {
            if (response == saveBtn) {
                try {
                    // Save preferences
                    prefManager.putString(PreferenceKeys.OUTPUT_FORMAT, formatCombo.getValue());
                    prefManager.putString(PreferenceKeys.BITRATE, bitrateCombo.getValue());
                    prefManager.setVolumeBoost(volumeSlider.getValue());
                    prefManager.setNoiseReductionEnabled(noiseReductionCheck.isSelected());
                    prefManager.setNormalizeAudioEnabled(normalizeCheck.isSelected());
                    prefManager.putBoolean("remove_silence", removeSilenceCheck.isSelected());
                    prefManager.setID3TaggingEnabled(id3TaggingCheck.isSelected());
                    prefManager.setAutoVolumeOptimizationEnabled(autoVolumeCheck.isSelected());
                    prefManager.flush();
                    
                    // Refresh configuration panel
                    configurationPanel.loadPreferences();
                    
                    logger.accept("✅ Audio processing settings saved");
                } catch (Exception e) {
                    LOGGER.error("Failed to save audio settings", e);
                    logger.accept("❌ Failed to save audio settings: " + e.getMessage());
                }
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
            "You have an active Pro license with full access to all features." :
            "You are using the Free version. Upgrade to Pro for unlimited features."
        );
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    private void showActivationDialog() {
        // Will be handled by the controller
        logger.accept("💎 License activation - coming soon");
    }

    // ========================================================================
    //  Getters
    // ========================================================================

    public FileSelectionPanel getFileSelectionPanel() { return fileSelectionPanel; }
    public ConfigurationPanel getConfigurationPanel() { return configurationPanel; }
    public ControlPanel getControlPanel() { return controlPanel; }
    public TextArea getLogArea() { return logArea; }
    public CheckMenuItem getDarkModeMenuItem() { return darkModeMenuItem; }

    // ========================================================================
    //  Styling Helpers
    // ========================================================================

    private void setStyled(Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    private void applyCSSIfAvailable(Scene scene) {
        try {
            String cssPath = getClass().getResource(AppConstants.CSS_PATH).toExternalForm();
            scene.getStylesheets().add(cssPath);
        } catch (Exception e) {
            // CSS not found, use default styling
        }
    }

    // ========================================================================
    //  Methods that need external wiring
    // ========================================================================

    public void setTimeEstimator(TimeLeftEstimator timeEstimator) {
        // Update control panel with time estimator
        // This is a workaround since ControlPanel needs it
    }
    
    /**
    * Updates the REST API menu item text based on running state.
    */
    public void updateRestApiMenuItem(boolean running) {
        // This will be called from MainWindow when API state changes
        // The menu item is created in createToolsMenu()
        // We'll need to store a reference to the menu item
    }
    
    /**
    * Updates the REST API menu item.
    */
    public void setRestApiMenuItem(boolean running) {
        if (restApiMenuItem != null) {
            if (running) {
                restApiMenuItem.setText("🌐 Stop REST API");
                restApiMenuItem.setStyle("-fx-text-fill: #2e7d32;");
            } else {
                restApiMenuItem.setText("🌐 Start REST API...");
                restApiMenuItem.setStyle("");
            }
        }
    }
}