/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.util.PreferenceManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * End User License Agreement dialog.
 * Shown on first run and when the EULA has been updated.
 * 
 * <p><b>Version Management:</b></p>
 * <ul>
 *   <li><b>Version 1.0:</b> Initial EULA (legacy)</li>
 *   <li><b>Version 2.0:</b> Added privacy disclosure, updated third-party components</li>
 * </ul>
 * 
 * <p>When the EULA version is incremented, users will be prompted to accept
 * the new terms on their next launch.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see PreferenceManager
 */
public class EulaDialog {

    private static final String EULA_RESOURCE_PATH = "/eula.txt";
    
    /**
     * Current EULA version. Increment this when the EULA text changes
     * to force users to re-accept the agreement.
     */
    private static final int CURRENT_EULA_VERSION = 2;

    private final Stage stage;
    private boolean accepted = false;
    private boolean declined = false;

    public EulaDialog() {
        this.stage = new Stage();
        this.stage.initStyle(StageStyle.UTILITY);
        this.stage.initModality(Modality.APPLICATION_MODAL);
        this.stage.setTitle("End User License Agreement");
        this.stage.setMinWidth(650);
        this.stage.setMinHeight(550);
        this.stage.setResizable(true);
    }

    public boolean showAndWait() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #f8f9fa;");
        root.getStyleClass().add("theme-fix-surface-alt");

        // ===== Header =====
        VBox headerBox = new VBox(8);
        headerBox.setPadding(new Insets(0, 0, 5, 0));

        Label header = new Label("📄 End User License Agreement");
        header.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        header.getStyleClass().add("panel-heading");

        Label versionLabel = new Label("Version " + CURRENT_EULA_VERSION + ".0");
        versionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #7f8c8d;");

        Label subHeader = new Label(
            "Please read the following terms carefully before using AudioManager."
        );
        subHeader.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");

        headerBox.getChildren().addAll(header, versionLabel, subHeader);

        // ===== EULA Content =====
        TextArea eulaText = new TextArea(loadEulaText());
        eulaText.setEditable(false);
        eulaText.setWrapText(true);
        eulaText.setPrefHeight(400);
        eulaText.setMaxHeight(400);
        eulaText.setStyle(
            "-fx-font-family: 'Segoe UI', 'Arial', sans-serif; " +
            "-fx-font-size: 12px; " +
            "-fx-background-color: white; " +
            "-fx-border-color: #d0d0d0; " +
            "-fx-border-radius: 4; " +
            "-fx-background-radius: 4;"
        );
        eulaText.getStyleClass().add("theme-fix-surface");

        // ===== Summary Box =====
        VBox summaryBox = new VBox(5);
        summaryBox.setPadding(new Insets(8, 12, 8, 12));
        summaryBox.setStyle(
            "-fx-background-color: #e8f4fd; " +
            "-fx-border-color: #b8d4e8; " +
            "-fx-border-radius: 4; " +
            "-fx-background-radius: 4;"
        );
        summaryBox.getStyleClass().add("theme-fix-surface-alt");

        Label summaryTitle = new Label("🔑 Key Points:");
        summaryTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #0c5460;");

        Label summary1 = new Label("• Audio processing runs 100% offline — your data never leaves your machine");
        summary1.setStyle("-fx-font-size: 11px; -fx-text-fill: #0c5460;");
        Label summary2 = new Label("• Optional features (diarization, translation) require explicit user action");
        summary2.setStyle("-fx-font-size: 11px; -fx-text-fill: #0c5460;");
        Label summary3 = new Label("• Error reporting is opt-in and anonymized");
        summary3.setStyle("-fx-font-size: 11px; -fx-text-fill: #0c5460;");

        summaryBox.getChildren().addAll(summaryTitle, summary1, summary2, summary3);

        // ===== Agreement checkbox =====
        CheckBox agreeCheckBox = new CheckBox("I have read and agree to the terms and conditions");
        agreeCheckBox.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        agreeCheckBox.setDisable(true);

