/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.core.DependencyManager;
import audiomanager.ui.ThemeManager;
import audiomanager.model.BatchFileItem;
import audiomanager.util.TimeLeftEstimator;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Control panel — status label, overall progress bar, time report grid,
 * and action buttons.
 */
public class ControlPanel {

    /** See FileSelectionPanel.setStyled() for why every setStyle() call in this class routes through here. */
    private static void setStyled(javafx.scene.Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlPanel.class);

    private final VBox root;
    private final TimeLeftEstimator timeEstimator;

    private final Label statusLabel;
    private final Label detailedStatusLabel;
    private final Label resourceStatusLabel;
    private final ProgressBar overallProgressBar;
    private final Label overallPercentageLabel;
    private final Button processButton;
    private final Button clearLogButton;
    private final Button exitButton;

    private final Label fileTimeSpentLabel;
    private final Label fileTimeLeftLabel;
    private final Label totalTimeSpentLabel;
    private final Label totalTimeLeftLabel;
    private final Label dataStatusLabel;

    private final VBox individualProgressSection;
    private final VBox individualProgressRows;

    private long batchStartTimeMs = 0L;

    public ControlPanel(Runnable onProcessClick, Runnable onExitClick,
                        TimeLeftEstimator timeEstimator) {
        this.timeEstimator = timeEstimator;

        statusLabel           = new Label("Ready");
        detailedStatusLabel   = new Label("Run dependency check to begin");
        resourceStatusLabel   = new Label("");
        overallProgressBar    = new ProgressBar(0);
        overallPercentageLabel = new Label("0%");
        processButton         = new Button("🚀 Start Processing");
        clearLogButton        = new Button("🧹 Clear Log");
        exitButton            = new Button("🚪 Exit");
        fileTimeSpentLabel    = new Label("0s");
        fileTimeLeftLabel     = new Label("N/A");
        totalTimeSpentLabel   = new Label("0s");
        totalTimeLeftLabel    = new Label("N/A");
        dataStatusLabel       = new Label("Using default estimates");
        individualProgressRows   = new VBox(4);
        individualProgressSection = buildIndividualProgressSection();

        this.root = buildUI(onProcessClick, onExitClick);
    }

