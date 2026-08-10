/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.constants.AppConstants;
import audiomanager.constants.PreferenceKeys;
import audiomanager.ui.ThemeManager;
import audiomanager.model.ProcessingConfig;
import audiomanager.model.TranscriptionConfig;
import audiomanager.util.PreferenceManager;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration panel for audio processing and transcription settings
 */
public class ConfigurationPanel {

    /** See FileSelectionPanel.setStyled() for why every setStyle() call in this class routes through here. */
    private static void setStyled(javafx.scene.Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationPanel.class);
    
    private final ScrollPane root;
    private final PreferenceManager prefManager;

    // UI Components
    private ComboBox<String> modelComboBox;
    private ComboBox<String> languageComboBox;
    private ComboBox<String> outputFormatComboBox;
    private ComboBox<String> bitrateComboBox;
    private Slider volumeSlider;
    private Slider fontSizeSlider;
    private Spinner<Integer> maxParallelSpinner;

    private CheckBox noiseReductionCheckBox;
    private ComboBox<TranscriptionConfig.TimestampMode> timestampModeComboBox;
    private CheckBox confidenceCheckBox;
    private CheckBox exportWordCopyCheckBox;
    private CheckBox exportPdfCopyCheckBox;
    private CheckBox keepProcessedCheckBox;
    private CheckBox enableTranscriptionCheckBox;
    private CheckBox autoRemoveCompletedCheckBox;
    private CheckBox adaptiveScalingCheckBox;
    private CheckBox removeSilenceCheckBox;
    private CheckBox normalizeCheckBox;
    private Slider silenceThresholdSlider;
    private Slider silenceDurationSlider;
    private Consumer<Double> fontSizeChangeListener;

    // New components for enhanced control
    private ComboBox<TranscriptionConfig.OutputFormat> transcriptionFormatComboBox;
    private Spinner<Integer> srtMaxCharsSpinner;
    private Spinner<Integer> srtMaxLinesSpinner;
    private Spinner<Double> maxSegmentDurationSpinner;
    private ComboBox<String> logLevelComboBox;

    public ConfigurationPanel(PreferenceManager prefManager) {
        this.prefManager = prefManager;
        
        // Initialize components BEFORE creating the root pane
        initializeComponents();
        setupListeners();
        
        this.root = createRootPane(); // Now components are initialized
        loadPreferences();
    }
    
    public ScrollPane getRoot() {
        return root;
    }
    