        // ===== Buttons =====
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(5, 0, 0, 0));

        Button acceptButton = new Button("✅ Accept");
        acceptButton.setStyle(
            "-fx-background-color: #4CAF50; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: bold; " +
            "-fx-padding: 10 30; " +
            "-fx-background-radius: 4;"
        );
        acceptButton.getStyleClass().add("primary-button");
        acceptButton.setPrefWidth(120);
        acceptButton.setDisable(true);

        Button declineButton = new Button("❌ Decline");
        declineButton.setStyle(
            "-fx-background-color: #f44336; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: bold; " +
            "-fx-padding: 10 30; " +
            "-fx-background-radius: 4;"
        );
        declineButton.getStyleClass().add("action-btn-danger");
        declineButton.setPrefWidth(120);

        buttonBox.getChildren().addAll(declineButton, acceptButton);

        // ===== Enable accept when checkbox is checked =====
        agreeCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            acceptButton.setDisable(!newVal);
        });

        // ===== Scroll to bottom detection to enable checkbox =====
        eulaText.scrollTopProperty().addListener((obs, oldVal, newVal) -> {
            if (eulaText.getLength() > 0) {
                double scrollTop = eulaText.getScrollTop();
                double contentHeight = eulaText.getLength() * 0.045; // Approximate
                double viewportHeight = 400;
                if (scrollTop + viewportHeight >= contentHeight - 50) {
                    agreeCheckBox.setDisable(false);
                }
            }
        });

        // ===== Also enable checkbox after a short delay if the user has scrolled manually =====
        eulaText.textProperty().addListener((obs, oldVal, newVal) -> {
            if (eulaText.getLength() < 1000) {
                agreeCheckBox.setDisable(false);
            }
        });

        // ===== Actions =====
        acceptButton.setOnAction(e -> {
            accepted = true;
            declined = false;
            stage.close();
        });

        declineButton.setOnAction(e -> {
            accepted = false;
            declined = true;
            stage.close();
        });

        // ===== Scroll hint =====
        Label scrollHint = new Label("⬇️ Please scroll to the bottom to accept");
        scrollHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");
        
        // Hide the hint when checkbox is enabled
        agreeCheckBox.disableProperty().addListener((obs, oldVal, newVal) -> {
            scrollHint.setVisible(newVal);
        });

        // ===== Build layout =====
        root.getChildren().addAll(
            headerBox,
            eulaText,
            summaryBox,
            agreeCheckBox,
            scrollHint,
            buttonBox
        );

        Scene scene = new Scene(root);
        stage.setScene(scene);
        
        // Apply theme
        ThemeManager.applyCurrentThemeToDialog(null, scene);
        
        stage.showAndWait();

        return accepted;
    }

    /**
     * Checks if the EULA has been accepted by the user.
     * Uses PreferenceManager's convenience methods for clean, readable code.
     * 
     * @param prefManager The PreferenceManager instance
     * @return true if the EULA has been accepted at the current version
     */
    public static boolean isEulaAccepted(PreferenceManager prefManager) {
        return prefManager.getEulaAcceptedVersion() >= CURRENT_EULA_VERSION;
    }

    /**
     * Marks the EULA as accepted by the user at the current version.
     * Uses PreferenceManager's convenience methods for clean, readable code.
     * 
     * @param prefManager The PreferenceManager instance
     */
    public static void markEulaAccepted(PreferenceManager prefManager) {
        prefManager.setEulaAcceptedVersion(CURRENT_EULA_VERSION);
        prefManager.flush();
    }

    /**
     * Returns whether the user declined the EULA.
     *
     * @return {@code true} if the user declined
     */
    public boolean isDeclined() {
        return declined;
    }

    /**
     * Loads the EULA text from the resources folder.
     * Falls back to default embedded text if the resource is not found.
     * 
     * @return The EULA text content
     */
    private String loadEulaText() {
        try (InputStream stream = getClass().getResourceAsStream(EULA_RESOURCE_PATH)) {
            if (stream != null) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // Fallback to embedded EULA
        }
        return getDefaultEulaText();
    }

    /**
     * Returns the default EULA text embedded in the code.
     * Used as a fallback when the resource file cannot be loaded.
     * 
     * @return The default EULA text
     */
    private String getDefaultEulaText() {
        return """
            END USER LICENSE AGREEMENT

            Version 2.0

            1. GRANT OF LICENSE
            ------------------------
            Aburime Sound Manager v4.0.0 ("Phoenix") is provided free of charge for personal and commercial use.
            You may use, copy, and distribute the software subject to the terms below.

            2. RESTRICTIONS
            ------------------------
            You may not:
            - Sell, rent, or lease the software
            - Modify, reverse engineer, or decompile the software
            - Remove any copyright or proprietary notices

            3. DISCLAIMER OF WARRANTY
            ------------------------
            THE SOFTWARE IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND,
            EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
            OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND
            NONINFRINGEMENT.

            4. LIMITATION OF LIABILITY
            ------------------------
            IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
            ANY CLAIM, DAMAGES, OR OTHER LIABILITY, WHETHER IN AN ACTION OF
            CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF, OR IN
            CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.

            5. THIRD-PARTY COMPONENTS
            ------------------------
            This software uses the following third-party components:

            | Component      | License                              |
            |----------------|--------------------------------------|
            | FFmpeg         | LGPL 2.1                             |
            | WhisperX       | MIT                                  |
            | JavaFX         | GPL with Classpath Exception         |
            | SLF4J          | MIT                                  |
            | Gson           | Apache 2.0                           |
            | PyTorch        | BSD-style                            |
            | HuggingFace Hub| Apache 2.0                           |

            6. DATA COLLECTION & PRIVACY
            ------------------------
            By default, no data is collected. Optional features that may send data:

            • Speaker Diarization: Requires a HuggingFace token; sends requests to HuggingFace
            • Error Reporting: Optional, anonymized, requires explicit consent
            • Translation: User-configured endpoint; no data sent unless configured

            Audio processing and transcription run entirely on this computer.
            Your audio files and transcripts are never sent anywhere for these steps.
            Any optional network features are clearly labeled and require explicit user action.

            7. GOVERNING LAW
            ------------------------
            This agreement shall be governed by the laws of the jurisdiction
            where the user resides, without regard to its conflict of law provisions.

            8. CONTACT
            ------------------------
            For questions, contact: support@audiomanager.app

            _________________________________________________
            By clicking Accept, you agree to these terms.
            """;
    }
}