/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.util.PreferenceManager;
import audiomanager.model.BatchFileItem;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.DirectoryChooser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;

/**
 * "Sound Recorder Panel" — microphone input recording, added as a peer
 * section to Audio File Selection rather than a Tools-accordion entry,
 * per the request that named it a first-class panel.
 *
 * <h2>Recording mechanics</h2>
 * Captures via a blocking {@link TargetDataLine} read loop on a dedicated
 * background thread (JavaFX's own thread must never block on I/O), streamed
 * to a temporary raw-PCM file rather than buffered in memory — this app is
 * already careful about memory pressure everywhere else (see
 * ResourceMonitor / ParallelProcessingManager's adaptive scaling), so an
 * unbounded in-memory buffer for a recording of arbitrary length (a
 * lecture, an interview) would be inconsistent with that. The raw PCM is
 * wrapped into a proper WAV file (correct header, correct frame count)
 * only once, at the end of the recording, then the temp file is deleted.
 *
 * <h2>Destination choice</h2>
 * Per explicit product decision: every recording can go to EITHER the
 * existing batch queue (transcribed like any selected file) OR stand alone
 * (saved only) — the user picks per-recording, immediately before it
 * starts, via {@link #onRecordClicked()}'s confirmation dialog, rather than
 * a global setting that would be easy to forget was set to the "wrong"
 * mode for a given recording.
 */
