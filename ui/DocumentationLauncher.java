/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Opens a bundled Markdown doc (USER_MANUAL.md / TROUBLESHOOTING.md,
 * expected on the classpath under {@code /docs/}) in the system's default
 * handler for .md files, falling back to a plain-text dialog if no
 * association exists or the file can't be located.
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
                        "Make sure it's included under src/main/resources/docs/.");
                return;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();

            // Try the OS default handler first (nicer rendering if the user has a Markdown viewer).
            File tempFile = File.createTempFile(resourceName.replace(".md", "_"), ".md");
            tempFile.deleteOnExit();
            Files.writeString(tempFile.toPath(), content, StandardCharsets.UTF_8);
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(tempFile);
                return;
            }
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
        area.setPrefSize(700, 600);
        Scene dialogScene = new Scene(new StackPane(area));
        dialog.setScene(dialogScene);
        dialog.show();
    }
}