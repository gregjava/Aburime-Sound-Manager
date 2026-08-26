/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.BatchFileItem;
import audiomanager.model.ProcessingConfig;
import audiomanager.model.TranscriptionConfig;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * A minimal REST API for headless/scripted operation.
 *
 * <p>This API provides endpoints for submitting transcription jobs
 * programmatically, enabling integration with scripts and automation tools.</p>
 *
 * <p><b>Security note:</b> This server binds to localhost only by design —
 * there is no authentication. Do not change the bind address to 0.0.0.0
 * without adding authentication first.</p>
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>{@code /api/health}</td><td>Health check — returns {@code {"status":"ok"}}</td></tr>
 *   <tr><td>POST</td><td>{@code /api/jobs}</td><td>Submit a job — body: {@code {"filePath": "/absolute/path.mp3"}}</td></tr>
 *   <tr><td>GET</td><td>{@code /api/jobs}</td><td>List all jobs</td></tr>
 *   <tr><td>GET</td><td>{@code /api/jobs/{id}}</td><td>Get job status</td></tr>
 * </table>
 *
 * <p><b>Job lifecycle:</b> Submitted jobs run through an internal queue,
 * one file at a time, only when the shared {@link BatchProcessor} isn't
 * already processing a UI-initiated batch or another REST job.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see BatchProcessor
 * @see RestJob
 */
