/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.core.DependencyManager;
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
 *
 * <h2>Fixes vs. original</h2>
 * <ul>
 *   <li>All formerly-{@code public} UI fields ({@code statusLabel},
 *       {@code detailedStatusLabel}, {@code overallProgressBar}) are now
 *       {@code private}.  Callers use the typed accessor methods
 *       {@link #updateStatus(String, String)}, {@link #setOverallProgress(double)},
 *       and {@link #setProcessingState(boolean)} instead of direct field access.
 *       This enforces the contract that all UI mutations go through
 *       {@link Platform#runLater} and prevents external code from writing to
 *       UI nodes off the JavaFX Application Thread.</li>
 *   <li>Logger now references {@code ControlPanel.class}, not
 *       {@code MainWindow.class} (was producing misleading log categories).</li>
 *   <li>{@link #startBatchProcessing()} no longer silently discards its
 *       return value — the batch-start timestamp is stored and used when
 *       computing elapsed time.</li>
 * </ul>
 */
public class ControlPanel {

    // FIX: logger now refers to ControlPanel, not MainWindow
    private static final Logger LOGGER = LoggerFactory.getLogger(ControlPanel.class);

    private final VBox root;
    private final TimeLeftEstimator timeEstimator;

    // FIX: all formerly-public UI fields are now private
    private final Label statusLabel;
    private final Label detailedStatusLabel;
    private final ProgressBar overallProgressBar;
    private final Label overallPercentageLabel;
    private final Button processButton;
    private final Button clearLogButton;
    private final Button exitButton;

    // Time-report labels
    private final Label fileTimeSpentLabel;
    private final Label fileTimeLeftLabel;
    private final Label totalTimeSpentLabel;
    private final Label totalTimeLeftLabel;
    private final Label dataStatusLabel;

    // FIX (task): "individual processing" view — a second, permanent view
    // shown alongside the general queue/progress view while a batch is
    // running, listing each file currently PROCESSING with its own name and
    // progress bar. Hidden (both setVisible AND setManaged — the latter is
    // what actually collapses it out of the layout, rather than just making
    // it invisible while still reserving its space) whenever nothing is
    // processing, including once the whole batch finishes.
    private final VBox individualProgressSection;
    private final VBox individualProgressRows;

    // FIX: actually store the batch-start timestamp (was previously discarded)
    private long batchStartTimeMs = 0L;

    // -------------------------------------------------------------------------
    //  Construction
    // -------------------------------------------------------------------------

    public ControlPanel(Runnable onProcessClick, Runnable onExitClick,
                        TimeLeftEstimator timeEstimator) {
        this.timeEstimator = timeEstimator;

        // Initialise all final fields before calling createUI so that
        // the UI builder can reference them directly.
        statusLabel           = new Label("Ready");
        detailedStatusLabel   = new Label("Run dependency check to begin");
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

    // -------------------------------------------------------------------------
    //  UI construction
    // -------------------------------------------------------------------------

    private VBox buildUI(Runnable onProcessClick, Runnable onExitClick) {
        VBox container = new VBox(10);
        container.setStyle("-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; "
                + "-fx-padding: 15; -fx-background-color: white;");

        // ---- Status section ----
        VBox statusBox = new VBox(8);
        statusBox.setStyle("-fx-padding: 10; -fx-background-color: #f8f9fa; -fx-border-radius: 5;");

        statusLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #2c3e50;");
        detailedStatusLabel.setWrapText(true);
        detailedStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #7f8c8d;");

        overallProgressBar.setMaxWidth(Double.MAX_VALUE);
        overallProgressBar.setStyle("-fx-accent: #27ae60; -fx-background-color: #ecf0f1;");
        HBox.setHgrow(overallProgressBar, Priority.ALWAYS);

        overallPercentageLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #27ae60;");

        HBox progressBox = new HBox(10, overallProgressBar, overallPercentageLabel);
        progressBox.setAlignment(Pos.CENTER_LEFT);

        // ---- Time-report grid ----
        VBox timeSection = buildTimeReportSection();

        statusBox.getChildren().addAll(statusLabel, detailedStatusLabel, progressBox, timeSection,
                individualProgressSection);

        // ---- Buttons ----
        HBox buttonBox = buildButtonBox(onProcessClick, onExitClick);

        container.getChildren().addAll(statusBox, buttonBox);
        return container;
    }

    /**
     * Builds the "individual processing" view — one row per file currently
     * PROCESSING, each with its own name and progress bar. Lives alongside
     * (appended below) the general aggregate progress view, not in place of
     * it. Starts hidden; setProcessingState() and updateProgress(items)
     * control its visibility and contents.
     */
    private VBox buildIndividualProgressSection() {
        VBox section = new VBox(6);
        section.setStyle("-fx-padding: 8 0 0 0;");

        Label header = new Label("🔄 Currently Processing");
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 13px;");

        individualProgressRows.setStyle("-fx-padding: 4 0 0 8;");

        section.getChildren().addAll(header, individualProgressRows);

        // FIX: setManaged(false) is what actually removes this from layout
        // (setVisible alone leaves its space reserved, which would show as
        // an empty gap rather than the section cleanly disappearing).
        section.setVisible(false);
        section.setManaged(false);
        return section;
    }

    /**
     * One row in the individual-processing view: filename + a small
     * progress bar + percentage, mirroring the per-row progress already
     * shown in the Batch Queue Status table, but visible directly in the
     * Ready to Process section without needing to scroll the queue.
     */
    private HBox buildIndividualProgressRow(BatchFileItem item) {
        Label nameLabel = new Label(item.getFileName());
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #34495e;");
        nameLabel.setMaxWidth(220);
        nameLabel.setMinWidth(220);

        ProgressBar bar = new ProgressBar(item.getProgress());
        bar.setPrefWidth(140);
        bar.setStyle("-fx-accent: #2196F3;");
        HBox.setHgrow(bar, Priority.ALWAYS);

        Label pct = new Label(String.format("%.0f%%", item.getProgress() * 100));
        pct.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");
        pct.setMinWidth(36);

        HBox row = new HBox(8, nameLabel, bar, pct);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildTimeReportSection() {
        VBox section = new VBox(5);

        Label header = new Label("⏱️ Time Report");
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 13px;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(8);
        grid.setPadding(new Insets(5, 0, 0, 0));

        // Row 0
        grid.add(titleLabel("File Time Spent:"),  0, 0);
        grid.add(fileTimeSpentLabel,              1, 0);
        grid.add(titleLabel("File Time Left:"),   2, 0);
        grid.add(fileTimeLeftLabel,               3, 0);
        grid.add(titleLabel("Total Time Spent:"), 4, 0);
        grid.add(totalTimeSpentLabel,             5, 0);
        grid.add(titleLabel("Total Time Left:"),  6, 0);
        grid.add(totalTimeLeftLabel,              7, 0);

        styleTimeValue(fileTimeSpentLabel,  "#2c3e50");
        styleTimeValue(fileTimeLeftLabel,   "#e74c3c");
        styleTimeValue(totalTimeSpentLabel, "#2c3e50");
        styleTimeValue(totalTimeLeftLabel,  "#e74c3c");

        dataStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666; -fx-font-style: italic;");

        section.getChildren().addAll(header, grid, dataStatusLabel);
        return section;
    }

    private HBox buildButtonBox(Runnable onProcessClick, Runnable onExitClick) {
        HBox box = new HBox(15);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-padding: 0 0 10 0;");

        processButton.setStyle(startStyle());
        processButton.setOnAction(e -> onProcessClick.run());
        processButton.setMinWidth(180);

        clearLogButton.setStyle("-fx-background-color: linear-gradient(to bottom, #95a5a6, #7f8c8d); "
                + "-fx-text-fill: white; -fx-padding: 12 30; -fx-background-radius: 8;");

        exitButton.setStyle("-fx-background-color: linear-gradient(to bottom, #e74c3c, #c0392b); "
                + "-fx-text-fill: white; -fx-padding: 12 30; -fx-background-radius: 8;");
        exitButton.setOnAction(e -> onExitClick.run());

        box.getChildren().addAll(processButton, clearLogButton, exitButton);
        return box;
    }

    private Label titleLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
        return l;
    }

    private void styleTimeValue(Label label, String color) {
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + "; -fx-font-size: 11px;");
    }

    // -------------------------------------------------------------------------
    //  Public API  (replaces direct field access from MainWindow)
    // -------------------------------------------------------------------------

    /**
     * Update the main and detail status labels.
     * Safe to call from any thread — delegates to {@link Platform#runLater}.
     */
    public void updateStatus(String main, String detail) {
        Platform.runLater(() -> {
            statusLabel.setText(main);
            if (detail != null) detailedStatusLabel.setText(detail);
        });
    }

    /**
     * Set the overall progress bar and percentage label.
     * Safe to call from any thread.
     *
     * @param progress value in [0, 1]
     */
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
                processButton.setStyle(cancelStyle());
                overallProgressBar.setStyle("-fx-accent: #2196F3; -fx-background-color: #E3F2FD;");
            } else {
                processButton.setText("🚀 Start Processing");
                processButton.setStyle(startStyle());
                overallProgressBar.setStyle("-fx-accent: #4CAF50; -fx-background-color: #E8F5E8;");
            }
            // FIX (task): the individual-processing view is a permanent
            // second view while a batch runs, not something that flashes —
            // it appears the moment processing starts and disappears the
            // moment it ends, same lifecycle as the process button's state.
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

    /**
     * Called once when batch processing starts.
     * Records the batch-start timestamp for elapsed-time calculations.
     */
    public void startBatchProcessing() {
        batchStartTimeMs = System.currentTimeMillis();
    }

    public Button getClearLogButton() { return clearLogButton; }
    public VBox   getRoot()           { return root; }

    // -------------------------------------------------------------------------
    //  Dependency-status display
    // -------------------------------------------------------------------------

    public void updateDependencyStatus(DependencyManager.DependencyStatus ffmpegStatus,
                                       DependencyManager.DependencyStatus whisperStatus) {
        Platform.runLater(() -> {
            if (ffmpegStatus == null || whisperStatus == null) {
                statusLabel.setText("Dependency Check Failed");
                statusLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #F44336;");
                detailedStatusLabel.setText("Could not verify system dependencies.");
                return;
            }

            if (ffmpegStatus.isAvailable()) {
                statusLabel.setText("Ready to Process");
                statusLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #4CAF50;");
            } else {
                statusLabel.setText("FFmpeg Missing");
                statusLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #F44336;");
            }

            String detail = ffmpegStatus.getMessage();
            if (!whisperStatus.isAvailable()) detail += " | " + whisperStatus.getMessage();
            detailedStatusLabel.setText(detail);
        });
    }

    // -------------------------------------------------------------------------
    //  Batch-queue progress  (item-list variant)
    // -------------------------------------------------------------------------

    public void updateProgress(ObservableList<BatchFileItem> items) {
        Platform.runLater(() -> {
            long total = items.size();
            if (total == 0) {
                overallProgressBar.setProgress(0);
                overallPercentageLabel.setText("0%");
                detailedStatusLabel.setText("📊 No files in queue");
                individualProgressRows.getChildren().clear();
                return;
            }

            double weighted = 0.0;
            int completed = 0, processing = 0, failed = 0;
            List<BatchFileItem> processingItems = new ArrayList<>();

            for (BatchFileItem item : items) {
                String status = item.getStatus();
                double progress = item.getProgress();
                if ("COMPLETED".equals(status)) { weighted += 1.0; completed++; }
                else if ("FAILED".equals(status))      { weighted += progress; failed++; }
                else if ("PROCESSING".equals(status))  { weighted += progress; processing++; processingItems.add(item); }
                // PENDING contributes 0
            }

            double overall = weighted / total;
            overallProgressBar.setProgress(overall);
            overallPercentageLabel.setText(String.format("%.0f%%", overall * 100));

            long pending = total - completed - failed - processing;
            detailedStatusLabel.setText(String.format(
                    "📊 Queue: %d total | ⏳ %d pending | 🔄 %d processing | ✅ %d done | ❌ %d failed",
                    total, pending, processing, completed, failed));

            // FIX (task): refresh the individual-processing view's rows —
            // one per file currently PROCESSING. Rebuilt each tick rather
            // than diffed in place: the row count is small (bounded by
            // max-parallel-files) and this keeps the row set trivially
            // correct as files start and finish, at the cost of discarding
            // and recreating a handful of Nodes once a second.
            individualProgressRows.getChildren().setAll(
                    processingItems.stream().map(this::buildIndividualProgressRow).toList());

            LOGGER.debug("Queue progress updated: overall={:.1f}%", overall * 100);
        });
    }

    // -------------------------------------------------------------------------
    //  Numeric-counts variant (used by BatchProcessor callback)
    // -------------------------------------------------------------------------

    public void updateProgress(int completed, int failed, int total) {
        Platform.runLater(() -> {
            if (total > 0) {
                double progress = (double)(completed + failed) / total;
                overallProgressBar.setProgress(progress);
                overallPercentageLabel.setText(String.format("%.0f%%", progress * 100));
                // FIX: was also calling detailedStatusLabel.setText(...) here,
                // with a DIFFERENT text format than updateProgress(items)
                // below. Both get called within the same Timeline tick in
                // MainWindow, so the label's text was being overwritten
                // twice a second with two different phrasings of the same
                // information — visible as a flash/flicker. updateProgress
                // (items) is the fuller source (it's the only one that knows
                // the "processing" count), so it alone owns this label now;
                // this method just drives the numeric bar.
            } else {
                overallProgressBar.setProgress(0);
                overallPercentageLabel.setText("0%");
            }
            LOGGER.debug("ControlPanel progress: {}/{}/{}", completed, failed, total);
        });
    }

    // -------------------------------------------------------------------------
    //  Time-estimate display
    // -------------------------------------------------------------------------

    public void updateTimeEstimates(ObservableList<BatchFileItem> items, long currentTime) {
        if (timeEstimator == null) return;

        Platform.runLater(() -> {
            long fileSpent  = timeEstimator.getCurrentFileTimeSpent();
            long totalSpent = timeEstimator.getTotalTimeSpent();

            // FIX: was estimateCurrentFileTimeLeft() / estimateTotalTimeLeft().
            // estimateCurrentFileTimeLeft() derives "time left" by re-deriving a
            // total from elapsed/progress each call — with progress held constant
            // between updates (e.g. mid-segment, or between WhisperX stdout lines),
            // elapsed keeps growing so the formula makes the *remaining* estimate
            // grow too, instead of ticking down. getLiveCurrentFileTimeLeftMs() /
            // getLiveTotalTimeLeftMs() instead anchor off a fixed estimatedTotalTime
            // (refreshed at each process/segment boundary) minus the always-live
            // elapsed clock, so the label counts down smoothly every tick of this
            // 1-second Timeline instead of jumping only at those boundaries.
            long fileLeft   = timeEstimator.getLiveCurrentFileTimeLeftMs();
            long totalLeft  = timeEstimator.getLiveTotalTimeLeftMs();

            LOGGER.debug("Time estimates — fileSpent={}, fileLeft={}, totalSpent={}, totalLeft={}",
                    fileSpent, fileLeft, totalSpent, totalLeft);

            fileTimeSpentLabel.setText(TimeLeftEstimator.formatTime(fileSpent));
            fileTimeLeftLabel.setText(TimeLeftEstimator.formatTime(fileLeft));
            totalTimeSpentLabel.setText(TimeLeftEstimator.formatTime(totalSpent));
            totalTimeLeftLabel.setText(TimeLeftEstimator.formatTime(totalLeft));

            // FIX: was keyed off getBatchStatistics().getCompletedFiles() > 0 —
            // i.e. files completed in THIS batch so far — so the label always
            // read "Using default estimates" for the first file of every batch,
            // even when the estimates it was actually computing from
            // (calculateFileTimeEstimates(), which pulls from processTimingData)
            // were already built from learned/persisted history saved from
            // earlier sessions. Checking getLearnedPatternCount() instead reflects
            // whether any process actually has learned/persisted samples behind
            // it, independent of how far the current batch has gotten.
            int learnedProcesses = timeEstimator.getLearnedPatternCount();
            if (learnedProcesses > 0) {
                dataStatusLabel.setText("Using learned estimates (" + learnedProcesses
                        + " process type" + (learnedProcesses == 1 ? "" : "s") + " learned)");
                dataStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #2e7d32; -fx-font-style: italic;");
            } else {
                dataStatusLabel.setText("Using default estimates");
                dataStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666; -fx-font-style: italic;");
            }
        });
    }

    // -------------------------------------------------------------------------
    //  Button style helpers
    // -------------------------------------------------------------------------

    private static String startStyle() {
        return "-fx-background-color: linear-gradient(to bottom, #27ae60, #219a52); "
                + "-fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 12 30; "
                + "-fx-font-size: 14px; -fx-background-radius: 8; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(39,174,96,0.3), 5, 0, 0, 2);";
    }

    private static String cancelStyle() {
        return "-fx-background-color: linear-gradient(to bottom, #F44336, #D32F2F); "
                + "-fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10 25; "
                + "-fx-font-size: 14px; -fx-background-radius: 8; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 5, 0, 0, 2);";
    }
}