    private VBox buildUI(Runnable onProcessClick, Runnable onExitClick) {
        VBox container = new VBox(10);
        setStyled(container, "-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; "
                + "-fx-padding: 15; -fx-background-color: white;");

        VBox statusBox = new VBox(8);
        setStyled(statusBox, "-fx-padding: 10; -fx-background-color: #f8f9fa; -fx-border-radius: 5;");
        statusBox.getStyleClass().add("theme-fix-surface-alt");  // ← add

        setStyled(statusLabel, "-fx-font-weight: bold; -fx-font-size: 16px;");
        statusLabel.getStyleClass().setAll("panel-heading");
        detailedStatusLabel.setWrapText(true);
        setStyled(detailedStatusLabel, "-fx-font-size: 12px; -fx-text-fill: #7f8c8d;");
        resourceStatusLabel.setWrapText(true);
        setStyled(resourceStatusLabel, "-fx-font-size: 11px; -fx-text-fill: #95a5a6; -fx-font-style: italic;");
        resourceStatusLabel.setManaged(false);
        resourceStatusLabel.setVisible(false);

        overallProgressBar.setMaxWidth(Double.MAX_VALUE);
        setStyled(overallProgressBar, "-fx-accent: #27ae60; -fx-background-color: #ecf0f1;");
        HBox.setHgrow(overallProgressBar, Priority.ALWAYS);

        setStyled(overallPercentageLabel, "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #27ae60;");

        HBox progressBox = new HBox(10, overallProgressBar, overallPercentageLabel);
        progressBox.setAlignment(Pos.CENTER_LEFT);

        VBox timeSection = buildTimeReportSection();

        statusBox.getChildren().addAll(statusLabel, detailedStatusLabel, resourceStatusLabel, progressBox, timeSection,
                individualProgressSection);

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

    private HBox buildIndividualProgressRow(BatchFileItem item) {
        Label nameLabel = new Label(item.getFileName());
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

    private VBox buildTimeReportSection() {
        VBox section = new VBox(5);

        Label header = new Label("⏱️ Time Report");
        setStyled(header, "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 13px;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(8);
        grid.setPadding(new Insets(5, 0, 0, 0));

        grid.add(titleLabel("File Time Spent:"),  0, 0);
        grid.add(fileTimeSpentLabel,              1, 0);
        grid.add(titleLabel("File Time Left:"),   2, 0);
        grid.add(fileTimeLeftLabel,               3, 0);
        grid.add(titleLabel("Total Time Spent:"), 4, 0);
        grid.add(totalTimeSpentLabel,             5, 0);
        grid.add(titleLabel("Total Time Left:"),  6, 0);
        grid.add(totalTimeLeftLabel,              7, 0);

        setStyled(fileTimeSpentLabel,  "-fx-font-weight: bold; -fx-font-size: 11px;");
        fileTimeSpentLabel.getStyleClass().add("tool-subheading");

        setStyled(fileTimeLeftLabel,   "-fx-font-weight: bold; -fx-font-size: 11px;");
        fileTimeLeftLabel.getStyleClass().add("status-negative");

        setStyled(totalTimeSpentLabel, "-fx-font-weight: bold; -fx-font-size: 11px;");
        totalTimeSpentLabel.getStyleClass().add("tool-subheading");

        setStyled(totalTimeLeftLabel,  "-fx-font-weight: bold; -fx-font-size: 11px;");
        totalTimeLeftLabel.getStyleClass().add("status-negative");

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

        setStyled(exitButton, "");
        exitButton.getStyleClass().add("action-btn-exit");
        exitButton.setOnAction(e -> onExitClick.run());

        box.getChildren().addAll(processButton, clearLogButton, exitButton);
        return box;
    }

    private Label titleLabel(String text) {
        Label l = new Label(text);
        setStyled(l, "-fx-font-weight: bold; -fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
        return l;
    }

    private void styleTimeValue(Label label, String color) {
        setStyled(label, "-fx-font-weight: bold; -fx-text-fill: " + color + "; -fx-font-size: 11px;");
    }

    public void updateStatus(String main, String detail) {
        Platform.runLater(() -> {
            statusLabel.setText(main);
            if (detail != null) detailedStatusLabel.setText(detail);
        });
    }

    public void updateResourceStatus(String message) {
        Platform.runLater(() -> {
            boolean show = message != null && !message.isBlank();
            resourceStatusLabel.setText(show ? message : "");
            resourceStatusLabel.setManaged(show);
            resourceStatusLabel.setVisible(show);
        });
    }

    public void setOverallProgress(double progress) {
        Platform.runLater(() -> {
            overallProgressBar.setProgress(progress);
            overallPercentageLabel.setText(String.format("%.0f%%", progress * 100));
        });
    }

    public void setProcessingState(boolean processing) {
        Platform.runLater(() -> {
            if (processing) {
                processButton.setText("⏹️ Cancel Processing");
                processButton.getStyleClass().setAll("action-btn-cancel");
                setStyled(overallProgressBar, "-fx-accent: #2196F3; -fx-background-color: #E3F2FD;");
            } else {
                processButton.setText("🚀 Start Processing");
                processButton.getStyleClass().setAll("action-btn-start");
                setStyled(overallProgressBar, "-fx-accent: #4CAF50; -fx-background-color: #E8F5E8;");
            }
            individualProgressSection.setVisible(processing);
            individualProgressSection.setManaged(processing);
            if (!processing) {
                individualProgressRows.getChildren().clear();
            }
        });
    }

    public void setProcessingEnabled(boolean enabled) {
        Platform.runLater(() -> processButton.setDisable(!enabled));
    }

    public void setDependencyCheckInProgress(boolean inProgress) {
        Platform.runLater(() -> processButton.setDisable(inProgress));
    }

    public void startBatchProcessing() {
        batchStartTimeMs = System.currentTimeMillis();
    }

    public Button getClearLogButton() { return clearLogButton; }
    public VBox   getRoot()           { return root; }

    public void updateDependencyStatus(DependencyManager.DependencyStatus ffmpegStatus,
                                       DependencyManager.DependencyStatus whisperStatus) {
        Platform.runLater(() -> {
            if (ffmpegStatus == null || whisperStatus == null) {
                statusLabel.setText("Dependency Check Failed");
                setStyled(statusLabel, "-fx-font-weight: bold; -fx-font-size: 16px;");
                statusLabel.getStyleClass().setAll("status-negative");
                detailedStatusLabel.setText("Could not verify system dependencies.");
                return;
            }

            if (ffmpegStatus.isAvailable()) {
                statusLabel.setText("Ready to Process");
                setStyled(statusLabel, "-fx-font-weight: bold; -fx-font-size: 16px;");
                statusLabel.getStyleClass().setAll("status-success");   // new class, see CSS below
            } else {
                statusLabel.setText("FFmpeg Missing");
                setStyled(statusLabel, "-fx-font-weight: bold; -fx-font-size: 16px;");
                statusLabel.getStyleClass().setAll("status-negative");
            }

            String detail = ffmpegStatus.getMessage();
            if (!whisperStatus.isAvailable()) detail += " | " + whisperStatus.getMessage();
            detailedStatusLabel.setText(detail);
        });
    }

    /**
     * FIX: previously this derived completed/failed/total AND the overall
     * percentage purely by scanning {@code items} — {@code total =
     * items.size()}, {@code completed}/{@code failed} counted by scanning
     * current statuses. That's correct only if {@code items} never shrinks.
     * But the caller (MainWindow) auto-removes COMPLETED items from that
     * same list moments after each one finishes — so on every tick after
     * the first, completed files simply aren't there to be counted anymore,
     * and both the "done" count AND the total (hence "pending") silently
     * went wrong together. PROCESSING items are never auto-removed, so
     * deriving the live "currently processing" rows and their partial
     * progress from {@code items} is still safe and kept; completed/failed/
     * total now come from the caller's own cumulative, removal-proof
     * counters instead of being re-derived here.
     */
    public void updateProgress(ObservableList<BatchFileItem> items,
                                int cumulativeCompleted, int cumulativeFailed, int cumulativeTotal) {
        Platform.runLater(() -> {
            if (cumulativeTotal == 0) {
                overallProgressBar.setProgress(0);
                overallPercentageLabel.setText("0%");
                detailedStatusLabel.setText("📊 No files in queue");
                individualProgressRows.getChildren().clear();
                return;
            }

            double processingWeighted = 0.0;
            int processing = 0;
            List<BatchFileItem> processingItems = new ArrayList<>();
            for (BatchFileItem item : items) {
                if ("PROCESSING".equals(item.getStatus())) {
                    processingWeighted += item.getProgress();
                    processing++;
                    processingItems.add(item);
                }
            }

            // Completed and failed files are both "done being worked on" —
            // each counts as a full 1.0 toward overall completion, same as
            // the old code did for completed (failed previously counted its
            // last partial progress instead of 1.0; since a failed file
            // won't be retried automatically, treating it as fully resolved
            // here is more representative of "how much of the batch is
            // finished, successfully or not").
            double overall = Math.min(1.0,
                    (cumulativeCompleted + cumulativeFailed + processingWeighted) / (double) cumulativeTotal);
            overallProgressBar.setProgress(overall);
            overallPercentageLabel.setText(String.format("%.0f%%", overall * 100));

            long pending = Math.max(0, cumulativeTotal - cumulativeCompleted - cumulativeFailed - processing);
            detailedStatusLabel.setText(String.format(
                    "📊 Queue: %d total | ⏳ %d pending | 🔄 %d processing | ✅ %d done | ❌ %d failed",
                    cumulativeTotal, pending, processing, cumulativeCompleted, cumulativeFailed));

            individualProgressRows.getChildren().setAll(
                    processingItems.stream().map(this::buildIndividualProgressRow).toList());

            LOGGER.debug("Queue progress updated: overall={}%, completed={}, failed={}, processing={}, pending={}",
                    String.format("%.1f", overall * 100), cumulativeCompleted, cumulativeFailed, processing, pending);
        });
    }

    public void updateProgress(int completed, int failed, int total) {
        Platform.runLater(() -> {
            if (total > 0) {
                double progress = (double)(completed + failed) / total;
                overallProgressBar.setProgress(progress);
                overallPercentageLabel.setText(String.format("%.0f%%", progress * 100));
            } else {
                overallProgressBar.setProgress(0);
                overallPercentageLabel.setText("0%");
            }
            LOGGER.debug("ControlPanel progress: {}/{}/{}", completed, failed, total);
        });
    }

    public void updateTimeEstimates(ObservableList<BatchFileItem> items, long currentTime) {
        if (timeEstimator == null) return;

        Platform.runLater(() -> {
            long fileSpent  = timeEstimator.getCurrentFileTimeSpent();
            long totalSpent = timeEstimator.getTotalTimeSpent();

            long fileLeft   = timeEstimator.getLiveCurrentFileTimeLeftMs();
            long totalLeft  = timeEstimator.getLiveTotalTimeLeftMs();

            LOGGER.debug("Time estimates — fileSpent={}, fileLeft={}, totalSpent={}, totalLeft={}",
                    fileSpent, fileLeft, totalSpent, totalLeft);

            fileTimeSpentLabel.setText(TimeLeftEstimator.formatTime(fileSpent));
            fileTimeLeftLabel.setText(TimeLeftEstimator.formatTime(fileLeft));
            totalTimeSpentLabel.setText(TimeLeftEstimator.formatTime(totalSpent));
            totalTimeLeftLabel.setText(TimeLeftEstimator.formatTime(totalLeft));

            int learnedProcesses = timeEstimator.getLearnedPatternCount();
            if (learnedProcesses > 0) {
                dataStatusLabel.setText("Using learned estimates (" + learnedProcesses
                        + " process type" + (learnedProcesses == 1 ? "" : "s") + " learned)");
                setStyled(dataStatusLabel, "-fx-font-size: 10px; -fx-text-fill: #2e7d32; -fx-font-style: italic;");
            } else {
                dataStatusLabel.setText("Using default estimates");
                setStyled(dataStatusLabel, "-fx-font-size: 10px; -fx-text-fill: #666; -fx-font-style: italic;");
            }
        });
    }
}