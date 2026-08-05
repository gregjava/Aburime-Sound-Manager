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
 * A minimal REST API for headless/scripted operation — "REST API for
 * headless operation" from the commercial-competitiveness review, never
 * previously built.
 *
 * <h2>Scope, honestly stated</h2>
 * This submits jobs using the exact same settings currently configured in
 * the desktop UI (model, language, diarization, output directory —
 * pulled live via the supplier lambdas passed to the constructor) rather
 * than accepting per-request overrides. That's a deliberate simplification:
 * building a safe way to construct a one-off {@link TranscriptionConfig}/
 * {@link ProcessingConfig} from arbitrary JSON without validating against
 * those classes' actual constraints would risk creating configs the rest
 * of the app has never had to handle. "Uses whatever the UI has open" is a
 * real, useful headless-automation story (point a script at a file, let it
 * transcribe with your normal settings) without that risk.
 *
 * <p>Because {@link BatchProcessor#processBatch} only supports one batch at
 * a time (throws if already processing), REST-submitted jobs run through
 * their own internal single-worker queue, one file at a time, only when
 * the shared {@code BatchProcessor} isn't already busy with a UI-initiated
 * batch or another REST job. This means a REST job can queue behind either.
 * </p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/health} — {"status":"ok"}</li>
 *   <li>{@code POST /api/jobs} — body {"filePath": "/absolute/path.mp3"} → {"jobId": "...", "status": "queued"}</li>
 *   <li>{@code GET /api/jobs} — list all jobs this server has seen (in-memory only, cleared on restart)</li>
 *   <li>{@code GET /api/jobs/{id}} — one job's status/result path/error</li>
 * </ul>
 *
 * <h2>Security note</h2>
 * This binds to localhost only, by design — there's no authentication, and
 * it accepts arbitrary local file paths to transcribe. Do not change the
 * bind address to 0.0.0.0 without adding authentication first; doing so
 * would let anything on the network submit arbitrary local files for this
 * process to read.
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

    public RestApiServer(BatchProcessor batchProcessor,
                          Supplier<ProcessingConfig> processingConfigSupplier,
                          Supplier<TranscriptionConfig> transcriptionConfigSupplier) {
        this.batchProcessor = batchProcessor;
        this.processingConfigSupplier = processingConfigSupplier;
        this.transcriptionConfigSupplier = transcriptionConfigSupplier;
    }

    public void start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        httpServer.createContext("/api/health", this::handleHealth);
        httpServer.createContext("/api/jobs", this::handleJobs);
        httpServer.setExecutor(null); // default executor is fine for a low-traffic local API
        httpServer.start();

        running = true;
        workerThread = new Thread(this::workerLoop, "RestApi-Job-Worker");
        workerThread.setDaemon(true);
        workerThread.start();

        LOGGER.info("REST API listening on http://127.0.0.1:{} (localhost only)", port);
    }

    public void stop() {
        running = false;
        if (workerThread != null) workerThread.interrupt();
        if (httpServer != null) httpServer.stop(1);
        LOGGER.info("REST API stopped");
    }

    public boolean isRunning() {
        return running;
    }

    // -------------------------------------------------------------------------
    //  Job model
    // -------------------------------------------------------------------------

    public enum JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED }

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
    //  Worker loop — drains the internal queue one file at a time
    // -------------------------------------------------------------------------

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

                // Don't step on a UI-initiated batch (or another REST job) —
                // processBatch() throws if one's already running.
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
            // processBatch's internal callbacks use Platform.runLater — the
            // JavaFX toolkit must already be running (it is, since this
            // server only ever runs alongside the desktop app), but this
            // worker thread itself is not the JavaFX thread, which is fine:
            // processBatch() is designed to be called from any thread and
            // returns a CompletableFuture.
            BatchProcessor.BatchResult result = batchProcessor
                    .processBatch(singleItemQueue, processingConfig, transcriptionConfig, 1)
                    .get(); // block this worker thread until the single job finishes

            if (result.getCompleted() >= 1) {
                job.status = JobStatus.COMPLETED;
                job.resultPath = processingConfig.getOutputDirectory();
            } else {
                job.status = JobStatus.FAILED;
                job.errorMessage = "Transcription did not complete successfully — check the app log for details.";
            }
        } catch (Exception e) {
            job.status = JobStatus.FAILED;
            // Classify against the typed exception hierarchy where the
            // underlying cause is one we recognise, so API consumers get a
            // specific reason instead of always seeing a generic message.
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

    private void handleHealth(HttpExchange exchange) throws IOException {
        writeJson(exchange, 200, Map.of("status", "ok"));
    }

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

    private void handleGetJob(HttpExchange exchange, String jobId) throws IOException {
        RestJob job = jobs.get(jobId);
        if (job == null) {
            writeJson(exchange, 404, Map.of("error", "Unknown job id"));
            return;
        }
        writeJson(exchange, 200, jobToMap(job));
    }

    private void handleListJobs(HttpExchange exchange) throws IOException {
        writeJson(exchange, 200, Map.of("jobs", jobs.values().stream().map(this::jobToMap).toList()));
    }

    private Map<String, Object> jobToMap(RestJob job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", job.id);
        m.put("filePath", job.filePath);
        m.put("status", job.status.name().toLowerCase());
        if (job.resultPath != null) m.put("outputDirectory", job.resultPath);
        if (job.errorMessage != null) m.put("error", job.errorMessage);
        return m;
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}