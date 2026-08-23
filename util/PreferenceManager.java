/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

/**
 *
 * @author USER
 */

import audiomanager.constants.AppConstants;
import audiomanager.constants.PreferenceKeys;
import java.util.prefs.BackingStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.Preferences;

/**
 * Manages application preferences with type-safe access
 */
public class PreferenceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreferenceManager.class);
    private final Preferences prefs;
    
    // Add these constants
    private static final String AUTO_VOLUME_OPTIMIZATION_KEY = "autoVolumeOptimization";
    private static final String TARGET_VOLUME_DB_KEY = "targetVolumeDb";

    // Add getters/setters
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

    public PreferenceManager(Class<?> clazz) {
        this.prefs = Preferences.userNodeForPackage(clazz);
    }

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

    // Long preferences (NEW)
    public long getLong(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    public void putLong(String key, long value) {
        prefs.putLong(key, value);
    }

    // =============================================
    // EULA Methods (NEW)
    // =============================================
    
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

    // =============================================
    // ID3 Tagging Methods (NEW)
    // =============================================
    
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

    // =============================================
    // Code Signing Methods (NEW)
    // =============================================
    
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

    // =============================================
    // Convenience methods for common preferences
    // =============================================

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
        return prefs.get(PreferenceKeys.LAST_FILE_ADD_LOCATION, System.getProperty("user.home"));
    }
    
    /**
     * Set last file add location
     */
    public void setLastFileAddLocation(String path) {
        prefs.put(PreferenceKeys.LAST_FILE_ADD_LOCATION, path);
    }
    
    /**
     * Get last audio splitter location
     */
    public String getLastAudioSplitterLocation() {
        return prefs.get(PreferenceKeys.LAST_AUDIO_SPLITTER_LOCATION, System.getProperty("user.home"));
    }
    
    /**
     * Set last audio splitter location
     */
    public void setLastAudioSplitterLocation(String path) {
        prefs.put(PreferenceKeys.LAST_AUDIO_SPLITTER_LOCATION, path);
    }
    
    /**
     * Get last text combiner location
     */
    public String getLastTextCombinerLocation() {
        return prefs.get(PreferenceKeys.LAST_TEXT_COMBINER_LOCATION, System.getProperty("user.home"));
    }
    
    /**
     * Set last text combiner location
     */
    public void setLastTextCombinerLocation(String path) {
        prefs.put(PreferenceKeys.LAST_TEXT_COMBINER_LOCATION, path);
    }

    /**
     * Get last Audio Splitter OUTPUT directory (distinct from the input-file
     * location above). Falls back to the app's general output directory,
     * then the user's home, so a first-time user still gets something
     * sensible.
     */
    public String getLastAudioSplitterOutputLocation() {
        return prefs.get("last_audio_splitter_output_location", getOutputDirectory());
    }

    public void setLastAudioSplitterOutputLocation(String path) {
        prefs.put("last_audio_splitter_output_location", path);
    }

    /**
     * Get last Text File Combiner OUTPUT location (the folder the save
     * dialog opens into — the combined filename itself isn't remembered,
     * only the directory).
     */
    public String getLastTextCombinerOutputLocation() {
        return prefs.get("last_text_combiner_output_location", getOutputDirectory());
    }

    public void setLastTextCombinerOutputLocation(String path) {
        prefs.put("last_text_combiner_output_location", path);
    }

    /**
     * Get last Sound Recorder Panel save location — same pattern as the
     * AudioSplitter/TextCombiner output locations above.
     */
    public String getLastSoundRecorderLocation() {
        return prefs.get("last_sound_recorder_location", getOutputDirectory());
    }

    public void setLastSoundRecorderLocation(String path) {
        prefs.put("last_sound_recorder_location", path);
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
            prefs.remove("batch_queue_files");

            // Remove any temporary processing state
            prefs.remove("last_processing_state");
            prefs.remove("processing_start_time");

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
    
    /**
     * Remove a preference key
     */
    public void remove(String key) {
        prefs.remove(key);
    }
    
    /**
     * Window state holder
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