/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.core.FileTimingReport;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

/**
 * The "Tools -&gt; Performance Report..." dialog — a table of per-file
 * timing/resource breakdowns. Extracted out of {@code MainWindow}, which
 * had grown to over 2,000 lines covering window chrome, menus, dependency
 * checks, batch orchestration wiring, AND several self-contained dialogs
 * like this one all in a single file.
 *
 * <p>Stateless — construct and call {@link #show(List)} whenever the menu
 * item fires; there's no reason to keep an instance around between opens.</p>
 */
public class PerformanceReportDialog {

    public void show(List<FileTimingReport> reports) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Performance Report");
        dialog.setHeaderText(reports.isEmpty()
                ? "No files processed yet this session"
                : "Stage-by-stage timing for the last " + reports.size() + " file(s) processed");

        TableView<FileTimingReport> table = new TableView<>();
        table.setItems(FXCollections.observableArrayList(reports));
        table.setPrefSize(900, 400);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        table.getColumns().add(reportColumn("File", 220, FileTimingReport::getFileName));
        table.getColumns().add(millisColumn("Queue Wait", "queue_wait"));
        table.getColumns().add(millisColumn("Model Acq.", "model_acquisition"));
        table.getColumns().add(millisColumn("Model Load", "model_load"));
        table.getColumns().add(millisColumn("Audio Load", "audio_load"));
        table.getColumns().add(millisColumn("Preprocessing", "preprocessing"));
        table.getColumns().add(millisColumn("Transcription", "transcription"));
        table.getColumns().add(millisColumn("Alignment", "alignment"));
        table.getColumns().add(millisColumn("Diarization", "diarization"));
        table.getColumns().add(reportColumn("Subtitle Gen", 100, r -> {
            long srt = r.getStageMillis("subtitle_generation");
            long txt = r.getStageMillis("txt_generation");
            long total = Math.max(0, srt) + Math.max(0, txt);
            return (srt >= 0 || txt >= 0) ? String.format("%.1fs", total / 1000.0) : "—";
        }));
        table.getColumns().add(millisColumn("Output Saving", "output_saving"));
        table.getColumns().add(millisColumn("Total", "total_pipeline"));
        // Peak Mem / Avg CPU are the real thing — sampled throughout the
        // whole transcribe() call by the Python script itself (RSS memory
        // and CPU% of the Python process and any children it spawns) —
        // rather than a single JVM-wide system-CPU snapshot taken when the
        // file finished.
        table.getColumns().add(reportColumn("Peak Mem (MB)", 100,
                r -> r.getPythonPeakMemoryMb() >= 0 ? String.format("%.0f", r.getPythonPeakMemoryMb()) : "—"));
        table.getColumns().add(reportColumn("Avg CPU %", 90,
                r -> r.getPythonAvgCpuPercent() >= 0 ? String.format("%.0f%%", r.getPythonAvgCpuPercent()) : "—"));

        Label note = new Label(
                "Peak Mem / Avg CPU are measured by the Python transcription process itself across the whole file "
                + "(\u2014 if psutil isn't installed in that Python environment). Stages missing from a script "
                + "without STAGE_TIMING instrumentation (e.g. a custom override script) also show as \u2014.");
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

        VBox content = new VBox(10, table, note);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private TableColumn<FileTimingReport, String> millisColumn(String title, String stageKey) {
        return reportColumn(title, 100, r -> {
            long ms = r.getStageMillis(stageKey);
            return ms >= 0 ? String.format("%.1fs", ms / 1000.0) : "—";
        });
    }

    private TableColumn<FileTimingReport, String> reportColumn(
            String title, double width, Function<FileTimingReport, String> extractor) {
        TableColumn<FileTimingReport, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(extractor.apply(cellData.getValue())));
        return col;
    }
}