    private ScrollPane createRootPane() {
        VBox mainContent = new VBox(20);
        mainContent.setPadding(new Insets(20));
        
        // Add all sections to the main content
        mainContent.getChildren().addAll(
            createUISection(),
            createBatchSection(),
            createWhisperSection(),
            createAudioSection()
        );
        
        ScrollPane scrollPane = new ScrollPane(mainContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(600);
        return scrollPane;
    }
    
    private void setupListeners() {
        // Setup change listeners for UI controls
        if (removeSilenceCheckBox != null && silenceThresholdSlider != null && silenceDurationSlider != null) {
            removeSilenceCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                silenceThresholdSlider.setDisable(!newVal);
                silenceDurationSlider.setDisable(!newVal);
            });
        }
    }

    public void savePreferences() {
        try {
            // Save Transcription
            prefManager.putString(PreferenceKeys.MODEL, modelComboBox.getValue());
            prefManager.putString(PreferenceKeys.LANGUAGE, languageComboBox.getValue());
            
            // Save timestamp mode
            if (timestampModeComboBox.getValue() != null) {
                prefManager.putString("timestamp_mode", timestampModeComboBox.getValue().getValue());
            }

            // Save Processing
            prefManager.putString(PreferenceKeys.OUTPUT_FORMAT, outputFormatComboBox.getValue());
            prefManager.putString(PreferenceKeys.BITRATE, bitrateComboBox.getValue());
            prefManager.putDouble(PreferenceKeys.VOLUME_BOOST, volumeSlider.getValue());
            
            // Save CheckBoxes
            // FIX: previously wrote directly to PreferenceKeys.NOISE_REDUCTION_ENABLED
            // while loadPreferences() (below) read back PreferenceKeys.NOISE_REDUCTION —
            // a different key — so the checkbox silently reset to its default every
            // restart. Routing through PreferenceManager's own
            // setNoiseReductionEnabled() (which already existed, already used the
            // correct _ENABLED key, and was already called correctly elsewhere —
            // e.g. MainWindow.debugPreferences()'s isNoiseReductionEnabled() call —
            // but was never actually written to by this class) makes this the
            // single source of truth instead of two independently-written paths
            // that could drift apart, which is exactly what happened.
            prefManager.setNoiseReductionEnabled(noiseReductionCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.CONFIDENCE_ENABLED, confidenceCheckBox.isSelected());
            prefManager.putBoolean("export_word_copy", exportWordCopyCheckBox.isSelected());
            prefManager.putBoolean("export_pdf_copy", exportPdfCopyCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.KEEP_PROCESSED, keepProcessedCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.TRANSCRIPTION_ENABLED, enableTranscriptionCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, autoRemoveCompletedCheckBox.isSelected());
            prefManager.putBoolean("adaptive_scaling_enabled", adaptiveScalingCheckBox.isSelected());
            prefManager.putBoolean("remove_silence", removeSilenceCheckBox.isSelected());
            // FIX: same bug shape as noise reduction above, just across classes
            // instead of within one method: this used to write the raw
            // "normalize_audio" key directly, while PreferenceManager's own
            // setNormalizeAudioEnabled()/isNormalizeAudioEnabled() read/write a
            // different key, "normalize_audio_enabled" — the two never agreed.
            // isNormalizeAudioEnabled() is currently only called by
            // MainWindow.debugPreferences(), so the live impact today was limited
            // to that log line always showing "false" regardless of the real
            // setting — but it's the identical landmine that caused the
            // noise-reduction bug, just not yet triggered by a second caller.
            // Routing through the convenience method here closes it off the same way.
            prefManager.setNormalizeAudioEnabled(normalizeCheckBox.isSelected());
            
            // Save Silence Detection
            prefManager.putDouble("silence_threshold", silenceThresholdSlider.getValue());
            prefManager.putDouble("silence_duration", silenceDurationSlider.getValue());
            
            // Save Max Parallel Files
            prefManager.putInt(PreferenceKeys.MAX_PARALLEL, maxParallelSpinner.getValue());
            
            // Save Font Size
            prefManager.setFontSize(fontSizeSlider.getValue());
            
            // Save new transcription settings
            if (transcriptionFormatComboBox.getValue() != null) {
                prefManager.putString("transcription_format", transcriptionFormatComboBox.getValue().getValue());
            }
            
            prefManager.putInt("srt_max_chars", srtMaxCharsSpinner.getValue());
            prefManager.putInt("srt_max_lines", srtMaxLinesSpinner.getValue());
            prefManager.putDouble("max_segment_duration", maxSegmentDurationSpinner.getValue());

            if (logLevelComboBox.getValue() != null) {
                prefManager.putString("log_level", logLevelComboBox.getValue());
            }

            prefManager.flush();
            LOGGER.debug("Configuration preferences saved successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to save preferences: {}", e.getMessage(), e);
        }
    }

    public void refreshUI() {
        loadPreferences();
    }
    
    private void initializeComponents() {
        // Initialize all UI components here
        modelComboBox = new ComboBox<>(FXCollections.observableArrayList(
            "tiny", "base", "small", "medium", "large"
        ));
        modelComboBox.setPrefWidth(150);
        
        languageComboBox = new ComboBox<>(FXCollections.observableArrayList(
            "auto", "en", "es", "de", "fr", "it", "zh", "ja", "ru", "ko",
            "pt", "tr", "pl", "ca", "nl", "ar", "sv", "id", "hi", "fi",
            "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta"
        ));
        languageComboBox.setPrefWidth(150);
        
        outputFormatComboBox = new ComboBox<>(FXCollections.observableArrayList(AppConstants.OUTPUT_FORMATS));
        outputFormatComboBox.setPrefWidth(120);
        
        bitrateComboBox = new ComboBox<>(FXCollections.observableArrayList(AppConstants.BITRATES));
        bitrateComboBox.setPrefWidth(120);

        // Volume slider
        volumeSlider = new Slider(
            AppConstants.MIN_VOLUME_BOOST,
            AppConstants.MAX_VOLUME_BOOST,
            AppConstants.DEFAULT_VOLUME
        );
        volumeSlider.setShowTickMarks(true);
        volumeSlider.setShowTickLabels(true);
        volumeSlider.setMajorTickUnit(AppConstants.VOLUME_BOOST_TICK);
        volumeSlider.setPrefWidth(200);

        // Font size slider
        fontSizeSlider = new Slider(8, 20, AppConstants.DEFAULT_FONT_SIZE);
        fontSizeSlider.setShowTickMarks(true);
        fontSizeSlider.setShowTickLabels(true);
        fontSizeSlider.setMajorTickUnit(2);
        fontSizeSlider.setPrefWidth(200);

        // Parallel processing spinner
        int cores = Runtime.getRuntime().availableProcessors();
        maxParallelSpinner = new Spinner<>(1, cores * 2, Math.max(1, cores / 2));
        maxParallelSpinner.setPrefWidth(80);
        maxParallelSpinner.setEditable(true);

        // Checkboxes
        noiseReductionCheckBox = new CheckBox("Enable Noise Reduction");
        confidenceCheckBox = new CheckBox("Include Confidence Scores");
        exportWordCopyCheckBox = new CheckBox("Also Save Word-Compatible (.docx) Copy");
        exportPdfCopyCheckBox = new CheckBox("Also Save PDF-Compatible (.html) Copy");
        keepProcessedCheckBox = new CheckBox("Keep Processed Audio File");
        enableTranscriptionCheckBox = new CheckBox("Enable Transcription");
        autoRemoveCompletedCheckBox = new CheckBox("Auto-Remove Completed Files");
        adaptiveScalingCheckBox = new CheckBox("Adaptive Concurrency Scaling");
        adaptiveScalingCheckBox.setSelected(true);
        removeSilenceCheckBox = new CheckBox("Remove Silence from Audio");
        normalizeCheckBox = new CheckBox("Normalize Audio");

        // Timestamp mode combobox
        timestampModeComboBox = new ComboBox<>(FXCollections.observableArrayList(
            TranscriptionConfig.TimestampMode.values()
        ));
        timestampModeComboBox.setPrefWidth(180);

        // Silence detection sliders
        silenceThresholdSlider = new Slider(-60, -30, AppConstants.SILENCE_THRESHOLD);
        silenceThresholdSlider.setShowTickMarks(true);
        silenceThresholdSlider.setShowTickLabels(true);
        silenceThresholdSlider.setMajorTickUnit(10);
        silenceThresholdSlider.setPrefWidth(150);
        silenceThresholdSlider.setDisable(true);

        silenceDurationSlider = new Slider(0.5, 3.0, AppConstants.SILENCE_DURATION);
        silenceDurationSlider.setShowTickMarks(true);
        silenceDurationSlider.setShowTickLabels(true);
        silenceDurationSlider.setMajorTickUnit(0.5);
        silenceDurationSlider.setPrefWidth(150);
        silenceDurationSlider.setDisable(true);

        // New components for enhanced control
        transcriptionFormatComboBox = new ComboBox<>(FXCollections.observableArrayList(
            TranscriptionConfig.OutputFormat.values()
        ));
        transcriptionFormatComboBox.setPrefWidth(120);
        
        srtMaxCharsSpinner = new Spinner<>(20, 1000, 80);
        srtMaxCharsSpinner.setPrefWidth(80);
        srtMaxCharsSpinner.setEditable(true);
        
        srtMaxLinesSpinner = new Spinner<>(1, 10, 3);
        srtMaxLinesSpinner.setPrefWidth(60);
        srtMaxLinesSpinner.setEditable(true);
        
        maxSegmentDurationSpinner = new Spinner<>(5.0, 60.0, 30.0, 5.0);
        maxSegmentDurationSpinner.setPrefWidth(80);
        maxSegmentDurationSpinner.setEditable(true);

        // Log level selector
        logLevelComboBox = new ComboBox<>(FXCollections.observableArrayList(
            "ERROR", "WARN", "INFO", "DEBUG", "TRACE"
        ));
        logLevelComboBox.setPrefWidth(100);

        // Set tooltips
        setTooltips();
    }

    private void setTooltips() {
        modelComboBox.setTooltip(new Tooltip("Model size: tiny (fastest) → large (most accurate)"));
        languageComboBox.setTooltip(new Tooltip("Select 'auto' for automatic language detection"));
        outputFormatComboBox.setTooltip(new Tooltip("Output audio format"));
        bitrateComboBox.setTooltip(new Tooltip("Higher bitrate = better quality"));
        volumeSlider.setTooltip(new Tooltip("Volume boost in dB"));
        noiseReductionCheckBox.setTooltip(new Tooltip("Apply noise reduction filters"));
        timestampModeComboBox.setTooltip(new Tooltip(
            "none: No timestamps\n" +
            "auto: Automatic segmentation\n" +
            "word: Word-level timestamps\n" +
            "sentence: Sentence-level timestamps\n" +
            "paragraph: Paragraph-level timestamps\n" +
            "fixed: Fixed duration segments"));
        confidenceCheckBox.setTooltip(new Tooltip("Add confidence metrics to transcription"));
        exportWordCopyCheckBox.setTooltip(new Tooltip("In addition to the .srt/.txt, save a genuine .docx copy Word can open and edit directly"));
        exportPdfCopyCheckBox.setTooltip(new Tooltip("In addition to the .srt/.txt, save an .html copy formatted for printing to PDF from a browser or Word (no PDF library is bundled, so this isn't a native .pdf file)"));
        keepProcessedCheckBox.setTooltip(new Tooltip("Keep processed audio files"));
        enableTranscriptionCheckBox.setTooltip(new Tooltip("Enable audio-to-text transcription"));
        autoRemoveCompletedCheckBox.setTooltip(new Tooltip("Remove files from queue after completion"));
        adaptiveScalingCheckBox.setTooltip(new Tooltip(
                "Automatically reduce concurrency under high memory/CPU pressure (recommended). "
                + "Turn off to run at a fixed concurrency level regardless of measured pressure — "
                + "useful for baseline performance measurements."));
        removeSilenceCheckBox.setTooltip(new Tooltip("Detect and remove silent sections"));
        normalizeCheckBox.setTooltip(new Tooltip("Normalize to broadcast standard (-16 LUFS)"));
        silenceThresholdSlider.setTooltip(new Tooltip("Loudness threshold for silence detection (dB)"));
        silenceDurationSlider.setTooltip(new Tooltip("Minimum duration to consider as silence (seconds)"));
        transcriptionFormatComboBox.setTooltip(new Tooltip("Transcription output format(s)"));
        srtMaxCharsSpinner.setTooltip(new Tooltip("Maximum characters per SRT line (20-1000)"));
        srtMaxLinesSpinner.setTooltip(new Tooltip("Maximum lines per subtitle (1-10)"));
        maxSegmentDurationSpinner.setTooltip(new Tooltip("Maximum segment duration in seconds (5-60)"));
        logLevelComboBox.setTooltip(new Tooltip("Application log verbosity (affects the log file, not the on-screen Terminal)"));
    }

    public VBox createUISection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(10));
        
        Label title = new Label("🎨 Interface");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox fontBox = new HBox(10);
        fontBox.setAlignment(Pos.CENTER_LEFT);

        Label fontLabel = new Label("Font Size:");
        fontLabel.setMinWidth(80);

        Label fontValueLabel = new Label(String.format("%.0fpx", fontSizeSlider.getValue()));
        fontValueLabel.setMinWidth(40);
        
        fontSizeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            fontValueLabel.setText(String.format("%.0fpx", newVal.doubleValue()));
            applyFontSize(newVal.doubleValue());
        });

        fontBox.getChildren().addAll(fontLabel, fontSizeSlider, fontValueLabel);

        HBox logLevelBox = new HBox(10);
        logLevelBox.setAlignment(Pos.CENTER_LEFT);
        Label logLevelLabel = new Label("Log Level:");
        logLevelLabel.setMinWidth(80);
        logLevelBox.getChildren().addAll(logLevelLabel, logLevelComboBox);

        logLevelComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) applyLogLevel(newVal);
        });

        section.getChildren().addAll(title, fontBox, logLevelBox);

        return section;
    }

    /**
     * Apply a log level, without taking a hard compile-time dependency on
     * any specific SLF4J backend.
     *
     * <p>FIX: the previous version imported {@code ch.qos.logback.classic.*}
     * directly. That only compiles/works if Logback is actually the SLF4J
     * backend on the classpath — for any other backend (e.g.
     * {@code slf4j-simple}, {@code log4j-slf4j-impl}), the referenced
     * classes don't exist at all, which is a classpath/build problem, not
     * something a try/catch around already-loaded classes can paper over.
     * This now reaches for Logback only via reflection (so the class is
     * merely looked up, not linked, if it's absent) and falls back to
     * {@code java.util.logging}'s root logger otherwise — which is always
     * present on any JVM and is respected by SLF4J's jul-to-slf4j bridge,
     * if one is installed, without requiring any specific backend.</p>
     */
    private void applyLogLevel(String levelName) {
        if (tryApplyLogLevelViaLogback(levelName)) {
            return;
        }
        applyLogLevelViaJUL(levelName);
    }

    /**
     * Attempt to set the level via Logback's classes, purely through
     * reflection so this class doesn't fail to compile/load when Logback
     * isn't present.
     *
     * @return true if Logback was found and the level was applied through it
     */
    private boolean tryApplyLogLevelViaLogback(String levelName) {
        try {
            Class<?> logbackLoggerClass = Class.forName("ch.qos.logback.classic.Logger");
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");

            Object rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            if (!logbackLoggerClass.isInstance(rootLogger)) {
                // SLF4J is bound to some other backend — Logback classes may
                // happen to be on the classpath (e.g. a transitive dep) even
                // though they're not the active binding.
                return false;
            }

            Object level = levelClass.getMethod("toLevel", String.class).invoke(null, levelName);
            logbackLoggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, level);
            LOGGER.info("Log level changed to {} (via Logback)", levelName);
            return true;
        } catch (ClassNotFoundException e) {
            // Logback isn't on the classpath at all — expected on other backends.
            return false;
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Could not apply log level '{}' via Logback: {}", levelName, e.getMessage());
            return false;
        }
    }

    /**
     * Fallback that always works: sets the level on {@code java.util.logging}'s
     * root logger. Respected automatically if a jul-to-slf4j (or similar)
     * bridge is installed; otherwise it's a harmless no-op for whatever the
     * actual SLF4J backend is, so the UI control never throws or silently
     * does nothing without at least trying something.
     */
    private void applyLogLevelViaJUL(String levelName) {
        try {
            java.util.logging.Level level = switch (levelName) {
                case "ERROR" -> java.util.logging.Level.SEVERE;
                case "WARN"  -> java.util.logging.Level.WARNING;
                case "INFO"  -> java.util.logging.Level.INFO;
                case "DEBUG" -> java.util.logging.Level.FINE;
                case "TRACE" -> java.util.logging.Level.FINEST;
                default      -> java.util.logging.Level.INFO;
            };
            java.util.logging.Logger.getLogger("").setLevel(level);
            LOGGER.info("Log level changed to {} (via java.util.logging)", levelName);
        } catch (Exception e) {
            LOGGER.warn("Could not apply log level '{}': {}", levelName, e.getMessage());
        }
    }

    private void applyFontSize(double size) {
        if (fontSizeChangeListener != null) {
            fontSizeChangeListener.accept(size);
        }
    }

    public void setFontSizeChangeListener(Consumer<Double> listener) {
        this.fontSizeChangeListener = listener;
    }

    public VBox createBatchSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(10));
        
        Label title = new Label("⚙️ Batch Processing");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox parallelBox = new HBox(10);
        parallelBox.setAlignment(Pos.CENTER_LEFT);

        Label parallelLabel = new Label("Max Parallel Files:");
        parallelLabel.setMinWidth(120);

        Label recommendLabel = new Label(String.format("(Recommended: %d)", 
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
        setStyled(recommendLabel, "-fx-text-fill: #666; -fx-font-size: 11px;");

        parallelBox.getChildren().addAll(parallelLabel, maxParallelSpinner, recommendLabel);
        section.getChildren().addAll(title, parallelBox, autoRemoveCompletedCheckBox, adaptiveScalingCheckBox);

        return section;
    }

    public VBox createWhisperSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(10));
        
        Label title = new Label("🎤 Transcription (Whisper)");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        grid.setPadding(new Insets(5, 0, 5, 0));

        // Row 0: Model and Language
        Label modelLabel = new Label("Model:");
        modelLabel.setMinWidth(60);
        grid.add(modelLabel, 0, 0);
        grid.add(modelComboBox, 1, 0);

        Label languageLabel = new Label("Language:");
        languageLabel.setMinWidth(70);
        grid.add(languageLabel, 2, 0);
        grid.add(languageComboBox, 3, 0);

        // Row 1: Timestamp mode and Output format
        Label timestampLabel = new Label("Timestamps:");
        timestampLabel.setMinWidth(80);
        grid.add(timestampLabel, 0, 1);
        grid.add(timestampModeComboBox, 1, 1);

        Label formatLabel = new Label("Output Format:");
        formatLabel.setMinWidth(100);
        grid.add(formatLabel, 2, 1);
        grid.add(transcriptionFormatComboBox, 3, 1);

        // Row 2-3: Checkboxes
        GridPane.setColumnSpan(enableTranscriptionCheckBox, 4);
        grid.add(enableTranscriptionCheckBox, 0, 2);
        
        GridPane.setColumnSpan(confidenceCheckBox, 4);
        grid.add(confidenceCheckBox, 0, 3);

        GridPane.setColumnSpan(exportWordCopyCheckBox, 4);
        grid.add(exportWordCopyCheckBox, 0, 4);

        GridPane.setColumnSpan(exportPdfCopyCheckBox, 4);
        grid.add(exportPdfCopyCheckBox, 0, 5);

        // Row 4: SRT settings
        Label srtLabel = new Label("SRT Settings:");
        srtLabel.setMinWidth(80);
        grid.add(srtLabel, 0, 6);
        
        HBox srtBox = new HBox(10);
        srtBox.setAlignment(Pos.CENTER_LEFT);
        
        srtBox.getChildren().addAll(
            new Label("Max chars:"), srtMaxCharsSpinner,
            new Label("Max lines:"), srtMaxLinesSpinner
        );
        grid.add(srtBox, 1, 6, 3, 1);

        // Row 5: Segment duration
        Label segmentLabel = new Label("Max Segment:");
        segmentLabel.setMinWidth(80);
        grid.add(segmentLabel, 0, 7);
        
        HBox segmentBox = new HBox(10);
        segmentBox.setAlignment(Pos.CENTER_LEFT);
        
        segmentBox.getChildren().addAll(
            maxSegmentDurationSpinner,
            new Label("seconds")
        );
        grid.add(segmentBox, 1, 7, 3, 1);

        section.getChildren().addAll(title, grid);
        return section;
    }

    public VBox createAudioSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(10));
        
        Label title = new Label("🎧 Audio Processing (FFmpeg)");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        grid.setPadding(new Insets(5, 0, 5, 0));

        // Row 0: Format and Bitrate
        Label formatLabel = new Label("Output Format:");
        formatLabel.setMinWidth(100);
        grid.add(formatLabel, 0, 0);
        grid.add(outputFormatComboBox, 1, 0);

        Label bitrateLabel = new Label("Bitrate:");
        bitrateLabel.setMinWidth(60);
        grid.add(bitrateLabel, 2, 0);
        grid.add(bitrateComboBox, 3, 0);

        // Row 1: Volume
        Label volumeLabel = new Label("Volume Boost:");
        volumeLabel.setMinWidth(100);
        grid.add(volumeLabel, 0, 1);

        Label volumeValueLabel = new Label(String.format("%.1f dB", volumeSlider.getValue()));
        volumeValueLabel.setMinWidth(50);
        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) ->
            volumeValueLabel.setText(String.format("%.1f dB", newVal.doubleValue()))
        );

        HBox volumeBox = new HBox(10, volumeSlider, volumeValueLabel);
        volumeBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(volumeBox, 1, 1, 3, 1);

        // Row 2-5: Checkboxes
        GridPane.setColumnSpan(noiseReductionCheckBox, 4);
        grid.add(noiseReductionCheckBox, 0, 2);
        
        GridPane.setColumnSpan(normalizeCheckBox, 4);
        grid.add(normalizeCheckBox, 0, 3);
        
        GridPane.setColumnSpan(removeSilenceCheckBox, 4);
        grid.add(removeSilenceCheckBox, 0, 4);
        
        GridPane.setColumnSpan(keepProcessedCheckBox, 4);
        grid.add(keepProcessedCheckBox, 0, 5);

        // Row 6: Silence Threshold
        Label silenceThreshLabel = new Label("Silence Threshold:");
        silenceThreshLabel.setMinWidth(120);
        grid.add(silenceThreshLabel, 0, 6);

        Label threshValueLabel = new Label(String.format("%.1f dB", silenceThresholdSlider.getValue()));
        threshValueLabel.setMinWidth(50);
        silenceThresholdSlider.valueProperty().addListener((obs, oldVal, newVal) ->
            threshValueLabel.setText(String.format("%.1f dB", newVal.doubleValue()))
        );

        HBox threshBox = new HBox(10, silenceThresholdSlider, threshValueLabel);
        threshBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(threshBox, 1, 6, 3, 1);

        // Row 7: Silence Duration
        Label silenceDurLabel = new Label("Min Silence Duration:");
        silenceDurLabel.setMinWidth(120);
        grid.add(silenceDurLabel, 0, 7);

        Label durValueLabel = new Label(String.format("%.1f sec", silenceDurationSlider.getValue()));
        durValueLabel.setMinWidth(50);
        silenceDurationSlider.valueProperty().addListener((obs, oldVal, newVal) ->
            durValueLabel.setText(String.format("%.1f sec", newVal.doubleValue()))
        );

        HBox durBox = new HBox(10, silenceDurationSlider, durValueLabel);
        durBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(durBox, 1, 7, 3, 1);
        // In ConfigurationPanel.java, add to audio section:
        CheckBox autoVolumeCheckBox = new CheckBox("Auto Volume Optimization");
        autoVolumeCheckBox.setTooltip(new Tooltip("Automatically analyze and optimize audio volume for best transcription accuracy"));
        autoVolumeCheckBox.setSelected(prefManager.isAutoVolumeOptimizationEnabled());
        autoVolumeCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            prefManager.setAutoVolumeOptimizationEnabled(newVal);
        });

        // Add to audio section layout
        // section.getChildren().add(autoVolumeCheckBox);
        Label autoVolLabel = new Label("Auto Volume On/Off:");
        grid.add(autoVolLabel, 1, 8, 3, 1);
        grid.add(autoVolumeCheckBox, 1, 9, 3, 1);

        section.getChildren().addAll(title, grid);
        return section;
    }

    public void loadPreferences() {
        if (prefManager == null) {
            LOGGER.error("PreferenceManager is null - cannot load preferences");
            return;
        }

        // Load font size
        fontSizeSlider.setValue(prefManager.getFontSize());

        // FIX: the log-level ComboBox was saved to preferences on change
        // (see the listener in buildXxxSection() below) but never read back
        // here — so the saved choice was silently discarded on every
        // restart, same bug shape as the noise-reduction key mismatch
        // documented further down in this method. setValue() also fires
        // the ComboBox's own valueProperty listener when the saved level
        // differs from the default, which re-applies it via
        // applyLogLevel() — so this both restores the UI state and
        // actually re-arms the logging backend at startup, not just the
        // ComboBox's visible selection.
        logLevelComboBox.setValue(prefManager.getString("log_level", "INFO"));

        // Load transcription preferences
        modelComboBox.setValue(prefManager.getString(PreferenceKeys.MODEL, AppConstants.DEFAULT_MODEL));
        languageComboBox.setValue(prefManager.getString(PreferenceKeys.LANGUAGE, AppConstants.DEFAULT_LANGUAGE));
        
        // Load timestamp mode
        String savedTimestampMode = prefManager.getString("timestamp_mode", "auto");
        timestampModeComboBox.setValue(TranscriptionConfig.TimestampMode.fromString(savedTimestampMode));

        // Load processing preferences
        outputFormatComboBox.setValue(prefManager.getString(PreferenceKeys.OUTPUT_FORMAT, AppConstants.DEFAULT_FORMAT));
        bitrateComboBox.setValue(prefManager.getString(PreferenceKeys.BITRATE, AppConstants.DEFAULT_BITRATE));
        volumeSlider.setValue(prefManager.getDouble(PreferenceKeys.VOLUME_BOOST, AppConstants.DEFAULT_VOLUME));

        // Load checkbox states
        // FIX: this used to read PreferenceKeys.NOISE_REDUCTION while
        // savePreferences() (below) writes PreferenceKeys.NOISE_REDUCTION_ENABLED —
        // two different keys, so the checkbox silently reset to its default
        // (false) on every restart regardless of what the user last chose.
        // Confirmed by diffing this method against savePreferences() in this
        // same class; NOISE_REDUCTION_ENABLED is the key actually written, so
        // that's the one that must be read back here.
        noiseReductionCheckBox.setSelected(prefManager.isNoiseReductionEnabled());
        confidenceCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.CONFIDENCE_ENABLED, false));
        exportWordCopyCheckBox.setSelected(prefManager.getBoolean("export_word_copy", false));
        exportPdfCopyCheckBox.setSelected(prefManager.getBoolean("export_pdf_copy", false));
        keepProcessedCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.KEEP_PROCESSED, false));
        enableTranscriptionCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.TRANSCRIPTION_ENABLED, true));
        autoRemoveCompletedCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, false));
        adaptiveScalingCheckBox.setSelected(prefManager.getBoolean("adaptive_scaling_enabled", true));
        removeSilenceCheckBox.setSelected(prefManager.getBoolean("remove_silence", false));
        normalizeCheckBox.setSelected(prefManager.isNormalizeAudioEnabled());

        // Load silence detection
        silenceThresholdSlider.setValue(prefManager.getDouble("silence_threshold", AppConstants.SILENCE_THRESHOLD));
        silenceDurationSlider.setValue(prefManager.getDouble("silence_duration", AppConstants.SILENCE_DURATION));
        
        // Update slider disabled states
        silenceThresholdSlider.setDisable(!removeSilenceCheckBox.isSelected());
        silenceDurationSlider.setDisable(!removeSilenceCheckBox.isSelected());

        // Load parallel processing
        maxParallelSpinner.getValueFactory().setValue(prefManager.getMaxParallelFiles());

        // Load new transcription settings
        String savedTranscriptionFormat = prefManager.getString("transcription_format", "BOTH");
        transcriptionFormatComboBox.setValue(TranscriptionConfig.OutputFormat.fromString(savedTranscriptionFormat));
        
        srtMaxCharsSpinner.getValueFactory().setValue(prefManager.getInt("srt_max_chars", 80));
        srtMaxLinesSpinner.getValueFactory().setValue(prefManager.getInt("srt_max_lines", 3));
        maxSegmentDurationSpinner.getValueFactory().setValue(prefManager.getDouble("max_segment_duration", 30.0));

        LOGGER.debug("Preferences loaded successfully");
    }

    // =============================
    // Config Builders
    // =============================

    public ProcessingConfig getProcessingConfig() {
        return new ProcessingConfig.Builder()
            .outputDirectory(prefManager.getOutputDirectory())
            .outputFormat(outputFormatComboBox.getValue())
            .bitrate(bitrateComboBox.getValue())
            .volumeBoost(volumeSlider.getValue())
            .silenceThreshold(silenceThresholdSlider.getValue())
            .silenceDuration(silenceDurationSlider.getValue())
            .noiseReduction(noiseReductionCheckBox.isSelected())
            .removeSilence(removeSilenceCheckBox.isSelected())
            .normalize(normalizeCheckBox.isSelected())
            .keepProcessedAudio(keepProcessedCheckBox.isSelected())
            .transcriptionEnabled(enableTranscriptionCheckBox.isSelected())
            .build();
    }

    public TranscriptionConfig getTranscriptionConfig() {
        // Determine if timestamps are enabled based on the selected mode
        boolean timestampsEnabled = !timestampModeComboBox.getValue().equals(TranscriptionConfig.TimestampMode.NONE);
        
        return TranscriptionConfig.builder()
            .model(modelComboBox.getValue())
            .language(languageComboBox.getValue())
            .timestampsEnabled(timestampsEnabled)
            .confidenceEnabled(confidenceCheckBox.isSelected())
            .outputFormat(transcriptionFormatComboBox.getValue())
            .volumeBoost((float) volumeSlider.getValue())
            .silenceThreshold((float) silenceThresholdSlider.getValue())
            .silenceDuration((float) silenceDurationSlider.getValue())
            .noiseReduction(noiseReductionCheckBox.isSelected())
            .srtMaxChars(srtMaxCharsSpinner.getValue())
            .srtMaxLines(srtMaxLinesSpinner.getValue())
            .diarizeEnabled(false)
            .hfToken(null)
            .maxSegmentDuration(maxSegmentDurationSpinner.getValue().floatValue())
            .enabled(enableTranscriptionCheckBox.isSelected())
            .skipSegmentation(false)
            .build();
    }

    // =============================
    // Utility Methods
    // =============================

    public int getMaxParallelFiles() {
        return maxParallelSpinner.getValue();
    }

    public boolean isTranscriptionEnabled() {
        return enableTranscriptionCheckBox.isSelected();
    }

    public boolean isExportWordCopyEnabled() {
        return exportWordCopyCheckBox.isSelected();
    }

    public boolean isExportPdfCopyEnabled() {
        return exportPdfCopyCheckBox.isSelected();
    }

    public boolean isAutoRemoveCompleted() {
        return autoRemoveCompletedCheckBox.isSelected();
    }

    public boolean isAdaptiveScalingEnabled() {
        return adaptiveScalingCheckBox.isSelected();
    }

    public void setEnabled(boolean enabled) {
        // Set all components enabled/disabled state
        modelComboBox.setDisable(!enabled);
        languageComboBox.setDisable(!enabled);
        outputFormatComboBox.setDisable(!enabled);
        bitrateComboBox.setDisable(!enabled);
        volumeSlider.setDisable(!enabled);
        fontSizeSlider.setDisable(!enabled);
        maxParallelSpinner.setDisable(!enabled);
        noiseReductionCheckBox.setDisable(!enabled);
        timestampModeComboBox.setDisable(!enabled);
        confidenceCheckBox.setDisable(!enabled);
        exportWordCopyCheckBox.setDisable(!enabled);
        exportPdfCopyCheckBox.setDisable(!enabled);
        keepProcessedCheckBox.setDisable(!enabled);
        enableTranscriptionCheckBox.setDisable(!enabled);
        autoRemoveCompletedCheckBox.setDisable(!enabled);
        removeSilenceCheckBox.setDisable(!enabled);
        normalizeCheckBox.setDisable(!enabled);
        transcriptionFormatComboBox.setDisable(!enabled);
        srtMaxCharsSpinner.setDisable(!enabled);
        srtMaxLinesSpinner.setDisable(!enabled);
        maxSegmentDurationSpinner.setDisable(!enabled);

        // Silence controls only enabled when remove silence is checked and panel is enabled
        boolean silenceEnabled = enabled && removeSilenceCheckBox.isSelected();
        silenceThresholdSlider.setDisable(!silenceEnabled);
        silenceDurationSlider.setDisable(!silenceEnabled);
    }

    public void refreshAllComponents() {
        loadPreferences();
    }
}