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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    // FIX: other classes that actually shell out to ffmpeg/whisperx
    // (AudioProcessor, WhisperXTranscriptionService) don't hold a
    // reference to this Application instance — JavaFX only ever
    // constructs one, via launch(), so a simple static accessor is the
    // lowest-risk way to make the resolved bundled paths reachable from
    // anywhere, rather than threading a new constructor parameter through
    // the whole MainWindow -> BatchProcessor -> ParallelProcessingManager
    // -> AudioProcessor/WhisperXTranscriptionService chain. Deliberately
    // null before init() runs (and always null in unit tests, which never
    // call Application.launch()) — every caller must null-check and fall
    // back to the pre-bundling behavior, never assume this is set.
    private static volatile Studio instance;

    public static Studio getInstance() {
        return instance;
    }

    private PreferenceManager prefManager;
    private MainWindow mainWindow;
    
    // Bundled resource paths
    private String appDir;
    private String ffmpegPath;
    private String whisperPython;
    private String whisperEnv;

    /**
     * Application initialization - called before start()
     * @throws java.lang.Exception Error object returned in the event of process failure
     */
    @Override
    public void init() throws Exception {
        super.init();

        instance = this;
        
        // Initialize bundled resource paths
        initializeBundledPaths();
        
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
    
    /**
     * Initialize paths for bundled resources (FFmpeg, WhisperX, etc.)
     * Reads system properties set by launcher or falls back to relative paths
     */
    private void initializeBundledPaths() {
        // Read system properties set by launcher, or use defaults
        appDir = System.getProperty("app.dir", ".");
        ffmpegPath = System.getProperty("ffmpeg.path", "ffmpeg");
        whisperPython = System.getProperty("whisper.python", "python");
        whisperEnv = System.getProperty("whisper.env", ".");
        
        LOGGER.info("Application directory: {}", appDir);
        LOGGER.info("FFmpeg path: {}", ffmpegPath);
        LOGGER.info("Whisper Python: {}", whisperPython);
        LOGGER.info("Whisper environment: {}", whisperEnv);
        
        // Validate and resolve paths
        validateBundledPaths();
    }
    
    /**
     * Validate bundled resource paths and fallback to alternatives if needed
     */
    private void validateBundledPaths() {
        // Validate FFmpeg
        Path ffmpeg = Paths.get(ffmpegPath);
        if (!Files.exists(ffmpeg)) {
            LOGGER.warn("FFmpeg not found at configured path: {}", ffmpegPath);
            // Try fallback to bundled location
            Path bundledFFmpeg = getAppDirectory().resolve("ffmpeg").resolve("ffmpeg.exe");
            if (Files.exists(bundledFFmpeg)) {
                ffmpegPath = bundledFFmpeg.toString();
                LOGGER.info("Using bundled FFmpeg: {}", ffmpegPath);
            } else {
                // Try system PATH as last resort
                ffmpegPath = "ffmpeg";
                LOGGER.info("Using system FFmpeg (PATH): {}", ffmpegPath);
            }
        } else {
            LOGGER.info("FFmpeg found at: {}", ffmpegPath);
        }
        
        // Validate Whisper Python
        Path python = Paths.get(whisperPython);
        if (!Files.exists(python)) {
            LOGGER.warn("Whisper Python not found at configured path: {}", whisperPython);
            // Try fallback to bundled location
            Path bundledPython = getAppDirectory().resolve("whisper_env").resolve("Scripts").resolve("python.exe");
            if (Files.exists(bundledPython)) {
                whisperPython = bundledPython.toString();
                whisperEnv = getAppDirectory().resolve("whisper_env").toString();
                LOGGER.info("Using bundled Whisper Python: {}", whisperPython);
                LOGGER.info("Using bundled Whisper environment: {}", whisperEnv);
            } else {
                // Try system Python as last resort
                whisperPython = "python";
                LOGGER.info("Using system Python: {}", whisperPython);
            }
        } else {
            LOGGER.info("Whisper Python found at: {}", whisperPython);
        }
        
        // Validate Whisper environment directory
        Path envDir = Paths.get(whisperEnv);
        if (!Files.exists(envDir)) {
            LOGGER.warn("Whisper environment not found at: {}", whisperEnv);
            // Try to infer from python path
            Path pythonPath = Paths.get(whisperPython);
            if (pythonPath.getNameCount() > 3) {
                // Assuming python.exe is in Scripts directory
                Path inferredEnv = pythonPath.getParent().getParent();
                if (Files.exists(inferredEnv)) {
                    whisperEnv = inferredEnv.toString();
                    LOGGER.info("Inferred Whisper environment: {}", whisperEnv);
                }
            }
        } else {
            LOGGER.info("Whisper environment found at: {}", whisperEnv);
        }
    }
    
    /**
     * Get the application installation directory
     * @return Path to the application root directory
     */
    public Path getAppDirectory() {
        try {
            String path = Studio.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            // Navigate up from app/AburimeSoundManager.jar to root directory
            Path jarPath = Paths.get(path);
            if (jarPath.getNameCount() > 1) {
                return jarPath.getParent().getParent();
            }
            return jarPath.getParent();
        } catch (URISyntaxException e) {
            LOGGER.warn("Could not determine application directory, using current directory", e);
            return Paths.get(".");
        }
    }
    
    /**
     * Get the path to FFmpeg executable
     * @return Path to FFmpeg
     */
    public Path getFFmpegPath() {
        return Paths.get(ffmpegPath);
    }
    
    /**
     * Get the path to Whisper Python executable
     * @return Path to Python executable
     */
    public Path getWhisperPython() {
        return Paths.get(whisperPython);
    }
    
    /**
     * Get the Whisper environment directory
     * @return Path to Whisper environment
     */
    public Path getWhisperEnv() {
        return Paths.get(whisperEnv);
    }
    
    /**
     * Create a ProcessBuilder for running FFmpeg with bundled path
     * @param args Command line arguments for FFmpeg
     * @return Configured ProcessBuilder
     */
    public ProcessBuilder createFFmpegProcess(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(ffmpegPath);
        command.addAll(java.util.Arrays.asList(args));
        
        LOGGER.debug("FFmpeg command: {}", String.join(" ", command));
        return new ProcessBuilder(command);
    }
    
    /**
     * Create a ProcessBuilder for running WhisperX with bundled Python
     * @param args Command line arguments for WhisperX
     * @return Configured ProcessBuilder
     */
    public ProcessBuilder createWhisperProcess(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(whisperPython);
        command.add("-m");
        command.add("whisperx");
        command.addAll(java.util.Arrays.asList(args));
        
        LOGGER.debug("Whisper command: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        
        // Set environment to use bundled Whisper environment
        Path envPath = Paths.get(whisperEnv);
        if (Files.exists(envPath)) {
            // Add Scripts directory to PATH for DLL dependencies
            String pathEnv = System.getenv("PATH");
            String scriptsDir = envPath.resolve("Scripts").toString();
            String libDir = envPath.resolve("Lib").toString();
            
            pb.environment().put("PATH", 
                scriptsDir + 
                java.io.File.pathSeparator + 
                libDir + 
                java.io.File.pathSeparator + 
                (pathEnv != null ? pathEnv : ""));
            
            // Set Python-specific environment variables
            pb.environment().put("PYTHONHOME", envPath.toString());
            pb.environment().put("PYTHONPATH", 
                envPath.resolve("Lib").toString() + 
                java.io.File.pathSeparator + 
                envPath.resolve("Lib").resolve("site-packages").toString());
            
            // Set working directory to whisper_env
            pb.directory(envPath.toFile());
        }
        
        return pb;
    }
    
    /**
     * Check if FFmpeg is available
     * @return true if FFmpeg is available
     */
    public boolean isFFmpegAvailable() {
        Path ffmpeg = Paths.get(ffmpegPath);
        return Files.exists(ffmpeg) && Files.isExecutable(ffmpeg);
    }
    
    /**
     * Check if WhisperX is available
     * @return true if WhisperX is available
     */
    public boolean isWhisperAvailable() {
        Path python = Paths.get(whisperPython);
        return Files.exists(python) && Files.isExecutable(python);
    }

    /** The resolved ffmpeg path (bundled if found, else the system-PATH fallback token "ffmpeg") — see validateBundledPaths(). */
    public String getFfmpegPath() {
        return ffmpegPath;
    }

    /** The resolved WhisperX Python path (bundled venv if found, else the system-PATH fallback token "python") — see validateBundledPaths(). */
    public String getWhisperPythonPath() {
        return whisperPython;
    }

    /** The resolved bundled Whisper virtual environment root, if one was found — see validateBundledPaths(). */
    public String getWhisperEnvPath() {
        return whisperEnv;
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