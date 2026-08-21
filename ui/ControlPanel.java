/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.constants.AppConstants;
import audiomanager.core.DependencyManager;
import audiomanager.core.LicenseManager;
import audiomanager.model.BatchFileItem;
import audiomanager.util.TimeLeftEstimator;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Control panel with AppState binding.
 * 
 * <p>All button disable states are controlled via AppState bindings
 * to avoid the "A bound value cannot be set" error.</p>
 */
public class ControlPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlPanel.class);

    // ===== UI Components =====
    private final VBox root;
    private final AppState appState;
    private final TimeLeftEstimator timeEstimator;

    private final Label versionLabel;
    private final Label statusLabel;
    private final Label detailedStatusLabel;
    private final Label resourceStatusLabel;
    private final ProgressBar overallProgressBar;
    private final Label overallPercentageLabel;
    private final Button processButton;
    private final Button clearLogButton;
    private final Button exitButton;
    private final Button scheduleButton;
    private final HBox buttonBox;

    private final Label fileTimeSpentLabel;
    private final Label fileTimeLeftLabel;
    private final Label totalTimeSpentLabel;
    private final Label totalTimeLeftLabel;
    private final Label dataStatusLabel;

    private final VBox individualProgressSection;
    private final VBox individualProgressRows;

    private final Label licenseStatusLabel;

    // ===== State =====
    private long batchStartTimeMs = 0L;

    // ========================================================================
    //  Construction
    // ========================================================================

    public ControlPanel(AppState appState, Runnable onProcessClick, Runnable onExitClick,
                        TimeLeftEstimator timeEstimator) {
        this.appState = appState;
        this.timeEstimator = timeEstimator;

        this.versionLabel = new Label("v" + AppConstants.APP_VERSION);
        setStyled(this.versionLabel, "-fx-font-size: 10px; -fx-text-fill: #95a5a6;");

        LicenseManager license = LicenseManager.getInstance();
        this.licenseStatusLabel = new Label(license.getLicenseStatusText());
        setStyled(this.licenseStatusLabel,
            license.isPro()
                ? "-fx-font-size: 10px; -fx-text-fill: #2e7d32; -fx-font-weight: bold;"
                : "-fx-font-size: 10px; -fx-text-fill: #ed6c02;");

        this.statusLabel = new Label("Ready");
        this.detailedStatusLabel = new Label("Run dependency check to begin");
        this.resourceStatusLabel = new Label("");
        this.overallProgressBar = new ProgressBar(0);
        this.overallPercentageLabel = new Label("0%");
        this.processButton = new Button("🚀 Start Processing");
        this.clearLogButton = new Button("🧹 Clear Log");
        this.exitButton = new Button("🚪 Exit");
        this.scheduleButton = new Button("📅 Schedule");
        this.fileTimeSpentLabel = new Label("0s");
        this.fileTimeLeftLabel = new Label("N/A");
        this.totalTimeSpentLabel = new Label("0s");
        this.totalTimeLeftLabel = new Label("N/A");
        this.dataStatusLabel = new Label("Using default estimates");
        this.individualProgressRows = new VBox(4);
        this.individualProgressSection = buildIndividualProgressSection();
        this.buttonBox = buildButtonBox(onProcessClick, onExitClick);

        this.root = buildUI(onProcessClick, onExitClick);
        bindToState();

        LOGGER.debug("ControlPanel initialized with AppState binding");
    }

    // ========================================================================
    //  UI Construction
    // ========================================================================

    private VBox buildUI(Runnable onProcessClick, Runnable onExitClick) {
        VBox container = new VBox(10);
        setStyled(container, "-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; "
                + "-fx-padding: 15; -fx-background-color: white;");

        VBox statusBox = new VBox(8);
        setStyled(statusBox, "-fx-padding: 10; -fx-background-color: #f8f9fa; -fx-border-radius: 5;");
        statusBox.getStyleClass().add("theme-fix-surface-alt");

        setStyled(statusLabel, "-fx-font-weight: bold; -fx-font-size: 16px;");
        statusLabel.getStyleClass().setAll("panel-heading");
        detailedStatusLabel.setWrapText(true);
        setStyled(detailedStatusLabel, "-fx-font-size: 12px; -fx-text-fill: #7f8c8d;");
        resourceStatusLabel.setWrapText(true);
        setStyled(resourceStatusLabel, "-fx-font-size: 11px; -fx-text-fill: #95a5a6; -fx-font-style: italic;");
        resourceStatusLabel.setManaged(false);
        resourceStatusLabel.setVisible(false);

        HBox licenseBox = new HBox(5);
        licenseBox.setAlignment(Pos.CENTER_LEFT);
        licenseBox.getChildren().add(licenseStatusLabel);

        if (!LicenseManager.getInstance().isPro()) {
            Button upgradeButton = new Button("💎 Upgrade");
            upgradeButton.setStyle("-fx-background-color: #f9a825; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 10px; -fx-padding: 2 10;");
            upgradeButton.setOnAction(e -> showUpgradeDialog());
            licenseBox.getChildren().add(upgradeButton);
        }

        overallProgressBar.setMaxWidth(Double.MAX_VALUE);
        setStyled(overallProgressBar, "-fx-accent: #27ae60; -fx-background-color: #ecf0f1;");
        HBox.setHgrow(overallProgressBar, Priority.ALWAYS);

        setStyled(overallPercentageLabel, "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #27ae60;");

        HBox progressBox = new HBox(10, overallProgressBar, overallPercentageLabel);
        progressBox.setAlignment(Pos.CENTER_LEFT);

        VBox timeSection = buildTimeReportSection();

        statusBox.getChildren().addAll(
            statusLabel, detailedStatusLabel, resourceStatusLabel,
            licenseBox, progressBox, timeSection, individualProgressSection);

        HBox statusFooter = new HBox();
        statusFooter.setAlignment(Pos.CENTER_RIGHT);
        statusFooter.getChildren().add(versionLabel);
        statusBox.getChildren().add(statusFooter);

        HBox buttonBox = buildButtonBox(onProcessClick, onExitClick);
        container.getChildren().addAll(statusBox, buttonBox);
        return container;
    }

    private VBox buildIndividualProgressSection() {
        VBox section = new VBox(6);
        setStyled(section, "-fx-padding: 8 0 0 0;");

        Label header = new Label("🔄 Currently Processing");
        setStyled(header, "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 13px;");

        setStyled(individualProgressRows, "-fx-padding: 4 0 0 8;");

        section.getChildren().addAll(header, individualProgressRows);
        section.setVisible(false);
        section.setManaged(false);
        return section;
    }

    private VBox buildTimeReportSection() {
        VBox section = new VBox(5);

        Label header = new Label("⏱️ Time Report");
        setStyled(header, "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 13px;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(8);
        grid.setPadding(new Insets(5, 0, 0, 0));

        grid.add(titleLabel("File Time Spent:"), 0, 0);
        grid.add(fileTimeSpentLabel, 1, 0);
        grid.add(titleLabel("File Time Left:"), 2, 0);
        grid.add(fileTimeLeftLabel, 3, 0);
        grid.add(titleLabel("Total Time Spent:"), 4, 0);
        grid.add(totalTimeSpentLabel, 5, 0);
        grid.add(titleLabel("Total Time Left:"), 6, 0);
        grid.add(totalTimeLeftLabel, 7, 0);

        setStyled(fileTimeSpentLabel, "-fx-font-weight: bold; -fx-font-size: 11px;");
        setStyled(fileTimeLeftLabel, "-fx-font-weight: bold; -fx-font-size: 11px;");
        setStyled(totalTimeSpentLabel, "-fx-font-weight: bold; -fx-font-size: 11px;");
        setStyled(totalTimeLeftLabel, "-fx-font-weight: bold; -fx-font-size: 11px;");
        setStyled(dataStatusLabel, "-fx-font-size: 10px; -fx-text-fill: #666; -fx-font-style: italic;");

        section.getChildren().addAll(header, grid, dataStatusLabel);
        return section;
    }

    private HBox buildButtonBox(Runnable onProcessClick, Runnable onExitClick) {
        HBox box = new HBox(15);
        box.setAlignment(Pos.CENTER);
        setStyled(box, "-fx-padding: 0 0 10 0;");

        setStyled(processButton, "");
        processButton.getStyleClass().add("action-btn-start");
        processButton.setOnAction(e -> onProcessClick.run());
        processButton.setMinWidth(180);

        setStyled(clearLogButton, "");
        clearLogButton.getStyleClass().add("action-btn-clear-log");

        setStyled(scheduleButton, "");
        scheduleButton.getStyleClass().add("action-btn-schedule");
        scheduleButton.setMinWidth(120);
        scheduleButton.setTooltip(new Tooltip("Schedule batch to run later"));

        setStyled(exitButton, "");
        exitButton.getStyleClass().add("action-btn-exit");
        exitButton.setOnAction(e -> onExitClick.run());

        box.getChildren().addAll(processButton, clearLogButton, scheduleButton, exitButton);
        return box;
    }

    private Label titleLabel(String text) {
        Label l = new Label(text);
        setStyled(l, "-fx-font-weight: bold; -fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
        return l;
    }

    private void setStyled(Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    // ========================================================================
    //  AppState Binding
    // ========================================================================

    private void bindToState() {
        statusLabel.textProperty().bind(appState.processingStatusProperty());
        detailedStatusLabel.textProperty().bind(appState.detailedStatusProperty());
        resourceStatusLabel.textProperty().bind(appState.resourceStatusProperty());
        resourceStatusLabel.visibleProperty().bind(appState.resourceStatusProperty().isNotEmpty());
        resourceStatusLabel.managedProperty().bind(appState.resourceStatusProperty().isNotEmpty());

        overallProgressBar.progressProperty().bind(appState.overallProgressProperty());
        overallPercentageLabel.textProperty().bind(
            appState.overallProgressProperty().multiply(100).asString("%.0f%%")
        );

        // FIX: Combine isCancelling and dependencyCheckInProgress for the disable binding
        processButton.disableProperty().bind(
            appState.isCancellingProperty().or(appState.dependencyCheckInProgressProperty())
        );

        processButton.textProperty().bind(
            appState.isProcessingProperty().asString().map(p ->
                Boolean.parseBoolean(p) ? "⏹️ Cancel Processing" : "🚀 Start Processing"
            )
        );

        individualProgressSection.visibleProperty().bind(appState.isProcessingProperty());
        individualProgressSection.managedProperty().bind(appState.isProcessingProperty());
    }

    // ========================================================================
    //  Public Methods
    // ========================================================================

    public void setScheduleAction(Runnable action) {
        scheduleButton.setOnAction(e -> action.run());
    }

    public void updateStatus(String main, String detail) {
        appState.setStatus(main, detail);
    }

    public void updateResourceStatus(String message) {
        appState.setResourceStatus(message);
    }

    public void setOverallProgress(double progress) {
        appState.setOverallProgress(progress);
    }

    public void setProcessingState(boolean processing) {
        appState.setProcessing(processing);
        if (!processing) {
            individualProgressRows.getChildren().clear();
        }
    }

    public void setProcessingEnabled(boolean enabled) {
        // Use AppState instead of direct button manipulation
        appState.setCancelling(!enabled);
    }

    /**
     * Sets the dependency check in progress state.
     * Uses AppState to avoid the "A bound value cannot be set" error.
     */
    public void setDependencyCheckInProgress(boolean inProgress) {
        appState.setDependencyCheckInProgress(inProgress);
    }

    public void startBatchProcessing() {
        this.batchStartTimeMs = System.currentTimeMillis();
    }

    public void updateProgress(ObservableList<BatchFileItem> items, int completed, int failed, int total) {
        int pending = Math.max(0, total - completed - failed);
        appState.updateBatchStats(total, completed, failed, pending);

        if (total > 0) {
            double progress = (double) (completed + failed) / total;
            appState.setOverallProgress(Math.min(1.0, progress));
        }
        updateIndividualProgressRows(items);
    }

    public void updateProgress(int completed, int failed, int total) {
        int pending = Math.max(0, total - completed - failed);
        appState.updateBatchStats(total, completed, failed, pending);

        if (total > 0) {
            double progress = (double) (completed + failed) / total;
            appState.setOverallProgress(Math.min(1.0, progress));
        }
    }

    public void updateTimeEstimates(ObservableList<BatchFileItem> items, long currentTime) {
        if (timeEstimator == null) return;

        appState.updateTimeEstimates(
            timeEstimator.getCurrentFileTimeSpent(),
            timeEstimator.getLiveCurrentFileTimeLeftMs(),
            timeEstimator.getTotalTimeSpent(),
            timeEstimator.getLiveTotalTimeLeftMs()
        );

        int learnedProcesses = timeEstimator.getLearnedPatternCount();
        if (learnedProcesses > 0) {
            dataStatusLabel.setText("Using learned estimates (" + learnedProcesses
                + " process type" + (learnedProcesses == 1 ? "" : "s") + " learned)");
            setStyled(dataStatusLabel, "-fx-font-size: 10px; -fx-text-fill: #2e7d32; -fx-font-style: italic;");
        } else {
            dataStatusLabel.setText("Using default estimates");
            setStyled(dataStatusLabel, "-fx-font-size: 10px; -fx-text-fill: #666; -fx-font-style: italic;");
        }
    }

    public void updateDependencyStatus(DependencyManager.DependencyStatus ffmpegStatus,
                                       DependencyManager.DependencyStatus whisperStatus) {
        if (ffmpegStatus == null || whisperStatus == null) {
            appState.setStatus("Dependency Check Failed", "Could not verify system dependencies.");
            return;
        }

        if (ffmpegStatus.isAvailable()) {
            appState.setStatus("Ready to Process", ffmpegStatus.getMessage());
        } else {
            appState.setStatus("FFmpeg Missing", ffmpegStatus.getMessage());
        }

        if (!whisperStatus.isAvailable()) {
            String currentDetail = appState.detailedStatusProperty().get();
            appState.detailedStatusProperty().set(currentDetail + " | " + whisperStatus.getMessage());
        }
    }

    // ========================================================================
    //  Individual Progress Rows
    // ========================================================================

    private void updateIndividualProgressRows(ObservableList<BatchFileItem> items) {
        Platform.runLater(() -> {
            List<BatchFileItem> processingItems = new ArrayList<>();
            for (BatchFileItem item : items) {
                if ("PROCESSING".equals(item.getStatus())) {
                    processingItems.add(item);
                }
            }

            if (processingItems.isEmpty()) {
                individualProgressRows.getChildren().clear();
                return;
            }

            individualProgressRows.getChildren().setAll(
                processingItems.stream().map(this::buildIndividualProgressRow).toList()
            );
        });
    }

    private HBox buildIndividualProgressRow(BatchFileItem item) {
        Label nameLabel = new Label(item.getDisplayName());
        setStyled(nameLabel, "-fx-font-size: 11px; -fx-text-fill: #34495e;");
        nameLabel.setMaxWidth(220);
        nameLabel.setMinWidth(220);

        ProgressBar bar = new ProgressBar(item.getProgress());
        bar.setPrefWidth(140);
        setStyled(bar, "-fx-accent: #2196F3;");
        HBox.setHgrow(bar, Priority.ALWAYS);

        Label pct = new Label(String.format("%.0f%%", item.getProgress() * 100));
        setStyled(pct, "-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");
        pct.setMinWidth(36);

        HBox row = new HBox(8, nameLabel, bar, pct);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ========================================================================
    //  Getters
    // ========================================================================

    public Button getClearLogButton() { return clearLogButton; }
    public Button getExitButton() { return exitButton; }
    public Button getScheduleButton() { return scheduleButton; }
    public VBox getRoot() { return root; }
    public HBox getButtonBox() { return buttonBox; }

    // ========================================================================
    //  Upgrade Dialog
    // ========================================================================

    private void showUpgradeDialog() {
        LicenseManager license = LicenseManager.getInstance();

        if (license.isPro()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Pro License");
            alert.setHeaderText("💎 Pro License - Active");
            alert.setContentText(license.getFeatureSummary());
            ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
            alert.showAndWait();
            return;
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Upgrade to Pro");
        alert.setHeaderText("💎 Unlock Pro Features");
        alert.setContentText(license.getFeatureSummary() + "\n\nEnter your license key:");
        alert.getButtonTypes().add(new ButtonType("Enter Key", ButtonBar.ButtonData.OK_DONE));
        alert.getButtonTypes().add(ButtonType.CANCEL);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);

        alert.showAndWait().ifPresent(response -> {
            if (response.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                TextInputDialog keyDialog = new TextInputDialog();
                keyDialog.setTitle("Enter License Key");
                keyDialog.setHeaderText("Paste your Pro license key");
                keyDialog.setContentText("License Key:");
                ThemeManager.applyCurrentThemeToDialog(keyDialog.getDialogPane(), null);

                Optional<String> result = keyDialog.showAndWait();
                result.ifPresent(key -> {
                    if (license.activateLicense(key)) {
                        refreshLicenseUI();
                        showInfoAlert("✅ Pro License Activated", "You now have access to all Pro features!");
                    } else {
                        showErrorAlert("❌ Invalid License", "The license key you entered is invalid.");
                    }
                });
            }
        });
    }

    private void refreshLicenseUI() {
        LicenseManager license = LicenseManager.getInstance();
        licenseStatusLabel.setText(license.getLicenseStatusText());
        setStyled(licenseStatusLabel,
            license.isPro()
                ? "-fx-font-size: 10px; -fx-text-fill: #2e7d32; -fx-font-weight: bold;"
                : "-fx-font-size: 10px; -fx-text-fill: #ed6c02;");
    }

    private void showInfoAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }
}
