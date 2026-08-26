/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

import javafx.scene.media.AudioClip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Manages sound effects for the application.
 *
 * <p>This class provides methods for playing UI sound effects such as
 * button clicks, file processing completion, and error notifications.</p>
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * // Play a button click sound
 * SoundManager.playClick();
 *
 * // Play a completion sound
 * SoundManager.playComplete();
 *
 * // Toggle sounds on/off
 * SoundManager.setEnabled(false);
 * }</pre>
 *
 * <p><b>Sound files:</b> The class looks for sound files in the
 * {@code /sounds/} directory of the classpath. Supported formats:
 * WAV, MP3, AIFF. If a sound file is not found, a system beep
 * is played as a fallback.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 */
public final class SoundManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundManager.class);

    private static final String PREF_KEY_ENABLED = "sound.enabled";

    private static volatile SoundManager instance;

    private final Preferences prefs;
    private final Map<SoundType, AudioClip> sounds;
    private volatile boolean enabled = true;
    private volatile boolean initialized = false;

    /**
     * Types of sounds available in the application.
     */
    public enum SoundType {
        /** Button click sound (short, subtle) */
        CLICK("click.wav"),
        /** File processing completed sound (pleasant chime) */
        COMPLETE("complete.wav"),
        /** Error or failure sound */
        ERROR("error.wav"),
        /** Processing started sound */
        START("start.wav"),
        /** Batch processing finished sound */
        BATCH_DONE("batch_done.wav");

        private final String fileName;

        SoundType(String fileName) {
            this.fileName = fileName;
        }

        public String getFileName() {
            return fileName;
        }
    }

    private SoundManager() {
        this.prefs = Preferences.userNodeForPackage(SoundManager.class);
        this.sounds = new HashMap<>();
        this.enabled = prefs.getBoolean(PREF_KEY_ENABLED, true);
    }

    /**
     * Returns the singleton instance of SoundManager.
     *
     * @return the SoundManager instance
     */
    public static SoundManager getInstance() {
        if (instance == null) {
            synchronized (SoundManager.class) {
                if (instance == null) {
                    instance = new SoundManager();
                }
            }
        }
        return instance;
    }

    /**
     * Initialises the sound manager by loading all sound files.
     *
     * <p>This method is called automatically on first sound play,
     * but can be called early to pre-load sounds.</p>
     */
    public synchronized void init() {
        if (initialized) {
            return;
        }

        LOGGER.info("Initializing SoundManager...");

        for (SoundType type : SoundType.values()) {
            loadSound(type);
        }

        initialized = true;
        LOGGER.info("SoundManager initialized (enabled: {}, loaded: {}/{})",
                   enabled, sounds.size(), SoundType.values().length);
    }

    /**
     * Loads a single sound file.
     *
     * @param type the sound type to load
     */
    private void loadSound(SoundType type) {
        String fileName = type.getFileName();
        AudioClip clip = null;

        // Try multiple resource paths
        String[] paths = {
            "/sounds/" + fileName,
            "/audiomanager/sounds/" + fileName,
            "/resources/sounds/" + fileName
        };

        for (String path : paths) {
            try {
                URL resource = SoundManager.class.getResource(path);
                if (resource != null) {
                    clip = new AudioClip(resource.toExternalForm());
                    clip.setVolume(0.5); // 50% volume
                    clip.setCycleCount(1);
                    sounds.put(type, clip);
                    LOGGER.debug("Loaded sound: {} from {}", fileName, path);
                    return;
                }
            } catch (Exception e) {
                LOGGER.trace("Could not load sound from {}: {}", path, e.getMessage());
            }
        }

        // If we get here, the sound file wasn't found
        LOGGER.debug("Sound file not found: {} - will use fallback beep", fileName);
    }

    /**
     * Plays a system beep as a fallback when sound files are unavailable.
     */
    private void playFallbackBeep() {
        try {
            java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Exception e) {
            // Silently ignore - no sounds available at all
            LOGGER.trace("Fallback beep unavailable: {}", e.getMessage());
        }
    }

    /**
     * Plays a sound of the specified type.
     *
     * @param type the sound type to play
     */
    public void play(SoundType type) {
        if (!enabled) {
            return;
        }

        // Lazy initialization
        if (!initialized) {
            init();
            if (!enabled) {
                return;
            }
        }

        AudioClip clip = sounds.get(type);
        if (clip != null) {
            try {
                clip.play();
                LOGGER.trace("Playing sound: {}", type);
                return;
            } catch (Exception e) {
                LOGGER.warn("Failed to play sound '{}': {}", type, e.getMessage());
            }
        }

        // Fallback: system beep
        playFallbackBeep();
    }

    /**
     * Plays a button click sound.
     *
     * <p>Convenience method for {@link #play(SoundType)} with {@link SoundType#CLICK}.</p>
     */
    public static void playClick() {
        getInstance().play(SoundType.CLICK);
    }

    /**
     * Plays a file processing completion sound.
     *
     * <p>Convenience method for {@link #play(SoundType)} with {@link SoundType#COMPLETE}.</p>
     */
    public static void playComplete() {
        getInstance().play(SoundType.COMPLETE);
    }

    /**
     * Plays an error sound.
     *
     * <p>Convenience method for {@link #play(SoundType)} with {@link SoundType#ERROR}.</p>
     */
    public static void playError() {
        getInstance().play(SoundType.ERROR);
    }

    /**
     * Plays a processing start sound.
     *
     * <p>Convenience method for {@link #play(SoundType)} with {@link SoundType#START}.</p>
     */
    public static void playStart() {
        getInstance().play(SoundType.START);
    }

    /**
     * Plays a batch completion sound.
     *
     * <p>Convenience method for {@link #play(SoundType)} with {@link SoundType#BATCH_DONE}.</p>
     */
    public static void playBatchDone() {
        getInstance().play(SoundType.BATCH_DONE);
    }

    /**
     * Returns whether sound effects are enabled.
     *
     * @return {@code true} if sound effects are enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables sound effects.
     *
     * @param enabled {@code true} to enable sound effects
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        prefs.putBoolean(PREF_KEY_ENABLED, enabled);
        LOGGER.info("Sound effects {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Toggles sound effects on/off.
     *
     * @return the new state (true = enabled)
     */
    public boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    /**
     * Pre-loads all sound effects for immediate playback.
     *
     * <p>Call this during application startup to eliminate the delay
     * on first sound playback.</p>
     */
    public static void preload() {
        getInstance().init();
    }

    /**
     * Returns the number of loaded sound effects.
     *
     * @return the count of loaded sounds
     */
    public int getLoadedSoundCount() {
        return sounds.size();
    }

    /**
     * Reloads all sound effects (useful after resource changes).
     */
    public synchronized void reload() {
        sounds.clear();
        initialized = false;
        init();
        LOGGER.info("Sound effects reloaded: {}/{}", sounds.size(), SoundType.values().length);
    }
}