public class SoundRecorderPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundRecorderPanel.class);

    /** See FileSelectionPanel.setStyled() for why every setStyle() call in this class routes through here. */
    private static void setStyled(javafx.scene.Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    private static final String OPTION_QUEUE =
            "Add to Batch Queue (process with transcription)";
    private static final String OPTION_STANDALONE =
            "Save Only (standalone recording)";

    // Mono, 16-bit, 44.1kHz PCM — sufficient fidelity for speech
    // transcription while keeping file size and CPU load down relative to
    // stereo/higher sample rates neither WhisperX nor human speech need.
    private static final AudioFormat RECORDING_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            44100.0f, // sample rate
            16,       // sample size in bits
            1,        // channels (mono)
            2,        // frame size in bytes = channels * (sampleSizeInBits / 8)
            44100.0f, // frame rate
            false);   // little-endian

    private final ObservableList<BatchFileItem> batchFiles;
    private final PreferenceManager prefManager;
    private final Consumer<String> logger;

    private ComboBox<MixerEntry> deviceComboBox;
    private Button recordButton;
    private Button stopButton;
    private Button playButton;
    private Label statusLabel;
    private Label durationLabel;
    private ProgressBar levelMeter;
    private TextField outputPathField;
    private Button chooseLocationButton;

    private volatile TargetDataLine targetLine = null;
    private volatile boolean recording = false;
    private boolean addRecordingToQueue = false;
    private Thread captureThread;
    private File lastRecordedFile;
    private MediaPlayer mediaPlayer;

    private long recordingStartMs = 0;
    private javafx.animation.Timeline durationTicker;

    public SoundRecorderPanel(ObservableList<BatchFileItem> batchFiles,
                               PreferenceManager prefManager,
                               Consumer<String> logger) {
        this.batchFiles = batchFiles;
        this.prefManager = prefManager;
        this.logger = logger;
    }

    private void log(String message) {
        if (logger != null) logger.accept(message);
    }

    /** Wraps a Mixer.Info so the ComboBox displays a readable device name. */
    private static class MixerEntry {
        final Mixer.Info info;
        MixerEntry(Mixer.Info info) { this.info = info; }
        @Override public String toString() { return info.getName(); }
    }

    public VBox getRecorderSection() {
        VBox section = new VBox(10);
        setStyled(section, "-fx-border-color: #bdc3c7; -fx-border-width: 0 0 1 0; "
                + "-fx-padding: 15; -fx-background-color: white;");
        section.getStyleClass().add("theme-fix-surface");

        Label titleLabel = new Label("🎙️ Sound Recorder Panel");
        setStyled(titleLabel, "-fx-font-weight: bold; -fx-font-size: 16px;");
        titleLabel.getStyleClass().add("panel-heading");

        HBox deviceRow = new HBox(10);
        deviceRow.setAlignment(Pos.CENTER_LEFT);
        Label deviceLabel = new Label("Input Device:");
        deviceLabel.setMinWidth(90);
        deviceComboBox = new ComboBox<>();
        deviceComboBox.setPrefWidth(320);
        populateDevices();
        deviceRow.getChildren().addAll(deviceLabel, deviceComboBox);

        HBox controlsRow = new HBox(10);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        recordButton = new Button("⏺ Record");
        recordButton.setOnAction(e -> onRecordClicked());
        setStyled(recordButton, "-fx-font-weight: bold; -fx-text-fill: white; -fx-background-radius: 4;");
        recordButton.getStyleClass().add("tool-button-red");

        stopButton = new Button("⏹ Stop");
        stopButton.setOnAction(e -> stopRecording());
        stopButton.setDisable(true);
        setStyled(stopButton, "-fx-background-radius: 4;");
        stopButton.getStyleClass().add("action-btn-clear-queue-disabled");

        playButton = new Button("▶️ Play");
        playButton.setOnAction(e -> playLastRecording());
        playButton.setDisable(true);
        setStyled(playButton, "-fx-background-radius: 4;");
        playButton.getStyleClass().add("action-btn-clear-queue-disabled");

        durationLabel = new Label("00:00");
        setStyled(durationLabel, "-fx-font-weight: bold; -fx-font-size: 13px;");
        durationLabel.getStyleClass().add("tool-subheading");

        controlsRow.getChildren().addAll(recordButton, stopButton, playButton, durationLabel);

        levelMeter = new ProgressBar(0);
        levelMeter.setPrefWidth(430);
        levelMeter.setStyle("-fx-accent: #27ae60;");

        statusLabel = new Label("Ready to record");
        statusLabel.getStyleClass().add("tool-muted-text");

        HBox outputRow = new HBox(10);
        outputRow.setAlignment(Pos.CENTER_LEFT);
        Label outputLabel = new Label("Save to:");
        outputLabel.setMinWidth(90);
        outputPathField = new TextField(prefManager.getLastSoundRecorderLocation());
        outputPathField.setEditable(false);
        HBox.setHgrow(outputPathField, Priority.ALWAYS);
        chooseLocationButton = new Button("📂 Choose...");
        chooseLocationButton.setOnAction(e -> chooseLocation());
        setStyled(chooseLocationButton, "-fx-text-fill: white; -fx-background-radius: 4;");
        chooseLocationButton.getStyleClass().add("tool-button-purple");
        outputRow.getChildren().addAll(outputLabel, outputPathField, chooseLocationButton);

        section.getChildren().addAll(titleLabel, deviceRow, controlsRow, levelMeter, statusLabel, outputRow);
        return section;
    }

    private void populateDevices() {
        deviceComboBox.getItems().clear();
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, RECORDING_FORMAT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.isLineSupported(targetInfo)) {
                    deviceComboBox.getItems().add(new MixerEntry(info));
                }
            } catch (Exception e) {
                // Some mixer entries (virtual/loopback devices on certain
                // drivers) throw on isLineSupported() rather than just
                // returning false — skip rather than let one bad entry
                // block populating the rest of the list.
                LOGGER.debug("Skipping unusable mixer '{}': {}", info.getName(), e.getMessage());
            }
        }
        if (!deviceComboBox.getItems().isEmpty()) {
            deviceComboBox.getSelectionModel().selectFirst();
        }
    }

    private void onRecordClicked() {
        if (deviceComboBox.getSelectionModel().getSelectedItem() == null) {
            showAlert("No microphone input device available. Check your system's audio input settings.");
            return;
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(OPTION_QUEUE, OPTION_QUEUE, OPTION_STANDALONE);
        dialog.setTitle("Sound Recorder");
        dialog.setHeaderText("Where should this recording go?");
        dialog.setContentText("Choice:");
        ThemeManager.applyCurrentThemeToDialog(dialog.getDialogPane(), null);

        dialog.showAndWait().ifPresent(choice -> startRecording(OPTION_QUEUE.equals(choice)));
    }

    private void startRecording(boolean addToQueue) {
        MixerEntry selected = deviceComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        try {
            Mixer mixer = AudioSystem.getMixer(selected.info);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, RECORDING_FORMAT);
            targetLine = (TargetDataLine) mixer.getLine(info);
            targetLine.open(RECORDING_FORMAT);
            targetLine.start();
        } catch (LineUnavailableException e) {
            LOGGER.error("Failed to open microphone line: {}", e.getMessage());
            showAlert("Could not access the microphone — it may be in use by another "
                    + "application: " + e.getMessage());
            targetLine = null;
            return;
        }

        this.addRecordingToQueue = addToQueue;
        recording = true;
        recordingStartMs = System.currentTimeMillis();
        updateControlsForRecordingState(true);
        startDurationTicker();
        log("🎙️ Recording started (" + selected + ")" + (addToQueue ? " — will add to batch queue" : " — standalone"));

        captureThread = new Thread(this::captureLoop, "sound-recorder-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void stopRecording() {
        // Setting the flag and calling stop() is enough to make the
        // capture loop's blocking read() return promptly on virtually
        // every mixer implementation; close() happens in captureLoop's
        // finally block once the loop actually exits, rather than here,
        // to avoid closing the line out from under a read() that's still
        // in flight on the capture thread.
        recording = false;
        if (targetLine != null) {
            targetLine.stop();
        }
        stopDurationTicker();
    }

    private void captureLoop() {
        File tempRaw = null;
        try {
            tempRaw = File.createTempFile("asom_recording_", ".raw");
            byte[] buffer = new byte[4096];
            long totalBytesWritten = 0;

            try (FileOutputStream fos = new FileOutputStream(tempRaw);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                while (recording) {
                    int bytesRead = targetLine.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        bos.write(buffer, 0, bytesRead);
                        totalBytesWritten += bytesRead;
                        double level = computeLevel(buffer, bytesRead);
                        Platform.runLater(() -> levelMeter.setProgress(level));
                    }
                }
                bos.flush();
            }

            if (totalBytesWritten < RECORDING_FORMAT.getFrameSize()) {
                // Stopped essentially immediately — nothing meaningful to save.
                Platform.runLater(() -> {
                    updateControlsForRecordingState(false);
                    statusLabel.setText("Recording too short — discarded");
                });
                return;
            }

            long frameLength = totalBytesWritten / RECORDING_FORMAT.getFrameSize();
            File outputFile = buildOutputFile();
            try (FileInputStream fis = new FileInputStream(tempRaw);
                 AudioInputStream ais = new AudioInputStream(fis, RECORDING_FORMAT, frameLength)) {
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputFile);
            }

            lastRecordedFile = outputFile;
            Platform.runLater(() -> onRecordingSaved(outputFile));

        } catch (IOException e) {
            LOGGER.error("Recording failed: {}", e.getMessage());
            Platform.runLater(() -> {
                updateControlsForRecordingState(false);
                showAlert("Recording failed: " + e.getMessage());
            });
        } finally {
            if (tempRaw != null && !tempRaw.delete()) {
                LOGGER.debug("Could not delete temp recording file: {}", tempRaw);
            }
            if (targetLine != null) {
                targetLine.close();
                targetLine = null;
            }
        }
    }

    /** RMS-based level (0.0–1.0) for a 16-bit mono PCM buffer, scaled for visible meter movement at typical speech volume. */
    private static double computeLevel(byte[] buffer, int length) {
        long sumSquares = 0;
        int sampleCount = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sumSquares += (long) sample * sample;
            sampleCount++;
        }
        if (sampleCount == 0) return 0.0;
        double rms = Math.sqrt((double) sumSquares / sampleCount);
        return Math.min(1.0, (rms / 32768.0) * 4.0);
    }

    private File buildOutputFile() {
        String dir = outputPathField.getText();
        if (dir == null || dir.isBlank()) {
            dir = prefManager.getLastSoundRecorderLocation();
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return new File(dir, "recording_" + timestamp + ".wav");
    }

    private void onRecordingSaved(File outputFile) {
        updateControlsForRecordingState(false);
        playButton.setDisable(false);
        playButton.getStyleClass().setAll("tool-button-green");
        statusLabel.setText("Saved: " + outputFile.getName());
        log("🎙️ Recording saved: " + outputFile.getAbsolutePath());

        if (addRecordingToQueue) {
            BatchFileItem item = new BatchFileItem(outputFile);
            batchFiles.add(item);
            log("➕ Added recording to batch queue: " + outputFile.getName());
        }
    }

    private void updateControlsForRecordingState(boolean active) {
        recordButton.setDisable(active);
        deviceComboBox.setDisable(active);
        chooseLocationButton.setDisable(active);

        stopButton.setDisable(!active);
        stopButton.getStyleClass().setAll(active ? "action-btn-cancel" : "action-btn-clear-queue-disabled");

        statusLabel.setText(active ? "🔴 Recording..." : "Ready to record");
        if (!active) {
            durationLabel.setText("00:00");
            levelMeter.setProgress(0);
        }
    }

    private void startDurationTicker() {
        durationTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> updateDurationLabel()));
        durationTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        durationTicker.play();
    }

    private void stopDurationTicker() {
        if (durationTicker != null) {
            durationTicker.stop();
            durationTicker = null;
        }
    }

    private void updateDurationLabel() {
        long elapsedSeconds = (System.currentTimeMillis() - recordingStartMs) / 1000;
        durationLabel.setText(String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60));
    }

    private void chooseLocation() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Save Location for Recordings");
        File current = new File(outputPathField.getText());
        if (current.exists()) {
            chooser.setInitialDirectory(current);
        }
        File dir = chooser.showDialog(null);
        if (dir != null) {
            outputPathField.setText(dir.getAbsolutePath());
            prefManager.setLastSoundRecorderLocation(dir.getAbsolutePath());
        }
    }

    private void playLastRecording() {
        if (lastRecordedFile == null || !lastRecordedFile.exists()) return;
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.dispose();
            }
            Media media = new Media(lastRecordedFile.toURI().toString());
            mediaPlayer = new MediaPlayer(media);
            mediaPlayer.setOnEndOfMedia(() -> playButton.setText("▶️ Play"));
            mediaPlayer.play();
            playButton.setText("⏸ Playing...");
        } catch (Exception e) {
            LOGGER.warn("Playback failed: {}", e.getMessage());
            showAlert("Could not play recording: " + e.getMessage());
        }
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Sound Recorder");
        alert.setHeaderText(null);
        alert.setContentText(message);
        ThemeManager.applyCurrentThemeToDialog(alert.getDialogPane(), null);
        alert.showAndWait();
    }

    /**
     * Call on application exit to release the microphone line and any
     * open media player cleanly — an open TargetDataLine left dangling
     * would keep the OS's audio input device locked after the app closes
     * on some platforms.
     */
    public void shutdown() {
        recording = false;
        if (targetLine != null) {
            try {
                targetLine.stop();
                targetLine.close();
            } catch (Exception e) {
                LOGGER.debug("Error closing recording line during shutdown: {}", e.getMessage());
            }
        }
        if (mediaPlayer != null) {
            mediaPlayer.dispose();
        }
    }
}