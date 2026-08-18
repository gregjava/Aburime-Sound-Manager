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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in error and crash reporting system.
 * Collects anonymized error data and sends it to the server.
 */
public class ErrorReporter {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorReporter.class);
    private static final String REPORTING_ENDPOINT = 
        "https://api.audiomanager.app/reports/error";
    
    private final Gson gson = new Gson();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean userConsentGiven = new AtomicBoolean(false);
    private final Path reportQueuePath;
    private final String appVersion;
    private final String osInfo;
    private String userId;
    
    private static final int MAX_QUEUED_REPORTS = 50;
    private static final int BATCH_SIZE = 10;
    
    public ErrorReporter(String appVersion) {
        this.appVersion = appVersion;
        this.osInfo = System.getProperty("os.name") + " " + 
            System.getProperty("os.version") + " (" + 
            System.getProperty("os.arch") + ")";
        this.reportQueuePath = Paths.get(System.getProperty("user.home"), 
            ".audiomanager", "error_reports");
        generateUserId();
        
        try {
            Files.createDirectories(reportQueuePath);
        } catch (IOException e) {
            LOGGER.debug("Could not create report queue directory", e);
        }
        
        // Load persisted consent
        loadConsent();
    }
    
    /**
     * Set whether error reporting is enabled.
     * This should only be called with explicit user consent.
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        this.userConsentGiven.set(enabled);
        saveConsent();
    }
    
    public boolean isEnabled() {
        return enabled.get();
    }
    
    public boolean hasUserConsent() {
        return userConsentGiven.get();
    }
    
    /**
     * Report an error asynchronously.
     */
    public CompletableFuture<Void> reportError(Throwable error, String context) {
        return CompletableFuture.runAsync(() -> {
            if (!enabled.get() || !userConsentGiven.get()) {
                LOGGER.debug("Error reporting disabled - not sending report");
                return;
            }
            
            ErrorReport report = buildReport(error, context);
            queueReport(report);
            sendQueuedReports();
        });
    }
    
    /**
     * Report an error synchronously (for crashes).
     */
    public void reportErrorSync(Throwable error, String context) {
        if (!enabled.get() || !userConsentGiven.get()) {
            return;
        }
        
        ErrorReport report = buildReport(error, context);
        queueReport(report);
        sendQueuedReportsSync();
    }
    
    /**
     * Build a comprehensive error report.
     */
    private ErrorReport buildReport(Throwable error, String context) {
        ErrorReport report = new ErrorReport();
        report.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_INSTANT);
        report.appVersion = appVersion;
        report.osInfo = osInfo;
        report.userId = userId;
        
        // Error details
        report.errorType = error.getClass().getName();
        report.errorMessage = error.getMessage();
        report.stackTrace = getStackTrace(error);
        
        // Context
        report.context = context;
        
        // System info
        report.systemInfo = new HashMap<>();
        report.systemInfo.put("javaVersion", System.getProperty("java.version"));
        report.systemInfo.put("javaVendor", System.getProperty("java.vendor"));
        report.systemInfo.put("availableProcessors", 
            String.valueOf(Runtime.getRuntime().availableProcessors()));
        report.systemInfo.put("maxMemory", 
            String.valueOf(Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB");
        
        // Cause chain
        if (error.getCause() != null) {
            report.causeType = error.getCause().getClass().getName();
            report.causeMessage = error.getCause().getMessage();
        }
        
        return report;
    }
    
    /**
     * Get stack trace as string.
     */
    private String getStackTrace(Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        return sw.toString();
    }
    
    /**
     * Queue a report for later sending.
     */
    private void queueReport(ErrorReport report) {
        try {
            String json = gson.toJson(report);
            String filename = System.currentTimeMillis() + "_" + 
                UUID.randomUUID().toString().substring(0, 8) + ".json";
            Path reportFile = reportQueuePath.resolve(filename);
            Files.writeString(reportFile, json);
            
            // Clean old reports if queue is too large
            cleanQueue();
        } catch (IOException e) {
            LOGGER.debug("Failed to queue error report: {}", e.getMessage());
        }
    }
    
    /**
     * Clean old reports if queue exceeds max size.
     */
    private void cleanQueue() throws IOException {
        List<Path> reports = Files.list(reportQueuePath)
            .filter(p -> p.toString().endsWith(".json"))
            .sorted()
            .toList();
        
        while (reports.size() > MAX_QUEUED_REPORTS) {
            Files.delete(reports.get(0));
            reports = reports.subList(1, reports.size());
        }
    }
    
    /**
     * Send queued reports to the server.
     */
    private void sendQueuedReports() {
        try {
            List<Path> reports = Files.list(reportQueuePath)
                .filter(p -> p.toString().endsWith(".json"))
                .limit(BATCH_SIZE)
                .toList();
            
            if (reports.isEmpty()) return;
            
            List<ErrorReport> reportBatch = new ArrayList<>();
            for (Path p : reports) {
                String json = Files.readString(p);
                reportBatch.add(gson.fromJson(json, ErrorReport.class));
            }
            
            sendReportBatch(reportBatch);
            
            // Delete sent reports
            for (Path p : reports) {
                Files.delete(p);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to send queued reports: {}", e.getMessage());
        }
    }
    
    /**
     * Send queued reports synchronously.
     */
    private void sendQueuedReportsSync() {
        try {
            List<Path> reports = Files.list(reportQueuePath)
                .filter(p -> p.toString().endsWith(".json"))
                .limit(BATCH_SIZE)
                .toList();
            
            if (reports.isEmpty()) return;
            
            List<ErrorReport> reportBatch = new ArrayList<>();
            for (Path p : reports) {
                String json = Files.readString(p);
                reportBatch.add(gson.fromJson(json, ErrorReport.class));
            }
            
            sendReportBatch(reportBatch);
            
            for (Path p : reports) {
                Files.delete(p);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to send queued reports: {}", e.getMessage());
        }
    }
    
    /**
     * Send a batch of reports to the server.
     */
    private void sendReportBatch(List<ErrorReport> reports) {
        try {
            String json = gson.toJson(Map.of("reports", reports));
            
            URL url = new URL(REPORTING_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "AudioManager/" + appVersion);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                LOGGER.debug("Sent {} error reports successfully", reports.size());
            } else {
                LOGGER.debug("Failed to send error reports: HTTP {}", responseCode);
            }
            
        } catch (Exception e) {
            LOGGER.debug("Failed to send error reports: {}", e.getMessage());
        }
    }
    
    /**
     * Generate a consistent user ID (anonymized).
     */
    private void generateUserId() {
        try {
            Path userIdFile = Paths.get(System.getProperty("user.home"), 
                ".audiomanager", "user_id");
            if (Files.exists(userIdFile)) {
                userId = Files.readString(userIdFile).trim();
            } else {
                userId = UUID.randomUUID().toString();
                Files.createDirectories(userIdFile.getParent());
                Files.writeString(userIdFile, userId);
            }
        } catch (IOException e) {
            userId = UUID.randomUUID().toString();
        }
    }
    
    /**
     * Load persisted consent setting.
     */
    private void loadConsent() {
        try {
            Path consentFile = Paths.get(System.getProperty("user.home"), 
                ".audiomanager", "error_reporting_consent");
            if (Files.exists(consentFile)) {
                String value = Files.readString(consentFile).trim();
                boolean consent = "true".equalsIgnoreCase(value);
                userConsentGiven.set(consent);
                enabled.set(consent);
            }
        } catch (IOException e) {
            LOGGER.debug("Could not load consent setting: {}", e.getMessage());
        }
    }
    
    /**
     * Save consent setting.
     */
    private void saveConsent() {
        try {
            Path consentFile = Paths.get(System.getProperty("user.home"), 
                ".audiomanager", "error_reporting_consent");
            Files.createDirectories(consentFile.getParent());
            Files.writeString(consentFile, String.valueOf(enabled.get()));
        } catch (IOException e) {
            LOGGER.debug("Could not save consent setting: {}", e.getMessage());
        }
    }
    
    /**
     * Error report data structure.
     */
    private static class ErrorReport {
        String timestamp;
        String appVersion;
        String osInfo;
        String userId;
        String errorType;
        String errorMessage;
        String stackTrace;
        String context;
        String causeType;
        String causeMessage;
        Map<String, String> systemInfo;
    }
}
