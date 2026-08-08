/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Makes dark mode actually re-theme panels with inline styling, not just
 * the default-styled chrome/menus.
 *
 * <h2>The bug this fixes</h2>
 * <p>The previous dark-mode toggle only added/removed a stylesheet on the
 * {@link Scene}. That works for anything relying on default JavaFX
 * styling, but does nothing for the ~130 call sites across this app's
 * panels that set an inline style directly (e.g.
 * {@code button.setStyle("-fx-background-color: #3498db; ...")}) — an
 * inline style ALWAYS wins over a stylesheet rule for the same node,
 * regardless of the stylesheet's selector specificity. That's exactly the
 * "dark mode doesn't fully re-theme everything" limitation documented in
 * TROUBLESHOOTING.md.</p>
 *
 * <h2>The fix</h2>
 * <p>Rather than rewriting every one of those ~130 call sites to be
 * theme-aware (a much larger, more error-prone change), this walks the
 * scene graph on toggle and, for every node with an inline style:</p>
 * <ul>
 *   <li><b>Going dark:</b> stashes the node's original inline style in its
 *       own {@code getProperties()} map (tied to the node's lifecycle, no
 *       separate external map to leak), then strips just the
 *       {@code -fx-background-color} / {@code -fx-text-fill} /
 *       {@code -fx-border-color} rules — leaving everything else (radius,
 *       font-weight, padding, etc.) untouched — so the dark stylesheet's
 *       type-selector rules (e.g. {@code .button}) can take over for
 *       color.</li>
 *   <li><b>Going light:</b> restores each node's exact original inline
 *       style from that stash.</li>
 * </ul>
 *
 * <h2>Known remaining scope gap</h2>
 * <p>{@link #sweep} only affects nodes that exist in the scene graph at
 * the moment dark mode is toggled. Content created dynamically
 * <em>while</em> dark mode is already active (e.g. a new queue row, a
 * freshly-opened dialog) won't automatically get the same treatment unless
 * the code creating it calls {@link #stripForCurrentTheme(Node)} itself
 * after building its inline style. This is applied at the highest-traffic
 * dynamic-creation points (batch queue rows, dialogs) but is not
 * exhaustively wired into every possible call site in a codebase this
 * size — see call sites of {@link #stripForCurrentTheme} for current
 * coverage.</p>
 */
public final class ThemeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeManager.class);

    private ThemeManager() {}

    private static final String STASH_KEY = "themeManager.lightModeStyle";
    private static final Pattern COLOR_RULE_PATTERN = Pattern.compile(
            "-fx-(?:background-color|text-fill|border-color)\\s*:\\s*[^;]+;?\\s*");

    private static volatile boolean darkModeActive = false;

    /** Whether dark mode is currently the active theme — dynamic content creators check this before deciding whether to call {@link #stripForCurrentTheme}. */
    public static boolean isDarkModeActive() {
        return darkModeActive;
    }

    /**
     * Walk the entire scene graph and either strip (going dark) or restore
     * (going light) each node's inline color rules. Call this once, right
     * after adding/removing the dark stylesheet on the scene.
     */
    public static void sweep(Scene scene, boolean dark) {
        darkModeActive = dark;
        if (scene == null || scene.getRoot() == null) return;
        sweepNode(scene.getRoot(), dark);
    }

    /**
     * Node-based overload of {@link #sweep(Scene, boolean)} — usable
     * directly on a {@code DialogPane} (which is itself a {@code Node},
     * with its own independent {@code getStylesheets()} list) without
     * needing its {@code Scene}, which for {@code Dialog}/{@code Alert}
     * isn't reliably available until the dialog is actually shown.
     */
    public static void sweep(Node root, boolean dark) {
        if (root == null) return;
        sweepNode(root, dark);
    }

    /**
     * Applies the currently-active theme to a dialog's own stylesheets and
     * scene graph.
     *
     * <p>FIX (known gap, now closed for dialogs that call this): every
     * dialog in this app (Setup Assistant, Performance Report, the
     * documentation viewer, etc.) gets its own separate {@code Scene} or
     * {@code DialogPane} — {@link #sweep(Scene, boolean)} only ever swept
     * the MAIN window's scene graph, so a dialog opened while dark mode was
     * active still rendered in light-mode colors regardless of the toggle.
     * Call this once, right after building a dialog's content and before
     * (or immediately after) showing it, to give it the same treatment as
     * the main window. No-ops harmlessly if light mode is active — nothing
     * needs to change for a dialog that's already in its native light
     * styling.</p>
     *
     * @param pane        the dialog's DialogPane (for Dialog/Alert), or null if not applicable
     * @param sceneOrNull the dialog's own Scene (for a raw Stage+Scene dialog), or null if not applicable
     */
    public static void applyCurrentThemeToDialog(javafx.scene.control.DialogPane pane, Scene sceneOrNull) {
        if (!darkModeActive) return;

        String darkCssUrl = resolveDarkCssUrl();
        if (darkCssUrl == null) return;

        if (pane != null) {
            if (!pane.getStylesheets().contains(darkCssUrl)) {
                pane.getStylesheets().add(darkCssUrl);
            }
            sweepNode(pane, true);
        }
        if (sceneOrNull != null) {
            if (!sceneOrNull.getStylesheets().contains(darkCssUrl)) {
                sceneOrNull.getStylesheets().add(darkCssUrl);
            }
            sweepNode(sceneOrNull.getRoot(), true);
        }
    }

    private static String resolveDarkCssUrl() {
        var resource = ThemeManager.class.getResource("/styles/dark.css");
        return resource != null ? resource.toExternalForm() : null;
    }

    private static void sweepNode(Node node, boolean dark) {
        if (dark) {
            stripForDarkMode(node);
        } else {
            restoreForLightMode(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                sweepNode(child, dark);
            }
        }
    }

    /**
     * Strip inline color rules from a single node, stashing its original
     * style first if this is the first time it's been stripped. Safe to
     * call on a node that's already been stripped (no-ops on the color
     * portion, since there's nothing left to strip) — and safe to call on
     * a node with no inline style at all.
     *
     * <p>Dynamic UI creation code (new queue rows, dialogs) should call
     * this on any node it just gave an inline color style to, but only
     * when {@link #isDarkModeActive()} — no need to pay the stash/strip
     * cost while in light mode, since light mode is the style's own
     * native state.</p>
     */
    public static void stripForCurrentTheme(Node node) {
        if (darkModeActive) {
            stripForDarkMode(node);
        }
    }

    private static void stripForDarkMode(Node node) {
        String style = node.getStyle();
        if (style == null || style.isBlank()) return;

        // Already stashed (e.g. re-toggling dark->dark via a redundant call,
        // or this node was already handled) — don't stash a
        // partially-stripped style as if it were the "original".
        if (!node.getProperties().containsKey(STASH_KEY)) {
            node.getProperties().put(STASH_KEY, style);
        }

        String stripped = COLOR_RULE_PATTERN.matcher(style).replaceAll("").trim();
        try {
            node.setStyle(stripped);
        } catch (RuntimeException e) {
            // FIX (root cause of most "half-themed" symptoms across this
            // app): some internal control skin nodes (observed: MenuBar's
            // internal MenuBarButton) bind their own `style` property.
            // Calling setStyle() on a bound property throws
            // "X.style : A bound value cannot be set." — confirmed from a
            // real production log. This was previously UNCAUGHT, which
            // aborted this entire recursive sweep the instant it hit such
            // a node, silently leaving every node not yet visited (often
            // most of the actual window content, since MenuBar sits early
            // in traversal order under BorderPane.setTop()) in its
            // previous, un-themed state. One unstylable node must never be
            // allowed to stop the rest of the tree from being themed.
            LOGGER.debug("Skipping style strip on {} — style property is bound: {}",
                    node.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static void restoreForLightMode(Node node) {
        Object original = node.getProperties().remove(STASH_KEY);
        if (original instanceof String originalStyle) {
            try {
                node.setStyle(originalStyle);
            } catch (RuntimeException e) {
                // Same bound-property issue in the opposite direction —
                // see stripForDarkMode() above.
                LOGGER.debug("Skipping style restore on {} — style property is bound: {}",
                        node.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}