public class RestApiServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestApiServer.class);
    private static final Gson GSON = new Gson();

    private final BatchProcessor batchProcessor;
    private final Supplier<ProcessingConfig> processingConfigSupplier;
    private final Supplier<TranscriptionConfig> transcriptionConfigSupplier;

    private final Map<String, RestJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> pendingJobIds = new ConcurrentLinkedQueue<>();

    private HttpServer httpServer;
    private Thread workerThread;
    private volatile boolean running = false;

    /**
     * Constructs a new REST API server.
     *
     * @param batchProcessor the batch processor for executing jobs
     * @param processingConfigSupplier supplier for the current processing configuration
     * @param transcriptionConfigSupplier supplier for the current transcription configuration
     */
    public RestApiServer(BatchProcessor batchProcessor,
                          Supplier<ProcessingConfig> processingConfigSupplier,
                          Supplier<TranscriptionConfig> transcriptionConfigSupplier) {
        this.batchProcessor = batchProcessor;
        this.processingConfigSupplier = processingConfigSupplier;
        this.transcriptionConfigSupplier = transcriptionConfigSupplier;
    }

    /**
     * Starts the REST API server on the specified port.
     *
     * @param port the port to bind to (localhost only)
     * @throws IOException if the server cannot be started
     */
    public void start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpServer.createContext("/api/health", this::handleHealth);
        httpServer.createContext("/api/jobs", this::handleJobs);
        httpServer.setExecutor(null);
        httpServer.start();

        running = true;
        workerThread = new Thread(this::workerLoop, "RestApi-Job-Worker");
        workerThread.setDaemon(true);
        workerThread.start();

        LOGGER.info("REST API listening on http://127.0.0.1:{} (localhost only)", port);
    }

    /**
     * Stops the REST API server.
     */
    public void stop() {
        running = false;
        if (workerThread != null) workerThread.interrupt();
        if (httpServer != null) httpServer.stop(1);
        LOGGER.info("REST API stopped");
    }

    /**
     * Returns whether the server is running.
     *
     * @return {@code true} if the server is running
     */
    public boolean isRunning() {
        return running;
    }

    // -------------------------------------------------------------------------
    //  Job model
    // -------------------------------------------------------------------------

    /**
     * Job status enumeration.
     */
    public enum JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED }

    /**
     * REST job representation.
     */
    public static class RestJob {
        public final String id;
        public final String filePath;
        public volatile JobStatus status = JobStatus.QUEUED;
        public volatile String resultPath;
        public volatile String errorMessage;

        RestJob(String id, String filePath) {
            this.id = id;
            this.filePath = filePath;
        }
    }

    // -------------------------------------------------------------------------
    //  Worker loop
    // -------------------------------------------------------------------------

    /**
     * Worker loop that drains the internal queue one file at a time.
     */
    private void workerLoop() {
        while (running) {
            try {
                String jobId = pendingJobIds.poll();
                if (jobId == null) {
                    Thread.sleep(500);
                    continue;
                }
                RestJob job = jobs.get(jobId);
                if (job == null) continue;

                while (batchProcessor.isProcessing()) {
                    Thread.sleep(1000);
                }

                runJob(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.error("REST job worker error: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Executes a single REST job.
     *
     * @param job the job to execute
     */
    private void runJob(RestJob job) {
        job.status = JobStatus.PROCESSING;
        File file = new File(job.filePath);
        if (!file.isFile()) {
            job.status = JobStatus.FAILED;
            job.errorMessage = "File not found: " + job.filePath;
            return;
        }

        ObservableList<BatchFileItem> singleItemQueue = FXCollections.observableArrayList(new BatchFileItem(file));
        ProcessingConfig processingConfig = processingConfigSupplier.get();
        TranscriptionConfig transcriptionConfig = transcriptionConfigSupplier.get();

        try {
            BatchProcessor.BatchResult result = batchProcessor
                    .processBatch(singleItemQueue, processingConfig, transcriptionConfig, 1)
                    .get();

            if (result.getCompleted() >= 1) {
                job.status = JobStatus.COMPLETED;
                job.resultPath = processingConfig.getOutputDirectory();
            } else {
                job.status = JobStatus.FAILED;
                job.errorMessage = "Transcription did not complete successfully — check the app log for details.";
            }
        } catch (Exception e) {
            job.status = JobStatus.FAILED;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof audiomanager.exceptions.DependencyException de) {
                job.errorMessage = "Dependency problem: " + de.getUserMessage();
            } else if (cause instanceof audiomanager.exceptions.ModelNotFoundException mnfe) {
                job.errorMessage = "Model not found: " + mnfe.getUserMessage();
            } else if (cause instanceof audiomanager.exceptions.TranscriptionException te) {
                job.errorMessage = "Transcription failed: " + te.getUserMessage();
            } else if (cause instanceof audiomanager.exceptions.FfmpegException fe) {
                job.errorMessage = "Audio processing failed: " + fe.getUserMessage();
            } else {
                job.errorMessage = "Unexpected error: " + cause.getMessage();
            }
            LOGGER.error("REST job {} failed: {}", job.id, job.errorMessage, e);
        }
    }

    // -------------------------------------------------------------------------
    //  HTTP handlers
    // -------------------------------------------------------------------------

    /**
     * Handles health check requests.
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        writeJson(exchange, 200, Map.of("status", "ok"));
    }

    /**
     * Handles job-related requests (POST, GET).
     */
    private void handleJobs(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            if ("POST".equalsIgnoreCase(method) && path.equals("/api/jobs")) {
                handleCreateJob(exchange);
            } else if ("GET".equalsIgnoreCase(method) && path.equals("/api/jobs")) {
                handleListJobs(exchange);
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/jobs/")) {
                String jobId = path.substring("/api/jobs/".length());
                handleGetJob(exchange, jobId);
            } else {
                writeJson(exchange, 404, Map.of("error", "Not found"));
            }
        } catch (Exception e) {
            LOGGER.error("REST API request failed: {}", e.getMessage(), e);
            writeJson(exchange, 500, Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Handles job creation (POST /api/jobs).
     */
    private void handleCreateJob(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> parsed;
        try {
            parsed = GSON.fromJson(body, Map.class);
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("error", "Invalid JSON body"));
            return;
        }
        Object filePathObj = parsed != null ? parsed.get("filePath") : null;
        if (!(filePathObj instanceof String filePath) || filePath.isBlank()) {
            writeJson(exchange, 400, Map.of("error", "Missing required field: filePath"));
            return;
        }

        String jobId = UUID.randomUUID().toString();
        RestJob job = new RestJob(jobId, filePath);
        jobs.put(jobId, job);
        pendingJobIds.add(jobId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("status", job.status.name().toLowerCase());
        writeJson(exchange, 202, response);
    }

    /**
     * Handles job status requests (GET /api/jobs/{id}).
     */
    private void handleGetJob(HttpExchange exchange, String jobId) throws IOException {
        RestJob job = jobs.get(jobId);
        if (job == null) {
            writeJson(exchange, 404, Map.of("error", "Unknown job id"));
            return;
        }
        writeJson(exchange, 200, jobToMap(job));
    }

    /**
     * Handles job listing requests (GET /api/jobs).
     */
    private void handleListJobs(HttpExchange exchange) throws IOException {
        writeJson(exchange, 200, Map.of("jobs", jobs.values().stream().map(this::jobToMap).toList()));
    }

    /**
     * Converts a job to a map for JSON serialisation.
     */
    private Map<String, Object> jobToMap(RestJob job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", job.id);
        m.put("filePath", job.filePath);
        m.put("status", job.status.name().toLowerCase());
        if (job.resultPath != null) m.put("outputDirectory", job.resultPath);
        if (job.errorMessage != null) m.put("error", job.errorMessage);
        return m;
    }

    /**
     * Writes a JSON response to the HTTP exchange.
     */
    private void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}