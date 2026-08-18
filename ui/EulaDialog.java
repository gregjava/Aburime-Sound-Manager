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
 */
public class EulaDialog {

    private static final String EULA_RESOURCE_PATH = "/eula.txt";
    private static final int CURRENT_EULA_VERSION = 1;

    private final Stage stage;
    private boolean accepted = false;

    public EulaDialog() {
        this.stage = new Stage();
        this.stage.initStyle(StageStyle.UTILITY);
        this.stage.initModality(Modality.APPLICATION_MODAL);
        this.stage.setTitle("End User License Agreement");
        this.stage.setMinWidth(600);
        this.stage.setMinHeight(500);
    }

    public boolean showAndWait() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #f5f5f5;");

        // Header
        Label header = new Label("📄 End User License Agreement");
        header.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        Label subHeader = new Label(
            "Please read the following terms carefully before using AudioManager."
        );
        subHeader.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        // EULA Content
        TextArea eulaText = new TextArea(loadEulaText());
        eulaText.setEditable(false);
        eulaText.setWrapText(true);
        eulaText.setPrefHeight(350);
        eulaText.setMaxHeight(350);
        eulaText.setStyle("-fx-font-family: 'Segoe UI', 'Arial', sans-serif; -fx-font-size: 12px;");

        // Agreement checkbox
        CheckBox agreeCheckBox = new CheckBox("I have read and agree to the terms and conditions");
        agreeCheckBox.setStyle("-fx-font-size: 13px;");
        agreeCheckBox.setDisable(true);

        // Buttons
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        Button acceptButton = new Button("Accept");
        acceptButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        acceptButton.setPrefWidth(100);
        acceptButton.setDisable(true);

        Button declineButton = new Button("Decline");
        declineButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");
        declineButton.setPrefWidth(100);

        buttonBox.getChildren().addAll(declineButton, acceptButton);

        // Enable accept when checkbox is checked
        agreeCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            acceptButton.setDisable(!newVal);
        });

        // Scroll to bottom detection to enable checkbox
        eulaText.scrollTopProperty().addListener((obs, oldVal, newVal) -> {
            if (eulaText.getLength() > 0) {
                double scrollTop = eulaText.getScrollTop();
                // Estimate content height based on text length and font size
                double approxContentHeight = Math.max(350, eulaText.getLength() * 0.05);
                double viewportHeight = 350;
                if (scrollTop + viewportHeight >= approxContentHeight - 20) {
                    agreeCheckBox.setDisable(false);
                }
            }
        });

        // Also enable checkbox after a short delay if the user has scrolled manually
        eulaText.textProperty().addListener((obs, oldVal, newVal) -> {
            if (eulaText.getLength() < 1000) {
                agreeCheckBox.setDisable(false);
            }
        });

        // Actions
        acceptButton.setOnAction(e -> {
            accepted = true;
            stage.close();
        });

        declineButton.setOnAction(e -> {
            accepted = false;
            stage.close();
        });

        // Add a "Scroll to bottom to enable" indicator
        Label scrollHint = new Label("⬇️ Please scroll to the bottom to accept");
        scrollHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");
        
        // Hide the hint when checkbox is enabled
        agreeCheckBox.disableProperty().addListener((obs, oldVal, newVal) -> {
            scrollHint.setVisible(newVal);
        });

        root.getChildren().addAll(header, subHeader, eulaText, agreeCheckBox, scrollHint, buttonBox);

        Scene scene = new Scene(root);
        stage.setScene(scene);
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

            Version 1.0

            1. GRANT OF LICENSE
            AudioManager is provided free of charge for personal and commercial use.
            You may use, copy, and distribute the software subject to the terms below.

            2. RESTRICTIONS
            You may not:
            - Sell, rent, or lease the software
            - Modify, reverse engineer, or decompile the software
            - Remove any copyright or proprietary notices

            3. DISCLAIMER OF WARRANTY
            THE SOFTWARE IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND.

            4. LIMITATION OF LIABILITY
            IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY DAMAGES.

            5. THIRD-PARTY COMPONENTS
            This software uses:
            - FFmpeg (LGPL)
            - WhisperX (MIT)
            - JavaFX (GPL with Classpath Exception)
            - Apache Log4j/SLF4J (Apache 2.0)

            6. DATA COLLECTION
            By default, no data is collected. Optional error reporting can be enabled
            in Preferences and is subject to your consent.

            7. GOVERNING LAW
            This agreement shall be governed by the laws of the jurisdiction
            where the user resides.

            8. CONTACT
            For questions, contact: support@audiomanager.app

            _________________________________________________
            By clicking Accept, you agree to these terms.
            """;
    }
}