/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.constants.AppConstants;
import audiomanager.constants.PreferenceKeys;
import audiomanager.core.GpuConfig;
import audiomanager.core.LicenseManager;
import audiomanager.model.ProcessingConfig;
import audiomanager.model.TranscriptionConfig;
import audiomanager.util.PreferenceManager;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Configuration panel for audio processing and transcription settings.
 * 
 * <p>Theme and font settings now use AppState as the single source of truth,
 * eliminating the split state where UI settings came from PreferenceManager
 * while UI state came from AppState.</p>
 * 
 * <p>Includes GPU acceleration configuration in the Performance section.</p>
 * <p>Includes Translation configuration for post-transcription translation.</p>
 * <p>Includes quick access to Setup Wizard for dependency management.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 */
public class ConfigurationPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationPanel.class);

    private final ScrollPane root;
    private final PreferenceManager prefManager;
    private final AppState appState = AppState.getInstance();
    private final GpuConfig gpuConfig = GpuConfig.getInstance();

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
    public CheckBox adaptiveScalingCheckBox;
    private CheckBox skipSegmentationCheckBox;
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

    // ========== ID3 Tagging Checkbox ==========
    private CheckBox id3TaggingCheckBox;

    // ===== License Section Components =====
    private Label licenseLabel;
    private VBox licenseBox;

    // ===== GPU Section Components =====
    private CheckBox enableGpuCheckBox;
    private Label gpuStatusLabel;
    private Label gpuInfoLabel;

    // ===== Translation Section Components =====
    private TitledPane translationSection;
    private CheckBox translationEnabledCheckBox;
    private ComboBox<String> translationLanguageComboBox;
    private TextField translationEndpointField;
    private PasswordField translationApiKeyField;
    private Label translationStatusLabel;

    // ===== NEW: Setup Wizard Quick Access =====
    private Button setupWizardButton;
    private Label dependencyStatusLabel;

    public ConfigurationPanel(PreferenceManager prefManager) {
        this.prefManager = prefManager;
        
        // Initialize components BEFORE creating the root pane
        initializeComponents();
        setupListeners();
        
        this.root = createRootPane();
        loadPreferences();
        bindToAppState();
        updateGpuStatus();
        updateDependencyStatus();
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
            createDependencySection(),  // NEW: Setup Wizard section
            createBatchSection(),
            createWhisperSection(),
            createTranslationSection(),
            createAudioSection(),
            createPerformanceSection()
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

        // Translation enable/disable listener
        if (translationEnabledCheckBox != null) {
            translationEnabledCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                boolean enabled = newVal;
                translationLanguageComboBox.setDisable(!enabled);
                translationEndpointField.setDisable(!enabled);
                translationApiKeyField.setDisable(!enabled);
                updateTranslationStatus();
            });
        }
    }

    // ========================================================================
    //  AppState Binding
    // ========================================================================

    private void bindToAppState() {
        // Font size - bidirectional binding with AppState
        fontSizeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            appState.setFontSize(newVal.doubleValue());
            if (fontSizeChangeListener != null) {
                fontSizeChangeListener.accept(newVal.doubleValue());
            }
        });

        appState.fontSizeProperty().addListener((obs, oldVal, newVal) -> {
            if (fontSizeSlider != null) {
                fontSizeSlider.setValue(newVal.doubleValue());
            }
        });

        appState.darkModeProperty().addListener((obs, oldVal, newVal) -> {
            LOGGER.debug("Dark mode state changed to: {}", newVal);
        });

        LicenseManager license = LicenseManager.getInstance();
        licenseLabel.setText(license.getLicenseStatusText());
        updateLicenseUI(license);
    }

    private void updateLicenseUI(LicenseManager license) {
        licenseLabel.setText(license.getLicenseStatusText());
        setStyled(licenseLabel,
            license.isPro()
                ? "-fx-font-weight: bold; -fx-text-fill: #2e7d32; -fx-font-size: 12px; -fx-padding: 10 0 0 0;"
                : "-fx-font-size: 12px; -fx-text-fill: #ed6c02; -fx-padding: 10 0 0 0;");
    }

    // ========================================================================
    //  Save Preferences
    // ========================================================================

    public void savePreferences() {
        try {
            // Save Transcription
            prefManager.putString(PreferenceKeys.MODEL, modelComboBox.getValue());
            prefManager.putString(PreferenceKeys.LANGUAGE, languageComboBox.getValue());
            
            if (timestampModeComboBox.getValue() != null) {
                prefManager.putString("timestamp_mode", timestampModeComboBox.getValue().getValue());
            }

            // Save Processing
            prefManager.putString(PreferenceKeys.OUTPUT_FORMAT, outputFormatComboBox.getValue());
            prefManager.putString(PreferenceKeys.BITRATE, bitrateComboBox.getValue());
            prefManager.putDouble(PreferenceKeys.VOLUME_BOOST, volumeSlider.getValue());
            
            // Save CheckBoxes
            prefManager.setNoiseReductionEnabled(noiseReductionCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.CONFIDENCE_ENABLED, confidenceCheckBox.isSelected());
            prefManager.putBoolean("export_word_copy", exportWordCopyCheckBox.isSelected());
            prefManager.putBoolean("export_pdf_copy", exportPdfCopyCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.KEEP_PROCESSED, keepProcessedCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.TRANSCRIPTION_ENABLED, enableTranscriptionCheckBox.isSelected());
            prefManager.putBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, autoRemoveCompletedCheckBox.isSelected());
            prefManager.putBoolean("adaptive_scaling_enabled", adaptiveScalingCheckBox.isSelected());
            prefManager.putBoolean("skip_segmentation_baseline_mode", skipSegmentationCheckBox.isSelected());
            prefManager.putBoolean("remove_silence", removeSilenceCheckBox.isSelected());
            prefManager.setNormalizeAudioEnabled(normalizeCheckBox.isSelected());
            
            // ID3 Tagging Save
            prefManager.setID3TaggingEnabled(id3TaggingCheckBox.isSelected());
            
            // Save Silence Detection
            prefManager.putDouble("silence_threshold", silenceThresholdSlider.getValue());
            prefManager.putDouble("silence_duration", silenceDurationSlider.getValue());
            
            // Save Max Parallel Files
            prefManager.putInt(PreferenceKeys.MAX_PARALLEL, maxParallelSpinner.getValue());
            
            // Save Font Size - now using AppState
            appState.setFontSize(fontSizeSlider.getValue());
            
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

            // GPU settings
            if (enableGpuCheckBox != null) {
                prefManager.putBoolean("gpu.enabled", enableGpuCheckBox.isSelected());
            }

            // Translation settings
            prefManager.setTranslationEnabled(translationEnabledCheckBox.isSelected());
            prefManager.setTranslationTargetLanguage(translationLanguageComboBox.getValue());
            prefManager.setTranslationEndpoint(translationEndpointField.getText());
            prefManager.setTranslationApiKey(translationApiKeyField.getText());

            prefManager.flush();
            LOGGER.debug("Configuration preferences saved successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to save preferences: {}", e.getMessage(), e);
        }
    }

    public void refreshUI() {
        loadPreferences();
    }
    
    // ========================================================================
    //  Component Initialization
    // ========================================================================

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

        // Font size slider - now bound to AppState
        double initialFontSize = appState.getFontSize();
        if (initialFontSize <= 0) {
            initialFontSize = AppConstants.DEFAULT_FONT_SIZE;
        }
        fontSizeSlider = new Slider(8, 20, initialFontSize);
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
        skipSegmentationCheckBox = new CheckBox("Baseline Mode (Skip Segmentation & Per-Segment Retry)");
        skipSegmentationCheckBox.setSelected(false);

        // ID3 Tagging Checkbox
        id3TaggingCheckBox = new CheckBox("Generate ID3 Tags (sidecar .meta file)");
        id3TaggingCheckBox.setTooltip(new Tooltip("Create a .meta file with title, artist, and other metadata"));
        id3TaggingCheckBox.setSelected(prefManager.isID3TaggingEnabled());
        id3TaggingCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            prefManager.setID3TaggingEnabled(newVal);
            prefManager.flush();
        });

        // Adaptive Scaling and Baseline Mode mutual exclusivity
        adaptiveScalingCheckBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            if (isSelected) skipSegmentationCheckBox.setSelected(false);
        });
        skipSegmentationCheckBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            if (isSelected) adaptiveScalingCheckBox.setSelected(false);
        });

        // Persist adaptive scaling and baseline mode immediately
        adaptiveScalingCheckBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            prefManager.putBoolean("adaptive_scaling_enabled", isSelected);
            prefManager.flush();
        });
        skipSegmentationCheckBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            prefManager.putBoolean("skip_segmentation_baseline_mode", isSelected);
            prefManager.flush();
        });

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

        // ===== GPU Components =====
        enableGpuCheckBox = new CheckBox("Enable GPU Acceleration (CUDA)");
        enableGpuCheckBox.setTooltip(new Tooltip(
            "Use NVIDIA GPU for faster transcription.\n" +
            "Requires CUDA-compatible GPU and NVIDIA drivers.\n" +
            "If enabled, transcription will be 2-3x faster on compatible hardware."
        ));
        enableGpuCheckBox.setSelected(prefManager.getBoolean("gpu.enabled", true));

        enableGpuCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            prefManager.putBoolean("gpu.enabled", newVal);
            prefManager.flush();
            updateGpuStatus();
        });

        // GPU Status Label
        gpuStatusLabel = new Label();
        gpuStatusLabel.setStyle("-fx-font-size: 12px; -fx-padding: 4 0 4 0;");

        // GPU Info Label
        gpuInfoLabel = new Label();
        gpuInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #718096; -fx-padding: 0 0 4 0;");

        // ===== Translation Components =====
        initializeTranslationComponents();

        // ===== NEW: Setup Wizard Components =====
        initializeDependencyComponents();

        // Set tooltips
        setTooltips();
    }

    // ===== NEW: Dependency/Setup Wizard Components =====

    /**
     * Initializes the dependency check and Setup Wizard components.
     */
    private void initializeDependencyComponents() {
        Label title = new Label("🔧 System Dependencies");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        title.getStyleClass().add("panel-heading");

        dependencyStatusLabel = new Label("Click 'Check Dependencies' to verify your setup");
        dependencyStatusLabel.setStyle("-fx-font-size: 12px; -fx-padding: 4 0 4 0;");

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        Button checkDepsButton = new Button("🔄 Check Dependencies");
        checkDepsButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-padding: 6 16; -fx-background-radius: 4;");
        checkDepsButton.setOnAction(e -> {
            // This will be handled by MainWindow via the callback
            if (onCheckDependencies != null) {
                onCheckDependencies.run();
            }
        });

        setupWizardButton = new Button("🚀 Run Setup Wizard");
        setupWizardButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 6 16; -fx-font-weight: bold; -fx-background-radius: 4;");
        setupWizardButton.setTooltip(new Tooltip(
            "Launch the Setup Wizard to check Python version, WhisperX installation,\n" +
            "TorchCodec, and other dependencies. Recommended for first-time users."
        ));
        setupWizardButton.setOnAction(e -> {
            if (onRunSetupWizard != null) {
                onRunSetupWizard.run();
            }
        });

        Label quickTip = new Label("💡 Tip: Press Ctrl+Shift+S to launch Setup Wizard anytime");
        quickTip.setStyle("-fx-font-size: 10px; -fx-text-fill: #666; -fx-padding: 4 0 0 0;");

        buttonBox.getChildren().addAll(checkDepsButton, setupWizardButton);

        VBox section = new VBox(8);
        section.setPadding(new Insets(10));
        section.getChildren().addAll(title, dependencyStatusLabel, buttonBox, quickTip);
    }

    /**
     * Updates the dependency status label.
     */
    private void updateDependencyStatus() {
        if (dependencyStatusLabel == null) return;
        // This will be updated by MainWindow when dependencies are checked
        // For now, show a default message
        dependencyStatusLabel.setText("🔍 Click 'Check Dependencies' to verify your system setup");
        dependencyStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666; -fx-padding: 4 0 4 0;");
    }

    // ===== Callbacks for dependency actions =====
    private Runnable onCheckDependencies;
    private Runnable onRunSetupWizard;

    /**
     * Sets the callback for checking dependencies.
     *
     * @param callback the callback to invoke
     */
    public void setOnCheckDependencies(Runnable callback) {
        this.onCheckDependencies = callback;
    }

    /**
     * Sets the callback for running the Setup Wizard.
     *
     * @param callback the callback to invoke
     */
    public void setOnRunSetupWizard(Runnable callback) {
        this.onRunSetupWizard = callback;
    }

    // ===== Translation Components =====

    /**
     * Initializes the translation UI components.
     */
    private void initializeTranslationComponents() {
        translationEnabledCheckBox = new CheckBox("Enable Translation");
        translationEnabledCheckBox.setTooltip(new Tooltip(
            "Translate transcripts to another language after transcription.\n" +
            "Requires a LibreTranslate-compatible endpoint.\n\n" +
            "Note: Translation is a post-processing step. The original transcript\n" +
            "is preserved if translation fails."
        ));
        translationEnabledCheckBox.setSelected(prefManager.isTranslationEnabled());

        // Target language combobox
        translationLanguageComboBox = new ComboBox<>(FXCollections.observableArrayList(
            "es", "fr", "de", "it", "pt", "ru", "zh", "ja", "ar", "hi",
            "nl", "pl", "tr", "vi", "th", "ko", "sv", "no", "da", "fi",
            "el", "cs", "ro", "hu", "ta", "uk", "he", "id", "ms", "ca"
        ));
        translationLanguageComboBox.setPrefWidth(100);
        translationLanguageComboBox.setValue(prefManager.getTranslationTargetLanguage());
        translationLanguageComboBox.setTooltip(new Tooltip(
            "Target language for translation (ISO 639-1 code).\n" +
            "Common: es=Spanish, fr=French, de=German, it=Italian"
        ));
        translationLanguageComboBox.setDisable(!prefManager.isTranslationEnabled());

        // Endpoint URL
        translationEndpointField = new TextField(prefManager.getTranslationEndpoint());
        translationEndpointField.setPromptText("https://libretranslate.com/translate");
        translationEndpointField.setPrefWidth(350);
        translationEndpointField.setTooltip(new Tooltip(
            "LibreTranslate-compatible API endpoint.\n" +
            "Default: https://libretranslate.com/translate\n" +
            "Self-hosted: http://localhost:5000/translate"
        ));
        translationEndpointField.setDisable(!prefManager.isTranslationEnabled());

        // API Key (optional)
        translationApiKeyField = new PasswordField();
        translationApiKeyField.setPromptText("API Key (optional)");
        translationApiKeyField.setPrefWidth(200);
        translationApiKeyField.setTooltip(new Tooltip(
            "Optional API key for the translation service.\n" +
            "Not required for public LibreTranslate instances.\n" +
            "Required for some self-hosted or commercial services."
        ));
        String apiKey = prefManager.getTranslationApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            translationApiKeyField.setText(apiKey);
        }
        translationApiKeyField.setDisable(!prefManager.isTranslationEnabled());

        // Translation status label
        translationStatusLabel = new Label();
        translationStatusLabel.setStyle("-fx-font-size: 11px; -fx-padding: 4 0 4 0;");
        updateTranslationStatus();
    }

    /**
     * Updates the translation status label based on current configuration.
     */
    private void updateTranslationStatus() {
        if (translationStatusLabel == null) return;

        boolean enabled = translationEnabledCheckBox.isSelected();
        String endpoint = translationEndpointField.getText();
        String targetLang = translationLanguageComboBox.getValue();

        if (enabled) {
            if (endpoint == null || endpoint.isBlank() || endpoint.equals("none")) {
                translationStatusLabel.setText("⚠️ Translation enabled but no endpoint configured");
                translationStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #ed6c02; -fx-padding: 4 0 4 0;");
            } else {
                translationStatusLabel.setText("✅ Translation enabled: " + targetLang + " via " + endpoint);
                translationStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #2e7d32; -fx-padding: 4 0 4 0;");
            }
        } else {
            translationStatusLabel.setText("ℹ️ Translation disabled");
            translationStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #718096; -fx-padding: 4 0 4 0;");
        }
    }

    // ========================================================================
    //  Tooltips
    // ========================================================================

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
        exportPdfCopyCheckBox.setTooltip(new Tooltip("In addition to the .srt/.txt, save an .html copy formatted for printing to PDF from a browser or Word"));
        keepProcessedCheckBox.setTooltip(new Tooltip("Keep processed audio files"));
        enableTranscriptionCheckBox.setTooltip(new Tooltip("Enable audio-to-text transcription"));
        autoRemoveCompletedCheckBox.setTooltip(new Tooltip("Remove files from queue after completion"));
        adaptiveScalingCheckBox.setTooltip(new Tooltip(
                "Automatically reduce concurrency under high memory/CPU pressure (recommended). " +
                "Turn off to run at a fixed concurrency level regardless of measured pressure."));
        skipSegmentationCheckBox.setTooltip(new Tooltip(
                "Off (recommended): long files are split into segments that are transcribed and " +
                "retried independently.\nOn: transcribes each file as a single unbroken unit."));
        removeSilenceCheckBox.setTooltip(new Tooltip("Detect and remove silent sections"));
        normalizeCheckBox.setTooltip(new Tooltip("Normalize to broadcast standard (-16 LUFS)"));
        silenceThresholdSlider.setTooltip(new Tooltip("Loudness threshold for silence detection (dB)"));
        silenceDurationSlider.setTooltip(new Tooltip("Minimum duration to consider as silence (seconds)"));
        transcriptionFormatComboBox.setTooltip(new Tooltip("Transcription output format(s)"));
        srtMaxCharsSpinner.setTooltip(new Tooltip("Maximum characters per SRT line (20-1000)"));
        srtMaxLinesSpinner.setTooltip(new Tooltip("Maximum lines per subtitle (1-10)"));
        maxSegmentDurationSpinner.setTooltip(new Tooltip("Maximum segment duration in seconds (5-60)"));
        logLevelComboBox.setTooltip(new Tooltip("Application log verbosity (affects the log file, not the on-screen Terminal)"));
        id3TaggingCheckBox.setTooltip(new Tooltip("Create a sidecar .meta file with title, artist, and other metadata for each processed audio file"));
        enableGpuCheckBox.setTooltip(new Tooltip(
            "Use NVIDIA GPU for faster transcription.\n" +
            "Requires CUDA-compatible GPU and NVIDIA drivers.\n" +
            "If enabled, transcription will be 2-3x faster on compatible hardware."
        ));
        
        // ===== NEW: Setup Wizard tooltip already set in initialization =====
    }

    // ========================================================================
    //  GPU Status Update
    // ========================================================================

    private void updateGpuStatus() {
        if (gpuStatusLabel == null) return;

        gpuConfig.detectGpu();
        boolean gpuAvailable = gpuConfig.isGpuAvailable();
        boolean gpuEnabled = enableGpuCheckBox != null && enableGpuCheckBox.isSelected();

        if (gpuAvailable) {
            gpuStatusLabel.setText(getGpuStatusMessage());
            gpuStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #2e7d32; -fx-padding: 4 0 4 0;");
            gpuStatusLabel.setTooltip(null);

            String infoText = String.format(
                "Memory: %d MB | Compute Capability: %s | Cores: %d",
                gpuConfig.getGpuMemoryMB(),
                gpuConfig.getComputeCapability(),
                gpuConfig.getCudaCores()
            );
            gpuInfoLabel.setText(infoText + (gpuEnabled ? "" : " (currently disabled)"));
            gpuInfoLabel.setVisible(true);
            gpuInfoLabel.setManaged(true);
            gpuInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + 
                (gpuEnabled ? "#718096" : "#ed6c02") + "; -fx-padding: 0 0 4 0;");
        } else {
            gpuStatusLabel.setText(getGpuStatusMessage());
            gpuStatusLabel.setTooltip(new Tooltip(getGpuStatusTooltip()));
            gpuStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #b0b0b0; -fx-padding: 4 0 4 0;");
            gpuInfoLabel.setVisible(false);
            gpuInfoLabel.setManaged(false);
        }
    }

    // ========================================================================
    //  UI Sections
    // ========================================================================

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
            appState.setFontSize(newVal.doubleValue());
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

        // ========== LICENSE SECTION ==========
        LicenseManager license = LicenseManager.getInstance();

        this.licenseLabel = new Label(license.getLicenseStatusText());
        setStyled(licenseLabel,
            license.isPro()
                ? "-fx-font-weight: bold; -fx-text-fill: #2e7d32; -fx-font-size: 12px; -fx-padding: 10 0 0 0;"
                : "-fx-font-size: 12px; -fx-text-fill: #ed6c02; -fx-padding: 10 0 0 0;");

        this.licenseBox = new VBox(5);
        licenseBox.getChildren().add(licenseLabel);

        if (!license.isPro()) {
            Button upgradeButton = new Button("💎 Upgrade to Pro");
            upgradeButton.setStyle("-fx-background-color: #f9a825; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 20;");
            upgradeButton.setOnAction(e -> showUpgradeDialog());
            licenseBox.getChildren().add(upgradeButton);

            Label upgradeInfo = new Label("Free: Single file, 100MB limit\nPro: Batch processing, 750MB limit");
            setStyled(upgradeInfo, "-fx-font-size: 11px; -fx-text-fill: #666;");
            licenseBox.getChildren().add(upgradeInfo);
        }

        // Version info
        Label versionInfo = new Label("Aburime Sound Manager v" + AppConstants.APP_VERSION + 
            " - " + (license.isPro() ? "Pro" : "Free"));
        setStyled(versionInfo, "-fx-font-size: 10px; -fx-text-fill: #95a5a6; -fx-padding: 10 0 0 0;");

        section.getChildren().addAll(
            title, 
            fontBox, 
            logLevelBox,
            new Separator(),
            licenseBox,
            versionInfo
        );

        return section;
    }

    // ===== NEW: Dependency Section =====

    public VBox createDependencySection() {
        VBox section = new VBox(8);
        section.setPadding(new Insets(10));
        
        Label title = new Label("🔧 System Dependencies");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");
        title.getStyleClass().add("panel-heading");

        dependencyStatusLabel = new Label("Click 'Check Dependencies' to verify your system setup");
        dependencyStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666; -fx-padding: 4 0 4 0;");

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        Button checkDepsButton = new Button("🔄 Check Dependencies");
        checkDepsButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-padding: 6 16; -fx-background-radius: 4;");
        checkDepsButton.setOnAction(e -> {
            if (onCheckDependencies != null) {
                onCheckDependencies.run();
            }
        });

        setupWizardButton = new Button("🚀 Run Setup Wizard");
        setupWizardButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 6 16; -fx-font-weight: bold; -fx-background-radius: 4;");
        setupWizardButton.setTooltip(new Tooltip(
            "Launch the Setup Wizard to check Python version, WhisperX installation,\n" +
            "TorchCodec, and other dependencies. Recommended for first-time users."
        ));
        setupWizardButton.setOnAction(e -> {
            if (onRunSetupWizard != null) {
                onRunSetupWizard.run();
            }
        });

        Label quickTip = new Label("💡 Tip: Press Ctrl+Shift+S to launch Setup Wizard anytime");
        quickTip.setStyle("-fx-font-size: 10px; -fx-text-fill: #666; -fx-padding: 4 0 0 0;");

        buttonBox.getChildren().addAll(checkDepsButton, setupWizardButton);

        section.getChildren().addAll(title, dependencyStatusLabel, buttonBox, quickTip);
        return section;
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
        section.getChildren().addAll(title, parallelBox, autoRemoveCompletedCheckBox,
                adaptiveScalingCheckBox, skipSegmentationCheckBox);

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

    // ===== Translation Section =====

    public VBox createTranslationSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(10));
        
        Label title = new Label("🌐 Translation");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        grid.setPadding(new Insets(5, 0, 5, 0));

        // Row 0: Enable Translation (spanning all columns)
        GridPane.setColumnSpan(translationEnabledCheckBox, 4);
        grid.add(translationEnabledCheckBox, 0, 0);

        // Row 1: Target Language
        Label targetLabel = new Label("Target Language:");
        targetLabel.setMinWidth(120);
        grid.add(targetLabel, 0, 1);
        grid.add(translationLanguageComboBox, 1, 1);

        // Row 2: Endpoint URL
        Label endpointLabel = new Label("Endpoint URL:");
        endpointLabel.setMinWidth(120);
        grid.add(endpointLabel, 0, 2);
        grid.add(translationEndpointField, 1, 2, 3, 1);

        // Row 3: API Key
        Label apiKeyLabel = new Label("API Key:");
        apiKeyLabel.setMinWidth(120);
        grid.add(apiKeyLabel, 0, 3);
        grid.add(translationApiKeyField, 1, 3);

        // Row 4: Status (spanning all columns)
        GridPane.setColumnSpan(translationStatusLabel, 4);
        grid.add(translationStatusLabel, 0, 4);

        // Row 5: Info note
        Label infoNote = new Label(
            "💡 Translation uses LibreTranslate-compatible endpoints.\n" +
            "Public instance: https://libretranslate.com/translate (rate-limited)\n" +
            "Self-hosted: https://github.com/LibreTranslate/LibreTranslate"
        );
        infoNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #666; -fx-padding: 8 0 0 0;");
        infoNote.setWrapText(true);
        GridPane.setColumnSpan(infoNote, 4);
        grid.add(infoNote, 0, 5);

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

        // Row 6: ID3 Tagging Checkbox
        GridPane.setColumnSpan(id3TaggingCheckBox, 4);
        grid.add(id3TaggingCheckBox, 0, 6);

        // Row 7: Silence Threshold
        Label silenceThreshLabel = new Label("Silence Threshold:");
        silenceThreshLabel.setMinWidth(120);
        grid.add(silenceThreshLabel, 0, 7);

        Label threshValueLabel = new Label(String.format("%.1f dB", silenceThresholdSlider.getValue()));
        threshValueLabel.setMinWidth(50);
        silenceThresholdSlider.valueProperty().addListener((obs, oldVal, newVal) ->
            threshValueLabel.setText(String.format("%.1f dB", newVal.doubleValue()))
        );

        HBox threshBox = new HBox(10, silenceThresholdSlider, threshValueLabel);
        threshBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(threshBox, 1, 7, 3, 1);

        // Row 8: Silence Duration
        Label silenceDurLabel = new Label("Min Silence Duration:");
        silenceDurLabel.setMinWidth(120);
        grid.add(silenceDurLabel, 0, 8);

        Label durValueLabel = new Label(String.format("%.1f sec", silenceDurationSlider.getValue()));
        durValueLabel.setMinWidth(50);
        silenceDurationSlider.valueProperty().addListener((obs, oldVal, newVal) ->
            durValueLabel.setText(String.format("%.1f sec", newVal.doubleValue()))
        );

        HBox durBox = new HBox(10, silenceDurationSlider, durValueLabel);
        durBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(durBox, 1, 8, 3, 1);

        // Row 9: Auto Volume Optimization
        CheckBox autoVolumeCheckBox = new CheckBox("Auto Volume Optimization");
        autoVolumeCheckBox.setTooltip(new Tooltip("Automatically analyze and optimize audio volume for best transcription accuracy"));
        autoVolumeCheckBox.setSelected(prefManager.isAutoVolumeOptimizationEnabled());
        autoVolumeCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            prefManager.setAutoVolumeOptimizationEnabled(newVal);
        });

        grid.add(autoVolumeCheckBox, 1, 9, 3, 1);

        section.getChildren().addAll(title, grid);
        return section;
    }

    // ========================================================================
    //  Performance Section (GPU Settings)
    // ========================================================================

    public VBox createPerformanceSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(10));
        
        Label title = new Label("⚡ Performance");
        setStyled(title, "-fx-font-weight: bold; -fx-font-size: 14px;");

        // GPU Status
        gpuStatusLabel = new Label();
        gpuStatusLabel.setStyle("-fx-font-size: 12px; -fx-padding: 4 0 4 0;");
        updateGpuStatus();

        // GPU Info Label
        gpuInfoLabel = new Label();
        gpuInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #718096; -fx-padding: 0 0 4 0;");
        gpuInfoLabel.setVisible(false);
        gpuInfoLabel.setManaged(false);

        // GPU Enable Checkbox
        enableGpuCheckBox = new CheckBox("Enable GPU Acceleration (CUDA)");
        enableGpuCheckBox.setTooltip(new Tooltip(
            "Use NVIDIA GPU for faster transcription.\n" +
            "Requires CUDA-compatible GPU and NVIDIA drivers.\n" +
            "If enabled, transcription will be 2-3x faster on compatible hardware.\n\n" +
            "Current status: " + (gpuConfig.isGpuAvailable() ? 
                "GPU available ✓" : "No compatible GPU detected - CPU mode will be used")
        ));
        enableGpuCheckBox.setSelected(prefManager.getBoolean("gpu.enabled", true));

        enableGpuCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            prefManager.putBoolean("gpu.enabled", newVal);
            prefManager.flush();
            updateGpuStatus();
        });

        // Refresh GPU button
        Button refreshGpuButton = new Button("🔄 Refresh GPU Detection");
        refreshGpuButton.setTooltip(new Tooltip("Re-detect GPU (useful after driver updates or plugging in a GPU)"));
        refreshGpuButton.setOnAction(e -> {
            gpuConfig.resetDetection();
            gpuConfig.detectGpu();
            updateGpuStatus();
            LOGGER.info("GPU detection refreshed: {}", gpuConfig.getGpuSummary());
        });
        setStyled(refreshGpuButton, "-fx-background-radius: 4; -fx-padding: 4 12;");

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        buttonBox.getChildren().add(refreshGpuButton);

        section.getChildren().addAll(
            title,
            gpuStatusLabel,
            gpuInfoLabel,
            enableGpuCheckBox,
            buttonBox
        );

        return section;
    }

    // ========================================================================
    //  Load Preferences
    // ========================================================================

    public void loadPreferences() {
        if (prefManager == null) {
            LOGGER.error("PreferenceManager is null - cannot load preferences");
            return;
        }

        // Load font size from AppState
        double fontSize = appState.getFontSize();
        if (fontSize <= 0) {
            fontSize = AppConstants.DEFAULT_FONT_SIZE;
        }
        fontSizeSlider.setValue(fontSize);

        // Load log level
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
        noiseReductionCheckBox.setSelected(prefManager.isNoiseReductionEnabled());
        confidenceCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.CONFIDENCE_ENABLED, false));
        exportWordCopyCheckBox.setSelected(prefManager.getBoolean("export_word_copy", false));
        exportPdfCopyCheckBox.setSelected(prefManager.getBoolean("export_pdf_copy", false));
        keepProcessedCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.KEEP_PROCESSED, false));
        enableTranscriptionCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.TRANSCRIPTION_ENABLED, true));
        autoRemoveCompletedCheckBox.setSelected(prefManager.getBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, false));
        adaptiveScalingCheckBox.setSelected(prefManager.getBoolean("adaptive_scaling_enabled", true));
        skipSegmentationCheckBox.setSelected(prefManager.getBoolean("skip_segmentation_baseline_mode", false));
        removeSilenceCheckBox.setSelected(prefManager.getBoolean("remove_silence", false));
        normalizeCheckBox.setSelected(prefManager.isNormalizeAudioEnabled());

        // Load ID3 Tagging preference
        id3TaggingCheckBox.setSelected(prefManager.isID3TaggingEnabled());

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

        // GPU settings
        if (enableGpuCheckBox != null) {
            enableGpuCheckBox.setSelected(prefManager.getBoolean("gpu.enabled", true));
        }

        // Load Translation preferences
        translationEnabledCheckBox.setSelected(prefManager.isTranslationEnabled());
        translationLanguageComboBox.setValue(prefManager.getTranslationTargetLanguage());
        translationEndpointField.setText(prefManager.getTranslationEndpoint());
        String apiKey = prefManager.getTranslationApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            translationApiKeyField.setText(apiKey);
        } else {
            translationApiKeyField.clear();
        }
        translationLanguageComboBox.setDisable(!prefManager.isTranslationEnabled());
        translationEndpointField.setDisable(!prefManager.isTranslationEnabled());
        translationApiKeyField.setDisable(!prefManager.isTranslationEnabled());
        updateTranslationStatus();

        // Update dependency status
        updateDependencyStatus();

        LOGGER.debug("Preferences loaded successfully - font size from AppState: {}", appState.getFontSize());
    }

    // ========================================================================
    //  Config Builders
    // ========================================================================

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
            .skipSegmentation(skipSegmentationCheckBox.isSelected())
            // Translation settings
            .translationEnabled(translationEnabledCheckBox.isSelected())
            .translationTargetLanguage(translationLanguageComboBox.getValue())
            .translationEndpoint(translationEndpointField.getText())
            .translationApiKey(translationApiKeyField.getText())
            .build();
    }

    // ========================================================================
    //  Utility Methods
    // ========================================================================

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

    public boolean isSkipSegmentationEnabled() {
        return skipSegmentationCheckBox.isSelected();
    }

    public boolean isID3TaggingEnabled() {
        return prefManager.isID3TaggingEnabled();
    }

    public boolean isGpuEnabled() {
        return enableGpuCheckBox != null && enableGpuCheckBox.isSelected();
    }

    // Translation Getters
    public boolean isTranslationEnabled() {
        return translationEnabledCheckBox != null && translationEnabledCheckBox.isSelected();
    }

    public String getTranslationTargetLanguage() {
        return translationLanguageComboBox != null ? translationLanguageComboBox.getValue() : "es";
    }

    public String getTranslationEndpoint() {
        return translationEndpointField != null ? translationEndpointField.getText() : "https://libretranslate.com/translate";
    }

    public String getTranslationApiKey() {
        return translationApiKeyField != null ? translationApiKeyField.getText() : null;
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
        adaptiveScalingCheckBox.setDisable(!enabled);
        skipSegmentationCheckBox.setDisable(!enabled);
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
        id3TaggingCheckBox.setDisable(!enabled);
        enableGpuCheckBox.setDisable(!enabled);
        
        // Translation controls
        translationEnabledCheckBox.setDisable(!enabled);
        boolean translationEnabled = enabled && translationEnabledCheckBox.isSelected();
        translationLanguageComboBox.setDisable(!translationEnabled);
        translationEndpointField.setDisable(!translationEnabled);
        translationApiKeyField.setDisable(!translationEnabled);

        // Silence controls only enabled when remove silence is checked and panel is enabled
        boolean silenceEnabled = enabled && removeSilenceCheckBox.isSelected();
        silenceThresholdSlider.setDisable(!silenceEnabled);
        silenceDurationSlider.setDisable(!silenceEnabled);
    }

    public void setFontSizeChangeListener(Consumer<Double> listener) {
        this.fontSizeChangeListener = listener;
        // Immediately notify with current value
        if (listener != null) {
            listener.accept(fontSizeSlider.getValue());
        }
    }

    public void refreshAllComponents() {
        loadPreferences();
        updateGpuStatus();
        updateTranslationStatus();
        updateDependencyStatus();
    }

    // ========================================================================
    //  Styling Helper
    // ========================================================================

    private void setStyled(Node node, String style) {
        node.setStyle(style);
        ThemeManager.stripForCurrentTheme(node);
    }

    // ========================================================================
    //  Private Helpers
    // ========================================================================

    private void applyFontSize(double size) {
        if (fontSizeChangeListener != null) {
            fontSizeChangeListener.accept(size);
        }
    }

    private void applyLogLevel(String levelName) {
        if (tryApplyLogLevelViaLogback(levelName)) {
            return;
        }
        applyLogLevelViaJUL(levelName);
    }

    private boolean tryApplyLogLevelViaLogback(String levelName) {
        try {
            Class<?> logbackLoggerClass = Class.forName("ch.qos.logback.classic.Logger");
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");

            Object rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            if (!logbackLoggerClass.isInstance(rootLogger)) {
                return false;
            }

            Object level = levelClass.getMethod("toLevel", String.class).invoke(null, levelName);
            logbackLoggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, level);
            LOGGER.info("Log level changed to {} (via Logback)", levelName);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Could not apply log level '{}' via Logback: {}", levelName, e.getMessage());
            return false;
        }
    }

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
        alert.setContentText(license.getFeatureSummary() + "\n\nEnter your license key to upgrade:");
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
                        refreshAllComponents();
                        updateLicenseUI(license);
                        showInfoAlert("✅ Pro License Activated", 
                            "You now have access to all Pro features.");
                    } else {
                        showErrorAlert("❌ Invalid License", 
                            "The license key you entered is invalid. Please check and try again.");
                    }
                });
            }
        });
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

    // ========================================================================
    //  Error Reporting & Auto-Update Checkboxes
    // ========================================================================

    public CheckBox createErrorReportingCheckBox() {
        CheckBox cb = new CheckBox("Send anonymous error reports");
        cb.setTooltip(new Tooltip("Help improve the app by sending anonymous error reports"));
        cb.setSelected(prefManager.getBoolean("error.reporting.enabled", false));
        cb.setOnAction(e -> {
            boolean enabled = cb.isSelected();
            prefManager.putBoolean("error.reporting.enabled", enabled);
            prefManager.flush();

            audiomanager.Studio studio = audiomanager.Studio.getInstance();
            if (studio != null && studio.getErrorReporter() != null) {
                studio.getErrorReporter().setEnabled(enabled);
            }
            LOGGER.info("Error reporting {}", enabled ? "enabled" : "disabled");
        });
        return cb;
    }

    public CheckBox createAutoUpdateCheckBox() {
        CheckBox cb = new CheckBox("Check for updates automatically");
        cb.setTooltip(new Tooltip("Automatically check for new versions on startup"));
        cb.setSelected(prefManager.getBoolean("auto.update.enabled", true));
        cb.setOnAction(e -> {
            boolean enabled = cb.isSelected();
            prefManager.putBoolean("auto.update.enabled", enabled);
            prefManager.flush();
            LOGGER.info("Auto-update {}", enabled ? "enabled" : "disabled");
        });
        return cb;
    }
    
    /**
     * Gets a user-friendly GPU status message.
     */
    private String getGpuStatusMessage() {
        gpuConfig.detectGpu();
        if (gpuConfig.isGpuAvailable()) {
            return "🟢 GPU Detected: " + gpuConfig.getGpuName();
        } else {
            return "ℹ️ CPU Mode - No compatible GPU detected";
        }
    }

    /**
     * Gets a user-friendly GPU status tooltip.
     */
    private String getGpuStatusTooltip() {
        gpuConfig.detectGpu();
        if (gpuConfig.isGpuAvailable()) {
            return null; // No tooltip needed when GPU is available
        } else {
            return "The application is running on CPU for transcription.\n\n" +
                   "For faster performance, you can:\n" +
                   "• Use a smaller model (tiny, base, small)\n" +
                   "• Reduce parallel file processing\n" +
                   "• Install an NVIDIA GPU with CUDA support\n\n" +
                   "Note: CPU mode still works for all features.";
        }
    }
}