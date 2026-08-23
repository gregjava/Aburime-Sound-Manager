/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages theme switching (light/dark mode) with proper CSS support.
 * 
 * <p>Uses !important in dark.css to override inline styles without stripping them.
 * This eliminates flickering during theme transitions.</p>
 */
public final class ThemeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeManager.class);

    private ThemeManager() {}

    private static volatile boolean darkModeActive = false;

    /**
     * Check if dark mode is active (for conditional styling in UI code).
     */
    public static boolean isDarkModeActive() {
        return darkModeActive;
    }

    /**
     * Get the appropriate CSS class for a component based on the current theme.
     */
    public static String getThemeClass(String lightClass, String darkClass) {
        return darkModeActive ? darkClass : lightClass;
    }

    /**
     * Apply theme to the scene - simply adds/removes the dark stylesheet.
     * No inline style stripping = no flickering.
     */
    public static void sweep(Scene scene, boolean dark) {
        darkModeActive = dark;
        if (scene == null || scene.getRoot() == null) return;

        String darkCssUrl = resolveDarkCssUrl();

        if (dark) {
            // Add dark stylesheet if not already present
            if (darkCssUrl != null && !scene.getStylesheets().contains(darkCssUrl)) {
                scene.getStylesheets().add(darkCssUrl);
                LOGGER.info("✅ Added dark.css to scene stylesheets");
            }
            // Add dark-mode class to root
            scene.getRoot().getStyleClass().add("dark-mode");
            // Force CSS to apply
            scene.getRoot().applyCss();
        } else {
            // Remove dark stylesheet
            if (darkCssUrl != null) {
                scene.getStylesheets().remove(darkCssUrl);
            }
            scene.getRoot().getStyleClass().remove("dark-mode");
            // Force CSS to apply
            scene.getRoot().applyCss();
        }
    }

    /**
     * Node-based overload of {@link #sweep(Scene, boolean)}.
     */
    public static void sweep(Node root, boolean dark) {
        darkModeActive = dark;
        if (root == null) return;
        // Just add/remove the dark-mode class
        if (dark) {
            root.getStyleClass().add("dark-mode");
        } else {
            root.getStyleClass().remove("dark-mode");
        }
        root.applyCss();
    }

    /**
     * Applies the currently-active theme to a dialog.
     */
    public static void applyCurrentThemeToDialog(DialogPane pane, Scene sceneOrNull) {
        if (!darkModeActive) return;

        String darkCssUrl = resolveDarkCssUrl();
        if (darkCssUrl == null) return;

        if (pane != null) {
            if (!pane.getStylesheets().contains(darkCssUrl)) {
                pane.getStylesheets().add(darkCssUrl);
            }
        }
        if (sceneOrNull != null) {
            if (!sceneOrNull.getStylesheets().contains(darkCssUrl)) {
                sceneOrNull.getStylesheets().add(darkCssUrl);
            }
        }
    }

    /**
     * Resolves the dark CSS URL from the classpath.
     */
    private static String resolveDarkCssUrl() {
        try {
            // Try the correct path for your project structure
            var resource = ThemeManager.class.getResource("/audiomanager/styles/dark.css");
            if (resource == null) {
                // Fallback: try the standard path
                resource = ThemeManager.class.getResource("/styles/dark.css");
            }
            if (resource != null) {
                return resource.toExternalForm();
            }
            LOGGER.warn("❌ dark.css not found in classpath");
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to load dark.css: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the light CSS URL from the classpath.
     */
    public static String resolveLightCssUrl() {
        try {
            var resource = ThemeManager.class.getResource("/styles/styles.css");
            if (resource != null) {
                return resource.toExternalForm();
            }
            LOGGER.warn("styles.css not found in classpath at /styles/styles.css");
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to load styles.css: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Strip inline color rules from a single node.
     * No longer strips styles - CSS !important overrides them.
     */
    public static void stripForCurrentTheme(Node node) {
        // Do nothing - CSS !important handles everything
    }

    /**
     * Force a complete UI refresh after theme change.
     */
    public static void forceRefresh(Scene scene) {
        if (scene == null || scene.getRoot() == null) return;
        scene.getRoot().applyCss();
        scene.getRoot().requestLayout();
    }
}