/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Opens a bundled Markdown doc (USER_MANUAL.md / TROUBLESHOOTING.md,
 * expected on the classpath under {@code /docs/}) in an in-app viewer,
 * with an optional button to also try opening it in the OS's default
 * handler for .md files.
 *
 * <p>FIX: this previously tried the OS default handler FIRST, inside the
 * same try block as the in-app fallback — so when {@code Desktop.open()}
 * threw (e.g. "Application not found", confirmed by a real screenshot: most
 * Windows installs have no default handler for .md files at all), that
 * exception was caught by the OUTER catch and showed a plain error alert.
 * The in-app fallback was only ever reached when {@code Desktop} wasn't
 * supported on the platform at all — never on the far more common case of
 * "Desktop is supported, but there's no app registered for this file
 * type." Reordered so the in-app view (which always works — it's just
 * rendering a string in a TextArea, no OS dependency) is the primary path,
 * and OS-opening is an optional secondary action the user can click if
 * they want, that can fail on its own without affecting the (already
 * showing) primary view.</p>
 *
 * <p>Extracted out of {@code MainWindow} as part of a general pass to pull
 * self-contained dialogs/utilities out of that file, which had grown past
 * 2,000 lines. Stateless.</p>
 */
public class DocumentationLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentationLauncher.class);

    public void open(String resourceName) {
        try {
            InputStream in = getClass().getResourceAsStream("/docs/" + resourceName);
            if (in == null) {
                showInfoAlert("Documentation not found",
                        resourceName + " is missing from the bundled resources (/docs/" + resourceName + "). " +
                        "Make sure it's included under src/docs/ (this project's source root is src/, " +
                        "not src/main/resources/ — see project.properties' src.dir).");
                return;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();

            // The in-app view is always shown — it has no dependency on OS
            // file associations, so it can't fail the way Desktop.open() can.
            showLongTextDialog(resourceName, content);
        } catch (Exception e) {
            LOGGER.warn("Could not open {}: {}", resourceName, e.getMessage());
            showInfoAlert("Could not open documentation", "See " + resourceName + " in the app's install directory, or " + e.getMessage());
        }
    }

    private void showInfoAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showLongTextDialog(String title, String content) {
        Stage dialog = new Stage();
        dialog.setTitle(title);

        TextArea area = new TextArea(content);
        area.setEditable(false);
        area.setWrapText(true);
        area.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace;");

        javafx.scene.control.Button openExternalButton = new javafx.scene.control.Button("Open in External App");
        openExternalButton.setOnAction(e -> {
            // Optional, best-effort — a failure here just shows a small
            // inline notice next to the button; the already-visible in-app
            // view above is completely unaffected either way.
            try {
                File tempFile = File.createTempFile(title.replace(".md", "_"), ".md");
                tempFile.deleteOnExit();
                Files.writeString(tempFile.toPath(), content, StandardCharsets.UTF_8);
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(tempFile);
                } else {
                    showInfoAlert("Not supported", "This platform has no default handler support available.");
                }
            } catch (Exception ex) {
                LOGGER.debug("External open failed (expected on systems with no .md file association): {}", ex.getMessage());
                showInfoAlert("Could not open externally",
                        "No application is associated with .md files on this system. " +
                        "The document is already viewable above — no action needed unless you specifically want an external editor.");
            }
        });

        javafx.scene.layout.HBox buttonBar = new javafx.scene.layout.HBox(10, openExternalButton);
        buttonBar.setPadding(new javafx.geometry.Insets(8));

        javafx.scene.layout.BorderPane layout = new javafx.scene.layout.BorderPane();
        layout.setCenter(area);
        layout.setBottom(buttonBar);

        Scene dialogScene = new Scene(layout, 750, 650);
        dialog.setScene(dialogScene);
        audiomanager.ui.ThemeManager.applyCurrentThemeToDialog(null, dialogScene);
        dialog.show();
    }
}