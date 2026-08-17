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

    // FIX (cross-platform bundling): every bundled-path guess below used to
    // be hardcoded to the Windows venv/executable layout (ffmpeg.exe,
    // <env>/Scripts/python.exe, <env>/Lib/site-packages). Bundling now
    // targets Windows, macOS, and Linux from the same code path -- the
    // only real difference between them is executable naming and venv
    // internal layout, both centralized here so the rest of this class
    // doesn't need its own per-OS branches.
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_MAC = OS_NAME.contains("mac") || OS_NAME.contains("darwin");
    // (Anything that's neither Windows nor Mac is treated as a Linux-style
    // venv layout below -- true for every mainstream Linux distribution,
    // and a reasonable default for other Unix-likes too.)

    /** Platform-appropriate executable filename for a bundled tool -- "ffmpeg.exe" on Windows, "ffmpeg" elsewhere. */
    private static String exeName(String baseName) {
        return IS_WINDOWS ? baseName + ".exe" : baseName;
    }

    /**
     * Platform-appropriate relative path from a Python venv root to its
     * interpreter -- Windows venvs put executables in {@code Scripts/},
     * every other platform's venv convention uses {@code bin/}.
     */
    private static Path venvPythonRelativePath() {
        return IS_WINDOWS ? Paths.get("Scripts", "python.exe") : Paths.get("bin", "python3");
    }

    /** Platform-appropriate relative path from a venv root to its executables directory (for PATH prepending). */
    private static Path venvBinRelativePath() {
        return IS_WINDOWS ? Paths.get("Scripts") : Paths.get("bin");
    }

    /**
     * Platform-appropriate relative path from a venv root to its
     * site-packages directory. Windows venvs use a fixed {@code Lib\site-packages}
     * regardless of Python version; macOS/Linux venvs nest it under a
     * version-specific {@code lib/pythonX.Y/} directory, so this scans for
     * that directory rather than guessing a specific version — a bundled
     * installer built against a different Python minor version than
     * whatever wrote this code would otherwise silently resolve to a
     * nonexistent path.
     */
    private static Path venvSitePackagesRelativePath(Path envRoot) {
        if (IS_WINDOWS) {
            return Paths.get("Lib", "site-packages");
        }
        Path libDir = envRoot.resolve("lib");
        if (Files.isDirectory(libDir)) {
            try (var stream = Files.list(libDir)) {
                Path pythonVersionDir = stream
                        .filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("python3"))
                        .findFirst()
                        .orElse(null);
                if (pythonVersionDir != null) {
                    return envRoot.relativize(pythonVersionDir.resolve("site-packages"));
                }
            } catch (java.io.IOException ignored) {
                // Fall through to the guessed default below.
            }
        }
        // Reasonable fallback if the venv doesn't exist yet or the scan
        // failed for some other reason -- won't resolve to a real
        // directory, but keeps this method's return type non-null and
        // lets the "does this exist" checks elsewhere fail gracefully
        // rather than this method throwing.
        return Paths.get("lib", "python3", "site-packages");
    }

    /**
     * Application initialization - called before start()
     * @throws java.lang.Exception Error object returned in the event of process failure
     */
    @Override
    public void init() throws Exception {
        super.init();

        instance = this;

        // FIX: initializeBundledPaths() (and the getAppDirectory() call it
        // can reach via validateBundledPaths()) previously ran here with no
        // surrounding try/catch at all. JavaFX's Application.init() has no
        // graceful-failure path of its own -- an exception thrown here
        // aborts the launch before any window (and before
        // showErrorAndExit()'s try/catch in start(), which never gets a
        // chance to run) ever appears, typically surfacing as just a raw
        // stack trace with no UI at all. That's an acceptable risk for a
        // dev/IDE run, but not for a distributed installer, where an
        // unusual classloading environment or an unexpected install layout
        // (see getAppDirectory()'s own hardening below) must degrade to
        // "use system PATH" instead of preventing the app from starting.
        try {
            initializeBundledPaths();
        } catch (Exception e) {
            LOGGER.warn("Bundled runtime path resolution failed ({}); falling back to system PATH for FFmpeg/Python.",
                    e.getMessage(), e);
            appDir = ".";
            ffmpegPath = "ffmpeg";
            whisperPython = "python";
            whisperEnv = null;
        }
        
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
        // FIX: "ffmpeg"/"python" (the defaults when no launcher system
        // property is set at all -- see initializeBundledPaths()) are
        // meant to be resolved via the OS's own PATH search by
        // ProcessBuilder, not as literal files relative to the working
        // directory -- Paths.get("ffmpeg") almost never exists as a real
        // file, so this used to log a WARN on every single normal,
        // unbundled run (dev/IDE runs, or Linux/macOS users relying on a
        // system install), which is misleading: nothing is actually wrong
        // in that case. Only warn when a launcher genuinely configured a
        // specific path and that path doesn't exist -- that's the case
        // actually worth flagging.
        boolean ffmpegPathWasExplicitlyConfigured = System.getProperty("ffmpeg.path") != null;
        Path ffmpeg = Paths.get(ffmpegPath);
        if (!Files.exists(ffmpeg)) {
            if (ffmpegPathWasExplicitlyConfigured) {
                LOGGER.warn("FFmpeg not found at configured path: {}", ffmpegPath);
            } else {
                LOGGER.debug("No bundled FFmpeg path configured; will resolve \"ffmpeg\" via system PATH.");
            }
            // Cross-platform: exeName() resolves to "ffmpeg.exe" on
            // Windows, plain "ffmpeg" everywhere else -- same bundled
            // "ffmpeg/" subdirectory convention on every OS.
            Path bundledFFmpeg = getAppDirectory().resolve("ffmpeg").resolve(exeName("ffmpeg"));
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
        
        // Validate Whisper Python -- same "only warn if explicitly
        // configured" reasoning as FFmpeg above.
        boolean pythonPathWasExplicitlyConfigured = System.getProperty("whisper.python") != null;
        Path python = Paths.get(whisperPython);
        if (!Files.exists(python)) {
            if (pythonPathWasExplicitlyConfigured) {
                LOGGER.warn("Whisper Python not found at configured path: {}", whisperPython);
            } else {
                LOGGER.debug("No bundled Whisper Python path configured; will resolve \"python\" via system PATH.");
            }
            // Cross-platform: venvPythonRelativePath() resolves to
            // "Scripts/python.exe" on Windows, "bin/python3" everywhere
            // else -- the standard venv layout on each platform.
            Path bundledPython = getAppDirectory().resolve("whisper_env").resolve(venvPythonRelativePath());
            if (Files.exists(bundledPython)) {
                whisperPython = bundledPython.toString();
                whisperEnv = getAppDirectory().resolve("whisper_env").toString();
                LOGGER.info("Using bundled Whisper Python: {}", whisperPython);
                LOGGER.info("Using bundled Whisper environment: {}", whisperEnv);
            } else {
                // Try system Python as last resort
                whisperPython = "python";
                LOGGER.info("Using system Python: {}", whisperPython);
                // FIX (root cause of the "Fatal Python error: init_fs_encoding
                // ... ModuleNotFoundError: No module named 'encodings'"
                // crash): whisperEnv was never reset here, so it stayed at
                // its "." default (see initializeBundledPaths()) -- and "."
                // (the current working directory) always exists, so every
                // later Files.exists(Paths.get(whisperEnv)) check in this
                // class trivially passed as if a real bundled venv had been
                // found. createWhisperProcess() then forced the WhisperX
                // subprocess's working directory to "." (this app's own
                // project folder) and prepended a bogus "./Scripts" (or
                // "./bin") to PATH -- exactly consistent with the crash
                // log's sys.path dump, which showed this app's own project
                // directory mixed in among the real Python installation's
                // paths. null is the genuine "no bundled environment"
                // sentinel now -- see createWhisperProcess()'s updated
                // guard below.
                whisperEnv = null;
            }
        } else {
            LOGGER.info("Whisper Python found at: {}", whisperPython);
        }
        
        // Validate Whisper environment directory -- skipped entirely when
        // whisperEnv is already null (the definitive "not bundled, using
        // system Python" signal set above); nothing to validate or infer
        // for an environment that was never claimed to exist in the first
        // place.
        if (whisperEnv != null) {
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
    }
    
    /**
     * Get the application installation directory
     * @return Path to the application root directory
     */
    private Path getAppDirectory() {
        try {
            // FIX (restored — this null-guard was lost in the same edit
            // that added the Windows leading-slash fix below, most likely
            // from working off a copy of this method predating it):
            // getCodeSource() can legitimately return null in some
            // classloading environments (certain custom/module
            // classloaders, some signed-jar or security-manager setups).
            // init() wraps its caller in a try/catch so this alone won't
            // crash startup either way, but every OTHER caller of this
            // method gets the same safe fallback this way too, instead of
            // needing its own defensive check.
            var codeSource = Studio.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                LOGGER.warn("Could not determine application directory (no code source available), using current directory");
                return Paths.get(".");
            }
            String path = codeSource.getLocation().toURI().getPath();
            // Fix Windows paths with leading slash — confirmed necessary
            // by real Windows deployment testing: a file: URI's getPath()
            // can return e.g. "/C:/Program Files/App/app.jar", which isn't
            // a valid Windows path until that leading slash is stripped.
            if (path.startsWith("/") && path.contains(":")) {
                path = path.substring(1);
            }
            Path jarPath = Paths.get(path);
            return jarPath.getParent();
        } catch (URISyntaxException | RuntimeException e) {
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
     * Get the Whisper environment directory.
     * @return Path to the bundled Whisper environment, or {@code null} if
     *         no bundled environment was found (system Python is being
     *         used instead — see {@link #getWhisperPythonPath()}).
     */
    public Path getWhisperEnv() {
        return whisperEnv != null ? Paths.get(whisperEnv) : null;
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
        
        // Set environment to use bundled Whisper environment -- FIX (this
        // is the actual root cause of the "Fatal Python error:
        // init_fs_encoding ... No module named 'encodings'" crash):
        // whisperEnv used to always be a non-null String (defaulting to
        // "."), so Paths.get(whisperEnv) + Files.exists(...) below always
        // found *something* -- "." (the current working directory) always
        // exists -- even when no bundled environment had actually been
        // found. That silently forced this subprocess's working directory
        // to this app's own project folder and prepended a nonexistent
        // "./Scripts" (or "./bin") to PATH on every single non-bundled run,
        // for no reason. whisperEnv is now null exactly when no real
        // bundled environment exists (see validateBundledPaths()), so this
        // block now only runs for a genuinely resolved bundled venv.
        if (whisperEnv != null) {
            Path envPath = Paths.get(whisperEnv);
            if (Files.exists(envPath)) {
                // FIX (cross-platform bundling): this used to hardcode the
                // Windows venv layout unconditionally -- Scripts/ for
                // executables and DLL search, a flat Lib/ for PYTHONPATH. Now
                // resolved per-OS via the same venv*RelativePath() helpers
                // used for the bundled-python lookup above, so this stays in
                // sync with wherever that logic decided the interpreter itself
                // actually lives.
                String pathEnv = System.getenv("PATH");
                String binDir = envPath.resolve(venvBinRelativePath()).toString();

                pb.environment().put("PATH",
                    binDir +
                    java.io.File.pathSeparator +
                    (pathEnv != null ? pathEnv : ""));

                // FIX (real production crash: "Fatal Python error:
                // init_fs_encoding ... ModuleNotFoundError: No module named
                // 'encodings'"): this used to also set PYTHONHOME to envPath
                // and PYTHONPATH to the venv's site-packages. That's wrong
                // for a normal venv-created environment -- a venv's own Lib/
                // only holds site-packages; the actual standard library lives
                // in the BASE Python install the venv was created from, and
                // the venv's own interpreter already resolves that
                // automatically via its pyvenv.cfg file with zero environment
                // variables needed. Explicitly setting PYTHONHOME to the venv
                // root overrides that resolution and points Python at a
                // standard library that isn't there -- this is exactly what
                // produced the crash above. Setting nothing beyond PATH lets
                // the venv interpreter bootstrap itself exactly as it would
                // from a normal command-line invocation.

                // Set working directory to whisper_env
                pb.directory(envPath.toFile());
            }
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