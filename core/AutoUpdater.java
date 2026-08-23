/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * Automatic update checker and installer for AudioManager.
 * Checks a remote manifest for new versions and downloads updates.
 */
public class AutoUpdater {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoUpdater.class);
    private static final String UPDATE_MANIFEST_URL = 
    "https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/updates/latest.json";
    private static final String DOWNLOAD_BASE_URL = 
        "https://downloads.audiomanager.app/";
    
    private final Gson gson = new Gson();
    private UpdateInfo latestUpdate;
    private UpdateCheckCallback callback;
    
    public interface UpdateCheckCallback {
        void onUpdateAvailable(UpdateInfo update);
        void onNoUpdateAvailable();
        void onCheckFailed(String error);
        void onDownloadProgress(double progress);
        void onUpdateInstalled();
    }
    
    public static class UpdateInfo {
        public String version;
        public String releaseDate;
        public String downloadUrl;
        public long sizeBytes;
        public String checksum;
        public String releaseNotes;
        public boolean isCritical;
        public String minimumVersion;
        
        public boolean isNewerThan(String currentVersion) {
            return compareVersions(version, currentVersion) > 0;
        }
        
        private int compareVersions(String v1, String v2) {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");
            int len = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < len; i++) {
                int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
                int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
                if (p1 != p2) return Integer.compare(p1, p2);
            }
            return 0;
        }
    }
    
    public void setCallback(UpdateCheckCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Check for updates - with local fallback when server is unavailable.
     */
    public CompletableFuture<UpdateInfo> checkForUpdates(String currentVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Checking for updates (current version: {})", currentVersion);

                // Try remote server first
                try {
                    URL url = new URL(UPDATE_MANIFEST_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "AudioManager/" + currentVersion);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.connect();

                    if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        String json = new String(conn.getInputStream().readAllBytes());
                        UpdateInfo update = gson.fromJson(json, UpdateInfo.class);
                        return processUpdateResult(update, currentVersion);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Remote update check failed: {}", e.getMessage());
                }

                // Fallback: local version file (for development/testing)
                UpdateInfo localUpdate = checkLocalVersionFile(currentVersion);
                if (localUpdate != null) {
                    return processUpdateResult(localUpdate, currentVersion);
                }

                // No update available
                if (callback != null) callback.onNoUpdateAvailable();
                return null;

            } catch (Exception e) {
                LOGGER.warn("Update check failed: {}", e.getMessage());
                if (callback != null) callback.onCheckFailed(e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Download and install the update.
     */
    public CompletableFuture<Boolean> downloadAndInstallUpdate(UpdateInfo update) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "audiomanager-update");
                Files.createDirectories(tempDir);
                
                String fileName = "AudioManager-" + update.version + ".jar";
                Path downloadPath = tempDir.resolve(fileName);
                
                LOGGER.info("Downloading update to: {}", downloadPath);
                
                URL url = new URL(DOWNLOAD_BASE_URL + fileName);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "AudioManager-Updater");
                conn.connect();
                
                long totalBytes = conn.getContentLengthLong();
                long downloadedBytes = 0;
                
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(downloadPath.toFile())) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;
                        
                        if (totalBytes > 0 && callback != null) {
                            double progress = (double) downloadedBytes / totalBytes;
                            callback.onDownloadProgress(progress);
                        }
                    }
                }
                
                // Verify checksum if provided
                if (update.checksum != null && !update.checksum.isEmpty()) {
                    String actualChecksum = calculateChecksum(downloadPath);
                    if (!actualChecksum.equalsIgnoreCase(update.checksum)) {
                        LOGGER.error("Checksum mismatch! Expected: {}, Got: {}", 
                            update.checksum, actualChecksum);
                        throw new IOException("Download corrupted - checksum mismatch");
                    }
                }
                
                LOGGER.info("Update downloaded successfully: {} bytes", downloadedBytes);
                
                // Install the update
                installUpdate(downloadPath);
                
                if (callback != null) callback.onUpdateInstalled();
                return true;
                
            } catch (Exception e) {
                LOGGER.error("Update installation failed", e);
                return false;
            }
        });
    }
    
    /**
     * Install the update by replacing the current JAR.
     */
    private void installUpdate(Path downloadedFile) throws IOException {
        // Get the current JAR location
        String jarPath = AutoUpdater.class.getProtectionDomain()
            .getCodeSource().getLocation().getPath();
        Path currentJar = Paths.get(jarPath);
        
        // Create backup
        Path backupJar = currentJar.getParent().resolve(currentJar.getFileName() + ".bak");
        Files.copy(currentJar, backupJar, StandardCopyOption.REPLACE_EXISTING);
        
        // Replace with new version
        Files.copy(downloadedFile, currentJar, StandardCopyOption.REPLACE_EXISTING);
        
        LOGGER.info("Update installed. Restart required.");
        
        // Save a flag for next startup
        Path restartFlag = Paths.get(System.getProperty("user.home"), 
            ".audiomanager", "update_installed");
        Files.createDirectories(restartFlag.getParent());
        Files.writeString(restartFlag, "true");
    }
    
    /**
     * Calculate SHA-256 checksum of a file.
     */
    private String calculateChecksum(Path file) throws Exception {
        java.security.MessageDigest digest = 
            java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
    
    /**
     * Check if a restart is needed after update.
     */
    public static boolean isRestartRequired() {
        try {
            Path flag = Paths.get(System.getProperty("user.home"), 
                ".audiomanager", "update_installed");
            if (Files.exists(flag)) {
                Files.delete(flag);
                return true;
            }
        } catch (IOException e) {
            LOGGER.debug("Could not check restart flag: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Check a local version file for updates (development fallback).
     */
    private UpdateInfo checkLocalVersionFile(String currentVersion) {
        try {
            Path versionFile = Paths.get(System.getProperty("user.home"), 
                ".audiomanager", "latest_version.json");
            if (Files.exists(versionFile)) {
                String json = Files.readString(versionFile);
                UpdateInfo update = gson.fromJson(json, UpdateInfo.class);
                if (update.isNewerThan(currentVersion)) {
                    return update;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Local version file check failed: {}", e.getMessage());
        }
        return null;
    }

    private UpdateInfo processUpdateResult(UpdateInfo update, String currentVersion) {
        if (update.isNewerThan(currentVersion)) {
            LOGGER.info("Update available: {} (current: {})", update.version, currentVersion);
            if (callback != null) callback.onUpdateAvailable(update);
            latestUpdate = update;
            return update;
        } else {
            LOGGER.info("No update available (latest: {})", update.version);
            if (callback != null) callback.onNoUpdateAvailable();
            return null;
        }
    }
}