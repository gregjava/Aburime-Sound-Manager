/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager;

import audiomanager.core.ModelManager;
import audiomanager.ui.MainWindow;
import audiomanager.util.PreferenceManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Studio Audio Manager Application v3.9
 * 
 * A comprehensive audio processing and transcription tool with:
 * - Audio format conversion (MP3, WAV, FLAC, OGG) via FFmpeg
 * - Audio transcription with OpenAI Whisper CLI
 * - Batch processing with configurable parallel execution
 * - Audio splitting tool (2-10 equal parts)
 * - Text file combiner for transcription merging
 * - Real-time progress tracking and time estimation
 * - Noise reduction and volume boost filters
 * - SRT timestamp generation for subtitles
 * - 750MB file size limit with validation
 * - Clean architecture with separated concerns
 * 
 * Main entry point and JavaFX application launcher.
 * 
 * @author Aburime Sound Manager by GregJava 
 * @date Sep 8 - Oct 11, 2025
 * @version 0.3.9
 */
public class Studio extends Application {
    // Initialize model manager before anything else
    ModelManager modelManager;
    private static final Logger LOGGER = LoggerFactory.getLogger(Studio.class);
    
    private PreferenceManager prefManager;
    private MainWindow mainWindow;

    /**
     * Application initialization - called before start()
     * @throws java.lang.Exception Error object returned in the event of process failure
     */
    @Override
    public void init() throws Exception {
        super.init();
        this.modelManager = new ModelManager();
        
        // Verify WhisperX installation during app startup
        boolean modelsAvailable = verifyWhisperXInstallation();
        
        if (!modelsAvailable) {
            LOGGER.warn("WhisperX models not available at startup. They will be downloaded on first use.");
        } else {
            LOGGER.info("WhisperX models verified and ready at startup");
        }
        
        LOGGER.info("Initializing Studio Audio Manager v3.9");
        
        // Configure logging if needed
        configureLogging();
    }

    /**
     * Main application start method
     * @param primaryStage The Stage object used to start the application.
     */
    @Override
    public void start(Stage primaryStage) {
        try {
            LOGGER.info("Starting application...");
            
            // Initialize preference manager
            prefManager = new PreferenceManager(Studio.class);
            LOGGER.debug("Preference manager initialized");
            
            // Create and initialize main window
            mainWindow = new MainWindow(primaryStage, prefManager);
            mainWindow.initialize();
            
            LOGGER.info("Application started successfully");
        
            // Show model status in UI if needed
            Platform.runLater(() -> {
                // Update UI to show model status
                showModelStatusInUI();
            });
            
        } catch (Exception e) {
            LOGGER.error("Fatal error during application startup", e);
            showErrorAndExit("Startup Error", 
                           "Failed to initialize application", 
                           e);
        }
    }
    
    private boolean verifyWhisperXInstallation() {
        try {
            LOGGER.info("Verifying WhisperX installation...");
            return modelManager.verifyInstallation();
        } catch (Exception e) {
            LOGGER.error("Failed to verify WhisperX installation: {}", e.getMessage());
            return false;
        }
    }
    
    private void showModelStatusInUI() {
        // Update your UI to show model readiness status
        // This could be a status bar, tooltip, or initial dialog
    }

    /**
     * Application shutdown - called when application is closing
     */
    @Override
    public void stop() {
        LOGGER.info("Application shutdown initiated");
        
        try {
            // Save preferences
            if (prefManager != null) {
                prefManager.flush();
                LOGGER.debug("Preferences saved");
            }
            
            LOGGER.info("Application shutdown complete");
            // Cleanup if needed
            if (modelManager != null) {
                // Any cleanup logic
            }
            super.stop();
        } catch (Exception e) {
            LOGGER.error("Error during application shutdown", e);
        }
    }

    /**
     * Configure logging system
     */
    private void configureLogging() {
        // Set up basic logging if SLF4J implementation is missing
        String logLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");
        if (logLevel == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "INFO");
        }

        // Ensure basic logging works even without SLF4J implementation
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");

        LOGGER.debug("Logging configured");
    }

    /**
     * Show error dialog and exit application
     */
    private void showErrorAndExit(String title, String message, Exception e) {
        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(message);
            
            // Create expandable exception details
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String exceptionText = sw.toString();
            
            alert.setContentText(e.getMessage() + "\n\nSee details for full stack trace.");
            
            // Create expandable content
            javafx.scene.control.TextArea textArea = new javafx.scene.control.TextArea(exceptionText);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);
            
            javafx.scene.layout.GridPane.setVgrow(textArea, javafx.scene.layout.Priority.ALWAYS);
            javafx.scene.layout.GridPane.setHgrow(textArea, javafx.scene.layout.Priority.ALWAYS);
            
            javafx.scene.layout.GridPane expContent = new javafx.scene.layout.GridPane();
            expContent.setMaxWidth(Double.MAX_VALUE);
            expContent.add(new javafx.scene.control.Label("Exception details:"), 0, 0);
            expContent.add(textArea, 0, 1);
            
            alert.getDialogPane().setExpandableContent(expContent);
            alert.showAndWait();
            
            Platform.exit();
            System.exit(1);
        });
    }

    /**
     * Main entry point
     * 
     * @param args Command line arguments (currently unused)
     */
    public static void main(String[] args) {
        LOGGER.info("=================================================");
        LOGGER.info("  Studio Audio Manager v3.9");
        LOGGER.info("  Convert, Clean & Split Audio");
        LOGGER.info("=================================================");
        
        // Set system properties for better performance
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.text", "t2k");
        
        // Check Java version
        String javaVersion = System.getProperty("java.version");
        LOGGER.info("Java Version: {}", javaVersion);
        LOGGER.info("OS: {} {}", System.getProperty("os.name"), System.getProperty("os.version"));
        LOGGER.info("User Home: {}", System.getProperty("user.home"));
        
        try {
            // Launch JavaFX application
            launch(args);
        } catch (Exception e) {
            LOGGER.error("Fatal error in main method", e);
            System.exit(1);
        }
        
        LOGGER.info("Application terminated");
    }
}