/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager;

import audiomanager.core.AutoUpdater;
import audiomanager.core.BatchScheduler;
import audiomanager.core.ErrorReporter;
import audiomanager.core.ModelManager;
import audiomanager.ui.EulaDialog;
import audiomanager.ui.MainWindow;
import audiomanager.ui.OnboardingWizard;
import audiomanager.util.PreferenceManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * Studio Audio Manager Application v3.9
 */
public class Studio extends Application {

    ModelManager modelManager;
    private static final Logger LOGGER = LoggerFactory.getLogger(Studio.class);

    private static volatile Studio instance;

    public static Studio getInstance() {
        return instance;
    }

    private AutoUpdater autoUpdater;
    private ErrorReporter errorReporter;
    private BatchScheduler batchScheduler;

    private PreferenceManager prefManager;
    private MainWindow mainWindow;

    // Bundled resource paths
    private String appDir;
    private String ffmpegPath;
    private String whisperPython;
    private String whisperEnv;

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_MAC = OS_NAME.contains("mac") || OS_NAME.contains("darwin");

    private static String exeName(String baseName) {
        return IS_WINDOWS ? baseName + ".exe" : baseName;
    }

    private static Path venvPythonRelativePath() {
        return IS_WINDOWS ? Paths.get("Scripts", "python.exe") : Paths.get("bin", "python3");
    }

