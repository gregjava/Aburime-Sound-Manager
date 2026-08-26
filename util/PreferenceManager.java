/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

import audiomanager.constants.AppConstants;
import audiomanager.constants.PreferenceKeys;
import java.util.List;
import java.util.prefs.BackingStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.Preferences;

/**
 * Manages application preferences with type-safe access
 * 
 * <p>This class provides a centralized, type-safe way to store and retrieve
 * application preferences using the Java Preferences API.</p>
 * 
 * <p><b>Key Preferences:</b></p>
 * <ul>
 *   <li><b>Audio Processing:</b> Volume boost, noise reduction, normalization</li>
 *   <li><b>Transcription:</b> Model, language, timestamps, confidence</li>
 *   <li><b>Batch Processing:</b> Max parallel files, auto-remove completed</li>
 *   <li><b>Translation:</b> Enabled, target language, endpoint, API key</li>
 *   <li><b>UI:</b> Theme, font size, window state</li>
 *   <li><b>EULA:</b> Accepted version</li>
 * </ul>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 */
public class PreferenceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreferenceManager.class);
    private final Preferences prefs;
    
    // ===== Audio Processing Keys =====
    private static final String AUTO_VOLUME_OPTIMIZATION_KEY = "autoVolumeOptimization";
    private static final String TARGET_VOLUME_DB_KEY = "targetVolumeDb";

    // ===== Translation Preference Keys =====
    private static final String TRANSLATION_ENABLED_KEY = "translation.enabled";
    private static final String TRANSLATION_TARGET_LANGUAGE_KEY = "translation.target_language";
    private static final String TRANSLATION_ENDPOINT_KEY = "translation.endpoint";
    private static final String TRANSLATION_API_KEY_KEY = "translation.api_key";

    // ===== Batch Processing Keys =====
    private static final String BATCH_QUEUE_FILES_KEY = "batch_queue_files";
    private static final String BATCH_QUEUE_LAST_SAVED_KEY = "batch_queue_last_saved";

    // ===== Error Reporting Keys =====
    private static final String ERROR_REPORTING_ENABLED_KEY = "error.reporting.enabled";
    private static final String ERROR_REPORTING_LAST_SENT_KEY = "error.reporting.last_sent";

    // ===== Auto-Update Keys =====
    private static final String AUTO_UPDATE_ENABLED_KEY = "auto.update.enabled";
    private static final String UPDATE_SKIPPED_VERSION_KEY = "update.skipped.version";

    // ===== Dependency Check Keys =====
    private static final String LAST_DEPENDENCY_CHECK_KEY = "last.dependency.check";
    private static final String DEPENDENCIES_OK_KEY = "dependencies.ok";

    // =========================================================================
    //  Constructors
    // =========================================================================

    public PreferenceManager(Class<?> clazz) {
        this.prefs = Preferences.userNodeForPackage(clazz);
    }

    // =========================================================================
    //  Generic Preference Methods
    // =========================================================================

    // String preferences
    public String getString(String key, String defaultValue) {
        return prefs.get(key, defaultValue);
    }

    public void putString(String key, String value) {
        prefs.put(key, value);
    }

    // Integer preferences
    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    public void putInt(String key, int value) {
        prefs.putInt(key, value);
    }

    // Double preferences
    public double getDouble(String key, double defaultValue) {
        return prefs.getDouble(key, defaultValue);
    }

    public void putDouble(String key, double value) {
        prefs.putDouble(key, value);
    }

    // Boolean preferences
    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public void putBoolean(String key, boolean value) {
        prefs.putBoolean(key, value);
    }

    // Long preferences
    public long getLong(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    public void putLong(String key, long value) {
        prefs.putLong(key, value);
    }

    // Remove a preference
    public void remove(String key) {
        prefs.remove(key);
    }

    // =========================================================================
    //  Volume Optimization Preferences
    // =========================================================================

    public boolean isAutoVolumeOptimizationEnabled() {
        return getBoolean(AUTO_VOLUME_OPTIMIZATION_KEY, true);
    }

    public void setAutoVolumeOptimizationEnabled(boolean enabled) {
        putBoolean(AUTO_VOLUME_OPTIMIZATION_KEY, enabled);
    }

    public double getTargetVolumeDb() {
        return getDouble(TARGET_VOLUME_DB_KEY, -1.0);
    }

    public void setTargetVolumeDb(double targetDb) {
        putDouble(TARGET_VOLUME_DB_KEY, targetDb);
    }

    // =========================================================================
    //  Translation Preferences
    // =========================================================================

    /**
     * Returns whether translation is enabled.
     *
     * @return {@code true} if translation is enabled
     */
    public boolean isTranslationEnabled() {
        return getBoolean(TRANSLATION_ENABLED_KEY, false);
    }

    /**
     * Sets whether translation is enabled.
     *
     * @param enabled {@code true} to enable translation
     */
    public void setTranslationEnabled(boolean enabled) {
        putBoolean(TRANSLATION_ENABLED_KEY, enabled);
    }

    /**
     * Returns the target language for translation.
     *
     * @return the target language code (e.g., "es", "fr"), or "es" if not set
     */
    public String getTranslationTargetLanguage() {
        return getString(TRANSLATION_TARGET_LANGUAGE_KEY, "es");
    }

    /**
     * Sets the target language for translation.
     *
     * @param language the target language code (e.g., "es", "fr")
     */
    public void setTranslationTargetLanguage(String language) {
        putString(TRANSLATION_TARGET_LANGUAGE_KEY, language != null ? language : "es");
    }

    /**
     * Returns the translation endpoint URL.
     *
     * @return the endpoint URL, or the default LibreTranslate URL if not set
     */
    public String getTranslationEndpoint() {
        return getString(TRANSLATION_ENDPOINT_KEY, "https://libretranslate.com/translate");
    }

    /**
     * Sets the translation endpoint URL.
     *
     * @param endpoint the endpoint URL
     */
    public void setTranslationEndpoint(String endpoint) {
        putString(TRANSLATION_ENDPOINT_KEY, endpoint != null ? endpoint : "https://libretranslate.com/translate");
    }

    /**
     * Returns the translation API key.
     *
     * @return the API key, or {@code null} if not set
     */
    public String getTranslationApiKey() {
        return getString(TRANSLATION_API_KEY_KEY, null);
    }

    /**
     * Sets the translation API key.
     *
     * @param apiKey the API key, or {@code null} to clear
     */
    public void setTranslationApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            putString(TRANSLATION_API_KEY_KEY, apiKey);
        } else {
            remove(TRANSLATION_API_KEY_KEY);
        }
    }

    // =========================================================================
    //  Error Reporting Preferences
    // =========================================================================

    /**
     * Returns whether error reporting is enabled.
     *
     * @return {@code true} if error reporting is enabled
     */
    public boolean isErrorReportingEnabled() {
        return getBoolean(ERROR_REPORTING_ENABLED_KEY, false);
    }

    /**
     * Sets whether error reporting is enabled.
     *
     * @param enabled {@code true} to enable error reporting
     */
    public void setErrorReportingEnabled(boolean enabled) {
        putBoolean(ERROR_REPORTING_ENABLED_KEY, enabled);
    }

    /**
     * Returns the timestamp of the last error report sent.
     *
     * @return the timestamp in milliseconds, or 0 if never sent
     */
    public long getLastErrorReportSent() {
        return getLong(ERROR_REPORTING_LAST_SENT_KEY, 0);
    }

    /**
     * Sets the timestamp of the last error report sent.
     *
     * @param timestamp the timestamp in milliseconds
     */
    public void setLastErrorReportSent(long timestamp) {
        putLong(ERROR_REPORTING_LAST_SENT_KEY, timestamp);
    }

    // =========================================================================
    //  Auto-Update Preferences
    // =========================================================================

    /**
     * Returns whether auto-update checking is enabled.
     *
     * @return {@code true} if auto-update is enabled
     */
    public boolean isAutoUpdateEnabled() {
        return getBoolean(AUTO_UPDATE_ENABLED_KEY, true);
    }

    /**
     * Sets whether auto-update checking is enabled.
     *
     * @param enabled {@code true} to enable auto-update
     */
    public void setAutoUpdateEnabled(boolean enabled) {
        putBoolean(AUTO_UPDATE_ENABLED_KEY, enabled);
    }

    /**
     * Returns the version that the user chose to skip.
     *
     * @return the skipped version, or {@code null} if none
     */
    public String getSkippedUpdateVersion() {
        return getString(UPDATE_SKIPPED_VERSION_KEY, null);
    }

    /**
     * Sets the version that the user chose to skip.
     *
     * @param version the version to skip, or {@code null} to clear
     */
    public void setSkippedUpdateVersion(String version) {
        if (version != null && !version.isBlank()) {
            putString(UPDATE_SKIPPED_VERSION_KEY, version);
        } else {
            remove(UPDATE_SKIPPED_VERSION_KEY);
        }
    }

    // =========================================================================
    //  EULA Preferences
    // =========================================================================
    
    /**
     * Gets the version of the EULA that the user has accepted.
     * Returns 0 if the EULA has never been accepted.
     */
    public int getEulaAcceptedVersion() {
        return getInt(PreferenceKeys.EULA_ACCEPTED_VERSION, 0);
    }

    /**
     * Sets the version of the EULA that the user has accepted.
     * @param version The version number to mark as accepted
     */
    public void setEulaAcceptedVersion(int version) {
        putInt(PreferenceKeys.EULA_ACCEPTED_VERSION, version);
    }

    // =========================================================================
    //  ID3 Tagging Preferences
    // =========================================================================
    
    /**
     * Checks if ID3 tagging is enabled.
     * @return true if ID3 tagging is enabled, false otherwise
     */
    public boolean isID3TaggingEnabled() {
        return getBoolean(PreferenceKeys.ID3_TAGGING_ENABLED, false);
    }

    /**
     * Enables or disables ID3 tagging.
     * @param enabled true to enable, false to disable
     */
    public void setID3TaggingEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.ID3_TAGGING_ENABLED, enabled);
    }

    // =========================================================================
    //  Code Signing Preferences
    // =========================================================================
    
    /**
     * Checks if the current application instance is code-signed.
     * This is a verification flag that can be set after successful signature verification.
     * @return true if the application is verified as code-signed
     */
    public boolean isCodeSigned() {
        return getBoolean(PreferenceKeys.CODE_SIGNED, false);
    }

    /**
     * Sets the code signing verification status.
     * @param signed true if the application is verified as code-signed
     */
    public void setCodeSigned(boolean signed) {
        putBoolean(PreferenceKeys.CODE_SIGNED, signed);
    }

    // =========================================================================
    //  Batch Queue Preferences
    // =========================================================================

    /**
     * Saves the batch queue file list.
     *
     * @param filePaths the list of file paths, or null to clear
     */
    public void saveBatchQueueFiles(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            remove(BATCH_QUEUE_FILES_KEY);
            remove(BATCH_QUEUE_LAST_SAVED_KEY);
            return;
        }
        String joined = String.join(";", filePaths);
        putString(BATCH_QUEUE_FILES_KEY, joined);
        putLong(BATCH_QUEUE_LAST_SAVED_KEY, System.currentTimeMillis());
    }

    /**
     * Loads the saved batch queue file list.
     *
     * @return the list of file paths, or an empty list if none
     */
    public List<String> loadBatchQueueFiles() {
        String saved = getString(BATCH_QUEUE_FILES_KEY, "");
        if (saved == null || saved.isBlank()) {
            return new java.util.ArrayList<>();
        }
        return java.util.Arrays.asList(saved.split(";"));
    }

    /**
     * Checks if there is a recent batch queue saved.
     *
     * @return {@code true} if a recent batch queue exists
     */
    public boolean hasRecentBatchQueue() {
        long lastSaved = getLong(BATCH_QUEUE_LAST_SAVED_KEY, 0);
        return lastSaved > 0 && (System.currentTimeMillis() - lastSaved) < 7 * 24 * 60 * 60 * 1000L; // 7 days
    }

    // =========================================================================
    //  Dependency Check Preferences
    // =========================================================================

    /**
     * Returns the timestamp of the last dependency check.
     *
     * @return the timestamp in milliseconds, or 0 if never checked
     */
    public long getLastDependencyCheck() {
        return getLong(LAST_DEPENDENCY_CHECK_KEY, 0);
    }

    /**
     * Sets the timestamp of the last dependency check.
     *
     * @param timestamp the timestamp in milliseconds
     */
    public void setLastDependencyCheck(long timestamp) {
        putLong(LAST_DEPENDENCY_CHECK_KEY, timestamp);
    }

    /**
     * Returns whether all dependencies were OK on the last check.
     *
     * @return {@code true} if dependencies were OK
     */
    public boolean areDependenciesOk() {
        return getBoolean(DEPENDENCIES_OK_KEY, false);
    }

    /**
     * Sets whether all dependencies were OK on the last check.
     *
     * @param ok {@code true} if dependencies were OK
     */
    public void setDependenciesOk(boolean ok) {
        putBoolean(DEPENDENCIES_OK_KEY, ok);
    }

    // =========================================================================
    //  Convenience methods for common preferences
    // =========================================================================

    public String getOutputDirectory() {
        return getString(PreferenceKeys.OUTPUT_DIR, System.getProperty("user.home"));
    }

    public void setOutputDirectory(String path) {
        putString(PreferenceKeys.OUTPUT_DIR, path);
    }

    public String getLastFileDirectory() {
        return getString(PreferenceKeys.LAST_FILE_DIR, System.getProperty("user.home"));
    }

    public void setLastFileDirectory(String path) {
        putString(PreferenceKeys.LAST_FILE_DIR, path);
    }

    public double getFontSize() {
        return getDouble(PreferenceKeys.FONT_SIZE, AppConstants.DEFAULT_FONT_SIZE);
    }

    public void setFontSize(double size) {
        putDouble(PreferenceKeys.FONT_SIZE, size);
    }

    public int getMaxParallelFiles() {
        int cores = Runtime.getRuntime().availableProcessors();
        int defaultValue = Math.max(1, cores / 2);
        return getInt(PreferenceKeys.MAX_PARALLEL, defaultValue);
    }

    public void setMaxParallelFiles(int count) {
        putInt(PreferenceKeys.MAX_PARALLEL, count);
    }

    // Window state
    public WindowState getWindowState() {
        return new WindowState(
            getDouble(PreferenceKeys.STAGE_X, -1),
            getDouble(PreferenceKeys.STAGE_Y, -1),
            getDouble(PreferenceKeys.STAGE_WIDTH, AppConstants.DEFAULT_WINDOW_WIDTH),
            getDouble(PreferenceKeys.STAGE_HEIGHT, AppConstants.DEFAULT_WINDOW_HEIGHT)
        );
    }

    public void setWindowState(double x, double y, double width, double height) {
        putDouble(PreferenceKeys.STAGE_X, x);
        putDouble(PreferenceKeys.STAGE_Y, y);
        putDouble(PreferenceKeys.STAGE_WIDTH, width);
        putDouble(PreferenceKeys.STAGE_HEIGHT, height);
    }
    
    /**
     * Get last file add location
     */
    public String getLastFileAddLocation() {
        return getString(PreferenceKeys.LAST_FILE_ADD_LOCATION, System.getProperty("user.home"));
    }
    
    /**
     * Set last file add location
     */
    public void setLastFileAddLocation(String path) {
        putString(PreferenceKeys.LAST_FILE_ADD_LOCATION, path);
    }
    
    /**
     * Get last audio splitter location
     */
    public String getLastAudioSplitterLocation() {
        return getString(PreferenceKeys.LAST_AUDIO_SPLITTER_LOCATION, System.getProperty("user.home"));
    }
    
    /**
     * Set last audio splitter location
     */
    public void setLastAudioSplitterLocation(String path) {
        putString(PreferenceKeys.LAST_AUDIO_SPLITTER_LOCATION, path);
    }
    
    /**
     * Get last text combiner location
     */
    public String getLastTextCombinerLocation() {
        return getString(PreferenceKeys.LAST_TEXT_COMBINER_LOCATION, System.getProperty("user.home"));
    }
    
    /**
     * Set last text combiner location
     */
    public void setLastTextCombinerLocation(String path) {
        putString(PreferenceKeys.LAST_TEXT_COMBINER_LOCATION, path);
    }

    /**
     * Get last Audio Splitter OUTPUT directory
     */
    public String getLastAudioSplitterOutputLocation() {
        return getString("last_audio_splitter_output_location", getOutputDirectory());
    }

    public void setLastAudioSplitterOutputLocation(String path) {
        putString("last_audio_splitter_output_location", path);
    }

    /**
     * Get last Text File Combiner OUTPUT location
     */
    public String getLastTextCombinerOutputLocation() {
        return getString("last_text_combiner_output_location", getOutputDirectory());
    }

    public void setLastTextCombinerOutputLocation(String path) {
        putString("last_text_combiner_output_location", path);
    }

    /**
     * Get last Sound Recorder Panel save location
     */
    public String getLastSoundRecorderLocation() {
        return getString("last_sound_recorder_location", getOutputDirectory());
    }

    public void setLastSoundRecorderLocation(String path) {
        putString("last_sound_recorder_location", path);
    }
    
    // Audio Processing Settings
    public boolean isNoiseReductionEnabled() {
        return getBoolean(PreferenceKeys.NOISE_REDUCTION_ENABLED, false);
    }

    public void setNoiseReductionEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.NOISE_REDUCTION_ENABLED, enabled);
    }

    public boolean isNormalizeAudioEnabled() {
        return getBoolean(PreferenceKeys.NORMALIZE_AUDIO_ENABLED, false);
    }

    public void setNormalizeAudioEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.NORMALIZE_AUDIO_ENABLED, enabled);
    }

    public double getVolumeBoost() {
        return getDouble(PreferenceKeys.VOLUME_BOOST, 0.0);
    }

    public void setVolumeBoost(double boost) {
        putDouble(PreferenceKeys.VOLUME_BOOST, boost);
    }

    // Transcription Settings
    public boolean isTranscriptionEnabled() {
        return getBoolean(PreferenceKeys.TRANSCRIPTION_ENABLED, true);
    }

    public void setTranscriptionEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.TRANSCRIPTION_ENABLED, enabled);
    }

    public String getWhisperModel() {
        return getString(PreferenceKeys.WHISPER_MODEL, "base");
    }

    public void setWhisperModel(String model) {
        putString(PreferenceKeys.WHISPER_MODEL, model);
    }

    public String getLanguage() {
        return getString(PreferenceKeys.LANGUAGE, "auto");
    }

    public void setLanguage(String language) {
        putString(PreferenceKeys.LANGUAGE, language);
    }

    public boolean isTimestampsEnabled() {
        return getBoolean(PreferenceKeys.TIMESTAMPS_ENABLED, true);
    }

    public void setTimestampsEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.TIMESTAMPS_ENABLED, enabled);
    }

    public boolean isConfidenceEnabled() {
        return getBoolean(PreferenceKeys.CONFIDENCE_ENABLED, false);
    }

    public void setConfidenceEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.CONFIDENCE_ENABLED, enabled);
    }

    // Batch Settings
    public boolean isAutoRemoveCompleted() {
        return getBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, false);
    }

    public void setAutoRemoveCompleted(boolean autoRemove) {
        putBoolean(PreferenceKeys.AUTO_REMOVE_COMPLETED, autoRemove);
    }

    // Theme Settings
    public void setTheme(String theme) {
        putString(PreferenceKeys.THEME, theme);
        flush();
    }

    public String getTheme() {
        return getString(PreferenceKeys.THEME, "Light");
    }
    
    /**
     * Clear session-specific data (batch queue, etc.)
     */
    public void clearSessionData() {
        try {
            // Remove batch queue state
            remove(BATCH_QUEUE_FILES_KEY);
            remove(BATCH_QUEUE_LAST_SAVED_KEY);

            // Remove any temporary processing state
            remove("last_processing_state");
            remove("processing_start_time");

            flush();
            LOGGER.info("Session data cleared");
        } catch (Exception e) {
            LOGGER.error("Failed to clear session data", e);
        }
    }

    /**
     * Save current processing state
     */
    public void saveProcessingState(String state) {
        putString("last_processing_state", state);
        putLong("processing_state_timestamp", System.currentTimeMillis());
        flush();
    }

    /**
     * Get saved processing state
     */
    public String getProcessingState() {
        return getString("last_processing_state", "");
    }

    /**
     * Check if there's a recent session to restore
     */
    public boolean hasRecentSession() {
        long lastSave = getLong("processing_state_timestamp", 0);
        return (System.currentTimeMillis() - lastSave) < 300000; // 5 minutes
    }

    /**
     * Save preferences (flushes to disk)
     */
    public void flush() {
        try {
            prefs.flush();
            LOGGER.debug("Preferences saved");
        } catch (BackingStoreException e) {
            LOGGER.error("Failed to save preferences", e);
        }
    }

    // =========================================================================
    //  Inner Class: WindowState
    // =========================================================================

    /**
     * Window state holder for storing window position and size.
     */
    public static class WindowState {
        private final double x;
        private final double y;
        private final double width;
        private final double height;

        public WindowState(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public double getWidth() { return width; }
        public double getHeight() { return height; }
        public boolean hasPosition() { return x != -1 && y != -1; }
    }
}