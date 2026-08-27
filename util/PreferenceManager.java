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
        return getBoolean(PreferenceKeys.AUTO_VOLUME_OPTIMIZATION, true);
    }

    public void setAutoVolumeOptimizationEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.AUTO_VOLUME_OPTIMIZATION, enabled);
    }

    public double getTargetVolumeDb() {
        return getDouble(PreferenceKeys.TARGET_VOLUME_DB, -1.0);
    }

    public void setTargetVolumeDb(double targetDb) {
        putDouble(PreferenceKeys.TARGET_VOLUME_DB, targetDb);
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
        return getBoolean(PreferenceKeys.TRANSLATION_ENABLED, false);
    }

    /**
     * Sets whether translation is enabled.
     *
     * @param enabled {@code true} to enable translation
     */
    public void setTranslationEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.TRANSLATION_ENABLED, enabled);
    }

    /**
     * Returns the target language for translation.
     *
     * @return the target language code (e.g., "es", "fr"), or "es" if not set
     */
    public String getTranslationTargetLanguage() {
        return getString(PreferenceKeys.TRANSLATION_TARGET_LANGUAGE, "es");
    }

    /**
     * Sets the target language for translation.
     *
     * @param language the target language code (e.g., "es", "fr")
     */
    public void setTranslationTargetLanguage(String language) {
        putString(PreferenceKeys.TRANSLATION_TARGET_LANGUAGE, language != null ? language : "es");
    }

    /**
     * Returns the translation endpoint URL.
     *
     * @return the endpoint URL, or the default LibreTranslate URL if not set
     */
    public String getTranslationEndpoint() {
        return getString(PreferenceKeys.TRANSLATION_ENDPOINT, "https://libretranslate.com/translate");
    }

    /**
     * Sets the translation endpoint URL.
     *
     * @param endpoint the endpoint URL
     */
    public void setTranslationEndpoint(String endpoint) {
        putString(PreferenceKeys.TRANSLATION_ENDPOINT, endpoint != null ? endpoint : "https://libretranslate.com/translate");
    }

    /**
     * Returns the translation API key.
     *
     * @return the API key, or {@code null} if not set
     */
    public String getTranslationApiKey() {
        return getString(PreferenceKeys.TRANSLATION_API_KEY, null);
    }

    /**
     * Sets the translation API key.
     *
     * @param apiKey the API key, or {@code null} to clear
     */
    public void setTranslationApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            putString(PreferenceKeys.TRANSLATION_API_KEY, apiKey);
        } else {
            remove(PreferenceKeys.TRANSLATION_API_KEY);
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
        return getBoolean(PreferenceKeys.ERROR_REPORTING_ENABLED, false);
    }

    /**
     * Sets whether error reporting is enabled.
     *
     * @param enabled {@code true} to enable error reporting
     */
    public void setErrorReportingEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.ERROR_REPORTING_ENABLED, enabled);
    }

    /**
     * Returns the timestamp of the last error report sent.
     *
     * @return the timestamp in milliseconds, or 0 if never sent
     */
    public long getLastErrorReportSent() {
        return getLong(PreferenceKeys.ERROR_REPORTING_LAST_SENT, 0);
    }

    /**
     * Sets the timestamp of the last error report sent.
     *
     * @param timestamp the timestamp in milliseconds
     */
    public void setLastErrorReportSent(long timestamp) {
        putLong(PreferenceKeys.ERROR_REPORTING_LAST_SENT, timestamp);
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
        return getBoolean(PreferenceKeys.AUTO_UPDATE_ENABLED, true);
    }

    /**
     * Sets whether auto-update checking is enabled.
     *
     * @param enabled {@code true} to enable auto-update
     */
    public void setAutoUpdateEnabled(boolean enabled) {
        putBoolean(PreferenceKeys.AUTO_UPDATE_ENABLED, enabled);
    }

    /**
     * Returns the version that the user chose to skip.
     *
     * @return the skipped version, or {@code null} if none
     */
    public String getSkippedUpdateVersion() {
        return getString(PreferenceKeys.UPDATE_SKIPPED_VERSION, null);
    }

    /**
     * Sets the version that the user chose to skip.
     *
     * @param version the version to skip, or {@code null} to clear
     */
    public void setSkippedUpdateVersion(String version) {
        if (version != null && !version.isBlank()) {
            putString(PreferenceKeys.UPDATE_SKIPPED_VERSION, version);
        } else {
            remove(PreferenceKeys.UPDATE_SKIPPED_VERSION);
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
            remove(PreferenceKeys.BATCH_QUEUE_FILES);
            remove(PreferenceKeys.BATCH_QUEUE_LAST_SAVED);
            return;
        }
        String joined = String.join(";", filePaths);
        putString(PreferenceKeys.BATCH_QUEUE_FILES, joined);
        putLong(PreferenceKeys.BATCH_QUEUE_LAST_SAVED, System.currentTimeMillis());
    }

    /**
     * Loads the saved batch queue file list.
     *
     * @return the list of file paths, or an empty list if none
     */
    public List<String> loadBatchQueueFiles() {
        String saved = getString(PreferenceKeys.BATCH_QUEUE_FILES, "");
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
        long lastSaved = getLong(PreferenceKeys.BATCH_QUEUE_LAST_SAVED, 0);
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
        return getLong(PreferenceKeys.LAST_DEPENDENCY_CHECK, 0);
    }

    /**
     * Sets the timestamp of the last dependency check.
     *
     * @param timestamp the timestamp in milliseconds
     */
    public void setLastDependencyCheck(long timestamp) {
        putLong(PreferenceKeys.LAST_DEPENDENCY_CHECK, timestamp);
    }

    /**
     * Returns whether all dependencies were OK on the last check.
     *
     * @return {@code true} if dependencies were OK
     */
    public boolean areDependenciesOk() {
        return getBoolean(PreferenceKeys.DEPENDENCIES_OK, false);
    }

    /**
     * Sets whether all dependencies were OK on the last check.
     *
     * @param ok {@code true} if dependencies were OK
     */
    public void setDependenciesOk(boolean ok) {
        putBoolean(PreferenceKeys.DEPENDENCIES_OK, ok);
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
        return getString(PreferenceKeys.LAST_AUDIO_SPLITTER_OUTPUT_LOCATION, getOutputDirectory());
    }

    public void setLastAudioSplitterOutputLocation(String path) {
        putString(PreferenceKeys.LAST_AUDIO_SPLITTER_OUTPUT_LOCATION, path);
    }

    /**
     * Get last Text File Combiner OUTPUT location
     */
    public String getLastTextCombinerOutputLocation() {
        return getString(PreferenceKeys.LAST_TEXT_COMBINER_OUTPUT_LOCATION, getOutputDirectory());
    }

    public void setLastTextCombinerOutputLocation(String path) {
        putString(PreferenceKeys.LAST_TEXT_COMBINER_OUTPUT_LOCATION, path);
    }

    /**
     * Get last Sound Recorder Panel save location
     */
    public String getLastSoundRecorderLocation() {
        return getString(PreferenceKeys.LAST_SOUND_RECORDER_LOCATION, getOutputDirectory());
    }

    public void setLastSoundRecorderLocation(String path) {
        putString(PreferenceKeys.LAST_SOUND_RECORDER_LOCATION, path);
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
        return getString(PreferenceKeys.WHISPER_MODEL, AppConstants.DEFAULT_MODEL);
    }

    public void setWhisperModel(String model) {
        putString(PreferenceKeys.WHISPER_MODEL, model);
    }

    public String getLanguage() {
        return getString(PreferenceKeys.LANGUAGE, AppConstants.DEFAULT_LANGUAGE);
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
            remove(PreferenceKeys.BATCH_QUEUE_FILES);
            remove(PreferenceKeys.BATCH_QUEUE_LAST_SAVED);

            // Remove any temporary processing state
            remove(PreferenceKeys.LAST_PROCESSING_STATE);
            remove(PreferenceKeys.PROCESSING_STATE_TIMESTAMP);

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
        putString(PreferenceKeys.LAST_PROCESSING_STATE, state);
        putLong(PreferenceKeys.PROCESSING_STATE_TIMESTAMP, System.currentTimeMillis());
        flush();
    }

    /**
     * Get saved processing state
     */
    public String getProcessingState() {
        return getString(PreferenceKeys.LAST_PROCESSING_STATE, "");
    }

    /**
     * Check if there's a recent session to restore
     */
    public boolean hasRecentSession() {
        long lastSave = getLong(PreferenceKeys.PROCESSING_STATE_TIMESTAMP, 0);
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