    private static Path venvBinRelativePath() {
        return IS_WINDOWS ? Paths.get("Scripts") : Paths.get("bin");
    }

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
            } catch (IOException ignored) {
                // Fall through
            }
        }
        return Paths.get("lib", "python3", "site-packages");
    }

    @Override
    public void init() throws Exception {
        super.init();

        instance = this;

        try {
            initializeBundledPaths();
        } catch (Exception e) {
            LOGGER.warn("Bundled runtime path resolution failed ({}); falling back to system PATH.", e.getMessage(), e);
            appDir = ".";
            ffmpegPath = "ffmpeg";
            whisperPython = "python";
            whisperEnv = null;
        }

        this.modelManager = new ModelManager();

        // Initialize error reporter EARLY so it can catch startup errors
        initializeErrorReporter();

        // Initialize auto updater
        initializeAutoUpdater();

        // Initialize batch scheduler
        initializeBatchScheduler();

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

        // Set up global exception handler
        setupGlobalExceptionHandler();
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            LOGGER.info("Starting application...");

            // Initialize preference manager
            prefManager = new PreferenceManager(Studio.class);
            LOGGER.debug("Preference manager initialized");

            // ========== EULA CHECK ==========
            // Show EULA if not accepted
            if (!EulaDialog.isEulaAccepted(prefManager)) {
                LOGGER.info("EULA not accepted - showing dialog");
                EulaDialog eulaDialog = new EulaDialog();
                boolean accepted = eulaDialog.showAndWait();
                if (accepted) {
                    EulaDialog.markEulaAccepted(prefManager);
                    LOGGER.info("EULA accepted by user");
                } else {
                    LOGGER.info("EULA declined - exiting application");
                    Platform.exit();
                    return;
                }
            }
            // ========== END EULA CHECK ==========

            // ========== CODE SIGNING VERIFICATION ==========
            // Check if application is verified as code-signed
            if (prefManager.isCodeSigned()) {
                LOGGER.info("Application is verified as code-signed");
            } else {
                LOGGER.info("Application is not verified as code-signed - running in unsigned mode");
            }
            // ========== END CODE SIGNING VERIFICATION ==========

            // Check if we just installed an update
            checkRestartAfterUpdate();

            // Run first-run onboarding if needed
            runFirstRunOnboardingIfNeeded();

            // Check for updates in background
            checkForUpdatesInBackground();

            // Create and initialize main window
            mainWindow = new MainWindow(primaryStage, prefManager);
            mainWindow.initialize();

            LOGGER.info("Application started successfully");

            // Show model status in UI if needed
            Platform.runLater(this::showModelStatusInUI);

        } catch (Exception e) {
            LOGGER.error("Fatal error during application startup", e);
            if (errorReporter != null && errorReporter.isEnabled()) {
                errorReporter.reportErrorSync(e, "Application startup");
            }
            showErrorAndExit("Startup Error",
                    "Failed to initialize application",
                    e);
        }
    }

    @Override
    public void stop() {
        LOGGER.info("Application shutdown initiated");

        try {
            if (prefManager != null) {
                prefManager.flush();
                LOGGER.debug("Preferences saved");
            }

            if (batchScheduler != null) {
                batchScheduler.shutdown();
                LOGGER.debug("Batch scheduler shutdown");
            }

            if (autoUpdater != null) {
                LOGGER.debug("Auto updater shutdown");
            }

            LOGGER.info("Application shutdown complete");
            super.stop();
        } catch (Exception e) {
            LOGGER.error("Error during application shutdown", e);
        }
    }

    // ========== BUNDLED PATHS ==========

    private void initializeBundledPaths() {
        appDir = System.getProperty("app.dir", ".");
        ffmpegPath = System.getProperty("ffmpeg.path", "ffmpeg");
        whisperPython = System.getProperty("whisper.python", "python");
        whisperEnv = System.getProperty("whisper.env", ".");

        LOGGER.info("Application directory: {}", appDir);
        LOGGER.info("FFmpeg path: {}", ffmpegPath);
        LOGGER.info("Whisper Python: {}", whisperPython);
        LOGGER.info("Whisper environment: {}", whisperEnv);

        validateBundledPaths();
    }

    private void validateBundledPaths() {
        boolean ffmpegPathWasExplicitlyConfigured = System.getProperty("ffmpeg.path") != null;
        Path ffmpeg = Paths.get(ffmpegPath);
        if (!Files.exists(ffmpeg)) {
            if (ffmpegPathWasExplicitlyConfigured) {
                LOGGER.warn("FFmpeg not found at configured path: {}", ffmpegPath);
            } else {
                LOGGER.debug("No bundled FFmpeg path configured; will resolve \"ffmpeg\" via system PATH.");
            }
            Path bundledFFmpeg = getAppDirectory().resolve("ffmpeg").resolve(exeName("ffmpeg"));
            if (Files.exists(bundledFFmpeg)) {
                ffmpegPath = bundledFFmpeg.toString();
                LOGGER.info("Using bundled FFmpeg: {}", ffmpegPath);
            } else {
                ffmpegPath = "ffmpeg";
                LOGGER.info("Using system FFmpeg (PATH): {}", ffmpegPath);
            }
        } else {
            LOGGER.info("FFmpeg found at: {}", ffmpegPath);
        }

        boolean pythonPathWasExplicitlyConfigured = System.getProperty("whisper.python") != null;
        Path python = Paths.get(whisperPython);
        if (!Files.exists(python)) {
            if (pythonPathWasExplicitlyConfigured) {
                LOGGER.warn("Whisper Python not found at configured path: {}", whisperPython);
            } else {
                LOGGER.debug("No bundled Whisper Python path configured; will resolve \"python\" via system PATH.");
            }
            Path bundledPython = getAppDirectory().resolve("whisper_env").resolve(venvPythonRelativePath());
            if (Files.exists(bundledPython)) {
                whisperPython = bundledPython.toString();
                whisperEnv = getAppDirectory().resolve("whisper_env").toString();
                LOGGER.info("Using bundled Whisper Python: {}", whisperPython);
                LOGGER.info("Using bundled Whisper environment: {}", whisperEnv);
            } else {
                whisperPython = "python";
                LOGGER.info("Using system Python: {}", whisperPython);
                whisperEnv = null;
            }
        } else {
            LOGGER.info("Whisper Python found at: {}", whisperPython);
        }

        if (whisperEnv != null) {
            Path envDir = Paths.get(whisperEnv);
            if (!Files.exists(envDir)) {
                LOGGER.warn("Whisper environment not found at: {}", whisperEnv);
                Path pythonPath = Paths.get(whisperPython);
                if (pythonPath.getNameCount() > 3) {
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

    private Path getAppDirectory() {
        try {
            var codeSource = Studio.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                LOGGER.warn("Could not determine application directory, using current directory");
                return Paths.get(".");
            }
            String path = codeSource.getLocation().toURI().getPath();
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

    // ========== PUBLIC GETTERS ==========

    public Path getFFmpegPath() {
        return Paths.get(ffmpegPath);
    }

    public Path getWhisperPython() {
        return Paths.get(whisperPython);
    }

    public Path getWhisperEnv() {
        return whisperEnv != null ? Paths.get(whisperEnv) : null;
    }

    public ProcessBuilder createFFmpegProcess(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(ffmpegPath);
        command.addAll(java.util.Arrays.asList(args));
        LOGGER.debug("FFmpeg command: {}", String.join(" ", command));
        return new ProcessBuilder(command);
    }

    public ProcessBuilder createWhisperProcess(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(whisperPython);
        command.add("-m");
        command.add("whisperx");
        command.addAll(java.util.Arrays.asList(args));
        LOGGER.debug("Whisper command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);

        if (whisperEnv != null) {
            Path envPath = Paths.get(whisperEnv);
            if (Files.exists(envPath)) {
                String pathEnv = System.getenv("PATH");
                String binDir = envPath.resolve(venvBinRelativePath()).toString();
                pb.environment().put("PATH",
                        binDir +
                                java.io.File.pathSeparator +
                                (pathEnv != null ? pathEnv : ""));
                pb.directory(envPath.toFile());
            }
        }

        return pb;
    }

    public boolean isFFmpegAvailable() {
        Path ffmpeg = Paths.get(ffmpegPath);
        return Files.exists(ffmpeg) && Files.isExecutable(ffmpeg);
    }

    public boolean isWhisperAvailable() {
        Path python = Paths.get(whisperPython);
        return Files.exists(python) && Files.isExecutable(python);
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public String getWhisperPythonPath() {
        return whisperPython;
    }

    public String getWhisperEnvPath() {
        return whisperEnv;
    }

    public ErrorReporter getErrorReporter() {
        return errorReporter;
    }

    public BatchScheduler getBatchScheduler() {
        return batchScheduler;
    }

    public AutoUpdater getAutoUpdater() {
        return autoUpdater;
    }

    public PreferenceManager getPreferenceManager() {
        return prefManager;
    }

    public String getAppVersion() {
        try {
            String version = System.getProperty("app.version");
            if (version == null || version.isEmpty()) {
                version = getClass().getPackage().getImplementationVersion();
            }
            if (version == null || version.isEmpty()) {
                version = "0.3.9";
            }
            return version;
        } catch (Exception e) {
            return "0.3.9";
        }
    }

    // ========== INITIALIZATION METHODS ==========

    private void initializeErrorReporter() {
        String version = getAppVersion();
        errorReporter = new ErrorReporter(version);

        if (prefManager != null) {
            boolean consent = prefManager.getBoolean("error.reporting.enabled", false);
            errorReporter.setEnabled(consent);
        }

        LOGGER.info("Error reporter initialized (enabled: {})", errorReporter.isEnabled());
    }

    private void initializeAutoUpdater() {
        autoUpdater = new AutoUpdater();

        autoUpdater.setCallback(new AutoUpdater.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(AutoUpdater.UpdateInfo update) {
                LOGGER.info("Update available: version {}", update.version);
                Platform.runLater(() -> showUpdateAvailableDialog(update));
            }

            @Override
            public void onNoUpdateAvailable() {
                LOGGER.debug("No update available");
            }

            @Override
            public void onCheckFailed(String error) {
                LOGGER.warn("Update check failed: {}", error);
            }

            @Override
            public void onDownloadProgress(double progress) {
                LOGGER.debug("Update download progress: {}%", (int) (progress * 100));
            }

            @Override
            public void onUpdateInstalled() {
                LOGGER.info("Update installed successfully");
                Platform.runLater(() -> showUpdateInstalledDialog());
            }
        });

        LOGGER.info("Auto updater initialized");
    }

    private void initializeBatchScheduler() {
        batchScheduler = new BatchScheduler();

        batchScheduler.setOnBatchStart(() -> {
            LOGGER.info("Scheduled batch starting...");
            Platform.runLater(() -> {
                if (mainWindow != null) {
                    mainWindow.showStatusMessage("Scheduled batch is starting...");
                }
            });
        });

        batchScheduler.setOnBatchComplete(() -> {
            LOGGER.info("Scheduled batch completed");
            Platform.runLater(() -> {
                if (mainWindow != null) {
                    mainWindow.showStatusMessage("Scheduled batch completed!");
                }
            });
        });

        LOGGER.info("Batch scheduler initialized");
    }

    private void setupGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.error("Uncaught exception in thread: {}", thread.getName(), throwable);

            if (errorReporter != null && errorReporter.isEnabled()) {
                errorReporter.reportErrorSync(throwable, "Uncaught in thread: " + thread.getName());
            }

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Unexpected Error");
                alert.setHeaderText("Something went wrong");
                alert.setContentText("An unexpected error occurred.\n\n" +
                        "The application will continue running.\n" +
                        "If this persists, please restart the application.");

                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String exceptionText = sw.toString();

                TextArea textArea = new TextArea(exceptionText);
                textArea.setEditable(false);
                textArea.setWrapText(true);
                textArea.setMaxWidth(Double.MAX_VALUE);
                textArea.setMaxHeight(Double.MAX_VALUE);

                javafx.scene.layout.GridPane expContent = new javafx.scene.layout.GridPane();
                expContent.setMaxWidth(Double.MAX_VALUE);
                expContent.add(new Label("Exception details:"), 0, 0);
                expContent.add(textArea, 0, 1);

                alert.getDialogPane().setExpandableContent(expContent);
                alert.showAndWait();
            });
        });

        LOGGER.info("Global exception handler configured");
    }

    // ========== UPDATE METHODS ==========

    private void checkRestartAfterUpdate() {
        if (AutoUpdater.isRestartRequired()) {
            LOGGER.info("Update was installed - showing restart notification");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Update Installed");
                alert.setHeaderText("AudioManager has been updated!");
                alert.setContentText("The update has been installed successfully.\n\n" +
                        "Please restart the application to use the new version.");
                alert.getButtonTypes().setAll(ButtonType.OK);
                alert.showAndWait();
            });
        }
    }

    private void checkForUpdatesInBackground() {
        if (autoUpdater == null) {
            LOGGER.debug("Auto updater not initialized - skipping update check");
            return;
        }

        String version = getAppVersion();
        LOGGER.debug("Checking for updates (version: {})", version);

        new Thread(() -> {
            try {
                autoUpdater.checkForUpdates(version);
            } catch (Exception e) {
                LOGGER.warn("Background update check failed: {}", e.getMessage());
            }
        }, "AutoUpdater-Check").start();
    }

    private void showUpdateAvailableDialog(AutoUpdater.UpdateInfo update) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Update Available");
        alert.setHeaderText("Version " + update.version + " is now available");

        String content = "A new version of AudioManager is available.\n\n";
        if (update.releaseNotes != null && !update.releaseNotes.isEmpty()) {
            content += "Release Notes:\n" + update.releaseNotes + "\n\n";
        }
        content += "Download size: " + formatFileSize(update.sizeBytes);

        if (update.isCritical) {
            content += "\n\n⚠️ This is a critical update - please install it as soon as possible.";
        }

        alert.setContentText(content);

        ButtonType downloadBtn = new ButtonType("Download & Install");
        ButtonType laterBtn = new ButtonType("Later");
        ButtonType skipBtn = new ButtonType("Skip This Version");
        alert.getButtonTypes().setAll(downloadBtn, laterBtn, skipBtn);

        alert.showAndWait().ifPresent(response -> {
            if (response == downloadBtn) {
                downloadAndInstallUpdate(update);
            } else if (response == skipBtn) {
                if (prefManager != null) {
                    prefManager.putString("update.skipped.version", update.version);
                }
            }
        });
    }

    private void downloadAndInstallUpdate(AutoUpdater.UpdateInfo update) {
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle("Downloading Update");
        progressDialog.setHeaderText("Downloading version " + update.version);
        progressDialog.setResizable(true);

        ProgressBar progressBar = new ProgressBar(-1);
        progressBar.setPrefWidth(400);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        Label progressLabel = new Label("Starting download...");

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.getChildren().addAll(progressLabel, progressBar);
        progressDialog.getDialogPane().setContent(content);

        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        progressDialog.getDialogPane().getButtonTypes().add(cancelButton);

        final Dialog<Void> finalDialog = progressDialog;

        autoUpdater.setCallback(new AutoUpdater.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(AutoUpdater.UpdateInfo info) {
            }

            @Override
            public void onNoUpdateAvailable() {
            }

            @Override
            public void onCheckFailed(String error) {
            }

            @Override
            public void onDownloadProgress(double progress) {
                Platform.runLater(() -> {
                    if (progress >= 0 && progress <= 1.0) {
                        progressBar.setProgress(progress);
                        progressLabel.setText("Downloading... " + (int) (progress * 100) + "%");
                    } else {
                        progressBar.setProgress(-1);
                        progressLabel.setText("Preparing download...");
                    }
                });
            }

            @Override
            public void onUpdateInstalled() {
                Platform.runLater(() -> {
                    finalDialog.close();
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Update Installed");
                    alert.setHeaderText("Update installed successfully!");
                    alert.setContentText("Please restart AudioManager to use the new version.");
                    alert.showAndWait();
                });
            }
        });

        finalDialog.setOnCloseRequest(event -> {
            LOGGER.info("Update download cancelled by user");
        });

        Thread downloadThread = new Thread(() -> {
            try {
                boolean success = autoUpdater.downloadAndInstallUpdate(update).get();
                if (!success) {
                    Platform.runLater(() -> {
                        finalDialog.close();
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Update Failed");
                        alert.setHeaderText("Failed to install update");
                        alert.setContentText("An error occurred while installing the update. " +
                                "Please try again later or download manually.");
                        alert.showAndWait();
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Update installation failed", e);
                Platform.runLater(() -> {
                    finalDialog.close();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Update Failed");
                    alert.setHeaderText("Failed to install update");
                    alert.setContentText("Error: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Update-Download");
        downloadThread.setDaemon(true);
        downloadThread.start();

        progressDialog.showAndWait();
    }

    private void showUpdateInstalledDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Update Installed");
        alert.setHeaderText("AudioManager has been updated!");
        alert.setContentText("The update has been installed.\n\nPlease restart the application.");
        alert.showAndWait();
    }

    // ========== ONBOARDING ==========

    private void runFirstRunOnboardingIfNeeded() {
        Path configDir = Paths.get(System.getProperty("user.home"), ".audiomanager");
        Path configFile = configDir.resolve("config.properties");

        if (!Files.exists(configFile)) {
            LOGGER.info("First run detected - launching onboarding wizard");

            Platform.runLater(() -> {
                OnboardingWizard wizard = new OnboardingWizard();
                wizard.showAndWait();

                try {
                    Files.createDirectories(configDir);
                    Properties props = new Properties();
                    props.setProperty("onboarding.completed", "true");
                    props.setProperty("onboarding.date", LocalDateTime.now().toString());
                    try (java.io.OutputStream out = Files.newOutputStream(configFile)) {
                        props.store(out, "AudioManager Configuration");
                    }
                    LOGGER.info("Onboarding completed and saved");
                } catch (IOException e) {
                    LOGGER.warn("Could not save onboarding state: {}", e.getMessage());
                }
            });
        } else {
            try (java.io.InputStream in = Files.newInputStream(configFile)) {
                Properties props = new Properties();
                props.load(in);
                LOGGER.debug("Loaded existing preferences from: {}", configFile);
            } catch (IOException e) {
                LOGGER.warn("Could not load preferences: {}", e.getMessage());
            }
        }
    }

    // ========== UTILITY METHODS ==========

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
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
        // Update UI to show model readiness status
    }

    private void configureLogging() {
        String logLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");
        if (logLevel == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "INFO");
        }

        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");

        LOGGER.debug("Logging configured");
    }

    private void showErrorAndExit(String title, String message, Exception e) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(message);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String exceptionText = sw.toString();

            alert.setContentText(e.getMessage() + "\n\nSee details for full stack trace.");

            TextArea textArea = new TextArea(exceptionText);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);

            javafx.scene.layout.GridPane.setVgrow(textArea, javafx.scene.layout.Priority.ALWAYS);
            javafx.scene.layout.GridPane.setHgrow(textArea, javafx.scene.layout.Priority.ALWAYS);

            javafx.scene.layout.GridPane expContent = new javafx.scene.layout.GridPane();
            expContent.setMaxWidth(Double.MAX_VALUE);
            expContent.add(new Label("Exception details:"), 0, 0);
            expContent.add(textArea, 0, 1);

            alert.getDialogPane().setExpandableContent(expContent);
            alert.showAndWait();

            Platform.exit();
            System.exit(1);
        });
    }

    public static void main(String[] args) {
        LOGGER.info("=================================================");
        LOGGER.info("  Studio Audio Manager v3.9");
        LOGGER.info("  Convert, Clean & Split Audio");
        LOGGER.info("=================================================");

        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.text", "t2k");

        String javaVersion = System.getProperty("java.version");
        LOGGER.info("Java Version: {}", javaVersion);
        LOGGER.info("OS: {} {}", System.getProperty("os.name"), System.getProperty("os.version"));
        LOGGER.info("User Home: {}", System.getProperty("user.home"));

        try {
            launch(args);
        } catch (Exception e) {
            LOGGER.error("Fatal error in main method", e);
            System.exit(1);
        }

        LOGGER.info("Application terminated");
    }
}