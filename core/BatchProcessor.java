/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.constants.AppConstants;
import audiomanager.model.*;
import audiomanager.util.TimeLeftEstimator;
import audiomanager.util.PreferenceManager;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;

/**
 * Manages batch processing of audio files.
 *
 * <h2>Thread-safety fixes vs. original</h2>
 * <ul>
 *   <li>{@code completedFilesCount} and {@code failedFilesCount} are now
 *       {@link AtomicInteger} fields — safe for concurrent increment from
 *       multiple worker threads without {@code synchronized} blocks.</li>
 *   <li>{@link AudioProcessor#processAudioToWav} now returns a
 *       {@link AudioProcessor.ProcessingResult} rather than caching duration as
 *       a shared mutable field, so the same {@code AudioProcessor} instance
 *       can be used by parallel workers without data races.</li>
 *   <li>Cancellation now sets the item status to {@code CANCELLED} instead of
 *       silently returning — items no longer get stuck showing
 *       {@code PROCESSING}.</li>
 *   <li>The {@code System.out.println} debug statement is replaced with a
 *       proper {@code LOGGER.debug} call.</li>
 * </ul>
 *
 * <h2>Architecture fixes</h2>
 * <ul>
 *   <li>SRT / TXT / speaker-summary writing is delegated to
 *       {@link TranscriptionOutputWriter} (extracted helper class below).</li>
 *   <li>{@link #processItem} is decomposed into
 *       {@link #prepareAudio}, {@link #runTranscription}, and
 *       {@link #saveOutput} private methods to reduce nesting and length.</li>
 * </ul>
 */
public class BatchProcessor implements SegmentProgressListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchProcessor.class);

    private final AudioProcessor audioProcessor;
    private final WhisperXTranscriptionService transcriptionService;
    private final TimeLeftEstimator timeEstimator;
    private final PreferenceManager preferenceManager;
    private final Consumer<String> logger;
    private final ParallelProcessingManager parallelManager;

    // FIX (consolidation): the standalone `executor` field is gone — it
    // backed the now-removed executeBatch()/processItem() sequential
    // implementation. All processing goes through parallelManager's own
    // executors now.
    private volatile boolean cancelled = false;
    private ObservableList<BatchFileItem> currentItems;
    private boolean autoRemoveCompleted = false;

    // FIX: replaced plain volatile int with AtomicInteger for safe concurrent increment
    private final AtomicInteger completedFilesCount = new AtomicInteger(0);
    private final AtomicInteger failedFilesCount    = new AtomicInteger(0);
    private volatile int  totalFilesInBatch  = 0;
    private volatile long batchStartTime     = 0;
    private volatile long totalBatchDuration = 0;
    private volatile boolean batchInProgress = false;

    private Consumer<BatchStatistics> statisticsCallback;
    private Consumer<BatchFileItem>   fileCompletedCallback;
    private Consumer<Boolean>         isProcessingCallback;

    private static final String STATE_FILE_NAME = "batch_state.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Path stateFilePath;

    // -------------------------------------------------------------------------
    //  Process name constants
    // -------------------------------------------------------------------------

    private static final List<String> TRANSCRIPTION_PROCESSES = List.of(
            "audio_enhancement", "audio_preprocessing",
            "transcription_base", "saving_transcription", "file_cleanup");

    private static final List<String> AUDIO_ONLY_PROCESSES = List.of(
            "audio_enhancement", "file_cleanup");

    // -------------------------------------------------------------------------
    //  Callbacks / interfaces
    // -------------------------------------------------------------------------

    public interface FileCompletionCallback {
        void onFileCompleted(BatchFileItem item, boolean wasSuccessful);
    }

    private final FileCompletionCallback completionCallback;
    private final SimpleBooleanProperty isRunning = new SimpleBooleanProperty(false);

    // -------------------------------------------------------------------------
    //  SegmentProgressListener
    // -------------------------------------------------------------------------

    @Override
    public void onSegmentCompleted(int segmentIndex, int totalSegments) {
        saveBatchState();
    }

    // -------------------------------------------------------------------------
    //  Constructors
    // -------------------------------------------------------------------------

    public BatchProcessor(AudioProcessor audioProcessor,
                          WhisperXTranscriptionService transcriptionService,
                          TimeLeftEstimator timeEstimator,
                          Consumer<String> logger,
                          ObservableList<BatchFileItem> items) {
        this(audioProcessor, transcriptionService, timeEstimator, null, logger, null, items);
    }

    public BatchProcessor(AudioProcessor audioProcessor,
                          WhisperXTranscriptionService transcriptionService,
                          TimeLeftEstimator timeEstimator,
                          PreferenceManager preferenceManager,
                          Consumer<String> logger,
                          FileCompletionCallback completionCallback,
                          ObservableList<BatchFileItem> items) {
        this.audioProcessor     = audioProcessor;
        this.parallelManager    = new ParallelProcessingManager(
                audioProcessor, transcriptionService, logger, timeEstimator);
        // FIX (consolidation): processBatch() now always delegates to
        // parallelManager regardless of maxParallel — see the fix note on
        // processBatch() below. This wrapper preserves everything the
        // now-removed processItem()/executeBatch() used to do per file
        // (atomic completed/failed counters, the statistics callback, and
        // crash-resume state persisted after every single file so a
        // restart can resume mid-batch) by hooking them onto the same
        // per-item completion event, instead of losing them entirely once
        // there's no separate sequential implementation left to have done
        // it inline.
        this.parallelManager.setFileCompletionCallback((item, success) -> {
            if (success) completedFilesCount.incrementAndGet();
            else failedFilesCount.incrementAndGet();
            updateCompletionCounts();
            saveBatchState();
            if (completionCallback != null) completionCallback.onFileCompleted(item, success);
        });
        this.timeEstimator      = timeEstimator;
        this.preferenceManager  = preferenceManager;
        this.logger             = logger;
        this.completionCallback = completionCallback;
        this.currentItems       = items;
        this.transcriptionService = transcriptionService;

        if (timeEstimator == null) {
            LOGGER.warn("TimeEstimator is null — time estimation disabled.");
        }
        Path appDataDir = Paths.get(System.getProperty("user.home"), ".audiomanager");
        try {
            Files.createDirectories(appDataDir);
            stateFilePath = appDataDir.resolve(STATE_FILE_NAME);
        } catch (IOException e) {
            LOGGER.error("Failed to create state directory", e);
        }
    }

    // -------------------------------------------------------------------------
    //  State persistence
    // -------------------------------------------------------------------------

    private void saveBatchState() {
        if (!batchInProgress) {
            deleteStateFile();
            return;
        }
        BatchState state = new BatchState();
        state.setBatchId(String.valueOf(batchStartTime));
        state.setBatchStartTime(batchStartTime);

        for (BatchFileItem item : currentItems) {
            BatchState.FileState fs = new BatchState.FileState();
            fs.setFilePath(item.getFile().getAbsolutePath());
            fs.setStatus(item.getStatus());
            fs.setProgress(item.getProgress());
            fs.setErrorMessage(item.getErrorMessage());
            state.getFiles().add(fs);
        }

        int processingIndex = -1;
        for (int i = 0; i < currentItems.size(); i++) {
            if ("PROCESSING".equals(currentItems.get(i).getStatus())) {
                processingIndex = i;
                break;
            }
        }
        state.setCurrentFileIndex(processingIndex);

        try (Writer writer = Files.newBufferedWriter(stateFilePath)) {
            gson.toJson(state, writer);
            LOGGER.debug("Batch state saved.");
        } catch (IOException e) {
            LOGGER.error("Failed to save batch state", e);
        }
    }

    public void deleteStateFile() {
        try { Files.deleteIfExists(stateFilePath); }
        catch (IOException e) { LOGGER.warn("Could not delete state file", e); }
    }

    /**
     * Load the persisted batch state, if any.
     *
     * <p>FIX: previously only caught {@link IOException}. A truncated or
     * otherwise corrupted state file (e.g. from a crash mid-write) makes
     * Gson throw an unchecked {@code JsonSyntaxException}/{@code JsonIOException},
     * which passed straight through this method. The caller in
     * {@code MainWindow} happens to wrap its restore flow in a generic
     * {@code catch (Exception e)}, so this didn't crash the app — but it
     * also never deleted the bad file, so the same corrupted state failed
     * to load on every subsequent startup. Now a parse failure deletes the
     * file (matching the existing corrupted-file handling below) so the
     * app recovers cleanly instead of repeating the failure forever.</p>
     */
    public BatchState loadBatchState() {
        if (!Files.exists(stateFilePath)) return null;
        try (Reader reader = Files.newBufferedReader(stateFilePath)) {
            return gson.fromJson(reader, BatchState.class);
        } catch (IOException | com.google.gson.JsonParseException e) {
            LOGGER.error("Failed to load batch state — deleting corrupted file", e);
            try {
                Files.deleteIfExists(stateFilePath);
            } catch (IOException ex) {
                LOGGER.warn("Could not delete corrupted state file", ex);
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------
    //  Setters / accessors
    // -------------------------------------------------------------------------

    public void setStatisticsCallback(Consumer<BatchStatistics> callback)  { this.statisticsCallback  = callback; }
    public void setIsProcessingCallback(Consumer<Boolean> callback)        { this.isProcessingCallback = callback; }
    public void setFileCompletedCallback(Consumer<BatchFileItem> callback) { this.fileCompletedCallback = callback; }
    public void setAutoRemoveCompleted(boolean autoRemoveCompleted)        { this.autoRemoveCompleted  = autoRemoveCompleted; }

    /** Recent per-file stage-timing reports (see FileTimingReport), most-recent-first, for the UI's Performance Report view. */
    public java.util.List<FileTimingReport> getRecentTimingReports() {
        return parallelManager.getRecentTimingReports();
    }
    public void updateSegmentProgress(int segmentIndex, int totalSegments) { saveBatchState(); }
    public ReadOnlyBooleanProperty isRunningProperty()                     { return isRunning; }
    public int getTotalFilesInBatch()                                      { return currentItems.size(); }
    private ObservableList<BatchFileItem> getCurrentItems()                { return currentItems; }

    // -------------------------------------------------------------------------
    //  Batch lifecycle
    // -------------------------------------------------------------------------

    public CompletableFuture<BatchResult> processBatch(ObservableList<BatchFileItem> items,
                                                       ProcessingConfig processingConfig,
                                                       TranscriptionConfig transcriptionConfig,
                                                       int maxParallel) {
        if (isProcessing()) throw new IllegalStateException("Batch already in progress.");

        Platform.runLater(() -> isRunning.set(true));
        initializeBatchState(items);
        cancelled      = false;
        batchInProgress = true;

        if (isProcessingCallback != null) Platform.runLater(() -> isProcessingCallback.accept(true));
        if (timeEstimator != null) {
            timeEstimator.startBatch();
            for (BatchFileItem item : items) {
                double mb = item.getFile().length() / (1024.0 * 1024.0);
                List<String> procs = transcriptionConfig.isEnabled()
                        ? TRANSCRIPTION_PROCESSES : AUDIO_ONLY_PROCESSES;
                String model = transcriptionConfig.isEnabled()
                        ? transcriptionConfig.getModel() : "base";
                timeEstimator.addQueuedFile(item.getFile().getName(), mb, model, procs);
            }
        }

        LOGGER.info("Starting batch: {} files, max parallel: {}", totalFilesInBatch, maxParallel);
        log("🚀 Starting batch: " + totalFilesInBatch + " files");

        boolean exportWordCopy = preferenceManager != null
                && preferenceManager.getBoolean("export_word_copy", false);
        parallelManager.setExportWordCopy(exportWordCopy);

        boolean exportPdfCopy = preferenceManager != null
                && preferenceManager.getBoolean("export_pdf_copy", false);
        parallelManager.setExportPdfCopy(exportPdfCopy);

        // FIX (consolidation): this used to spin up its own executor and run
        // a fully separate sequential implementation (executeBatch() /
        // processItem()) — a second, independently-maintained copy of "walk
        // the queue, convert, transcribe, save" alongside
        // ParallelProcessingManager's copy of the same thing, and a THIRD
        // copy in the (now-removed) processBatchWithEnhancedParallelism().
        // Each had accumulated its own, different bugs over time, and fixes
        // to one silently didn't apply to the others (that's what most of
        // the earlier bug reports in this app turned out to trace back to).
        // Every batch now goes through this one pipeline, regardless of
        // maxParallel — a value of 1 behaves correctly as "one file
        // transcribing at a time" via ParallelProcessingManager's
        // semaphore-gated model-instance pool, it doesn't need a separate
        // code path to do that.
        return parallelManager.processBatchParallel(items, processingConfig, transcriptionConfig, maxParallel)
                .thenApply(parallelResult -> {
                    totalBatchDuration = parallelResult.getDurationMillis();
                    BatchResult result = new BatchResult(
                            parallelResult.getTotal(), parallelResult.getCompleted(),
                            parallelResult.getFailed(), parallelResult.getDurationMillis(),
                            parallelResult.wasCancelled());
                    log("🎯 Batch complete: " + parallelResult.getCompleted() + " succeeded, "
                            + parallelResult.getFailed() + " failed, "
                            + parallelResult.getDurationMillis() + "ms");
                    return result;
                })
                .exceptionally(throwable -> {
                    LOGGER.error("Batch processing failed", throwable);
                    log("❌ Batch processing failed: " + throwable.getMessage());
                    int completed = completedFilesCount.get();
                    // FIX: previously hardcoded cancelled=true here regardless
                    // of why the exception occurred — a genuine processing
                    // failure (e.g. every file failing because ffprobe
                    // couldn't be found) was reported to the UI as "Batch
                    // processing cancelled" instead of "Batch complete: 0
                    // succeeded, N failed", which actively misleads anyone
                    // trying to diagnose why nothing worked. The `cancelled`
                    // instance field is set only by cancel() (above) in
                    // response to an actual user cancellation request — use
                    // that as the source of truth instead of assuming every
                    // exception means the batch was cancelled.
                    return new BatchResult(totalFilesInBatch, completed,
                            totalFilesInBatch - completed, 0, cancelled);
                })
                .whenComplete((result, throwable) -> {
                    batchInProgress = false;
                    Platform.runLater(() -> isRunning.set(false));
                    if (isProcessingCallback != null)
                        Platform.runLater(() -> isProcessingCallback.accept(false));
                });
    }

    private void initializeBatchState(ObservableList<BatchFileItem> items) {
        totalFilesInBatch = items.size();
        completedFilesCount.set(0);
        failedFilesCount.set(0);
        batchStartTime    = System.currentTimeMillis();
        totalBatchDuration = 0;

        // FIX: a file that FAILED in a previous run (including one restored from
        // batch_state.json across an app restart) must be retryable. Previously,
        // FAILED items were left as FAILED here and the processing pipeline would
        // then skip them entirely — the "batch" would report success/failure
        // instantly (no FFmpeg/WhisperX subprocess was ever invoked) with no log
        // line explaining why. Only a genuinely COMPLETED item should be left
        // alone; everything else — including FAILED — gets reset to PENDING so a
        // new "Start batch" click retries it.
        //
        // Also note: this reset must actually COMPLETE before returning, not
        // just be queued. An earlier version of this fix used a bare
        // Platform.runLater(), which only queues the work on the JavaFX
        // Application Thread. processBatch() immediately follows this call by
        // handing the items off to parallelManager, which dispatches per-file
        // work onto its own pool threads — those threads could, and did, read
        // the still-stale FAILED status before the queued reset ran, re-skipping
        // the file in ~6ms. We block until the reset is done, guarding against
        // deadlock in case we're already on the FX Application Thread (e.g.
        // called directly from a button handler).
        Runnable resetTask = () -> {
            for (BatchFileItem item : items) {
                if (!"COMPLETED".equals(item.getStatus())) {
                    item.setStatus(ProcessingStatus.PENDING.name());
                }
                item.setStartTime(0);
                item.setErrorMessage(null);
            }
        };

        if (Platform.isFxApplicationThread()) {
            resetTask.run();
        } else {
            CountDownLatch resetLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    resetTask.run();
                } finally {
                    resetLatch.countDown();
                }
            });
            try {
                if (!resetLatch.await(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Timed out waiting for FX thread to reset batch item statuses; proceeding anyway — some files may be incorrectly skipped this run.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log("📊 Batch initialised: " + totalFilesInBatch + " files");
    }

    public void cancel() {
        if (!isProcessing()) return;
        cancelled = true;
        LOGGER.info("Cancellation requested.");
        // FIX (consolidation): was executor.shutdownNow() on the
        // now-removed standalone executor. parallelManager.cancel() is the
        // single cancellation point now, for both what used to be the
        // "standard" and "parallel" paths. It's run off the calling thread
        // since its underlying shutdown() can block up to ~2 minutes
        // waiting on executor termination — cancel() is typically invoked
        // from a UI confirmation handler on the FX thread, which shouldn't
        // freeze for that whole window.
        new Thread(parallelManager::cancel, "BatchProcessor-Cancel").start();
        if (isProcessingCallback != null)
            Platform.runLater(() -> isProcessingCallback.accept(false));
        Platform.runLater(() -> getCurrentItems().forEach(item -> {
            if (!ProcessingStatus.COMPLETED.name().equals(item.getStatus())
                    && !ProcessingStatus.FAILED.name().equals(item.getStatus())) {
                item.setStatus(ProcessingStatus.PENDING.name());
            }
        }));
    }

    public boolean isProcessing() {
        return batchInProgress;
    }


    // -------------------------------------------------------------------------
    //  Statistics
    // -------------------------------------------------------------------------

    private void updateCompletionCounts() {
        int completed = completedFilesCount.get();
        int failed    = failedFilesCount.get();
        BatchStatistics stats = new BatchStatistics(totalFilesInBatch, completed,
                failed, totalBatchDuration, batchStartTime);
        Platform.runLater(() -> {
            int pending = totalFilesInBatch - completed - failed;
            log("📈 Progress: " + completed + " done, " + failed + " failed, " + pending + " pending");
            if (statisticsCallback != null) statisticsCallback.accept(stats);
        });
    }

    public BatchStatistics getCurrentBatchStatistics() {
        if (!batchInProgress) {
            int done = 0, fail = 0;
            long totalDur = 0;
            for (BatchFileItem item : currentItems) {
                if ("COMPLETED".equals(item.getStatus())) done++;
                else if ("FAILED".equals(item.getStatus())) fail++;
                if (item.getTotalAudioDurationSeconds() > 0)
                    totalDur += (long)(item.getTotalAudioDurationSeconds() * 1000);
            }
            return new BatchStatistics(currentItems.size(), done, fail, totalDur, 0);
        }
        return new BatchStatistics(totalFilesInBatch, completedFilesCount.get(),
                failedFilesCount.get(), totalBatchDuration, batchStartTime);
    }

    public ProgressUpdate getCurrentProgress() {
        return new ProgressUpdate(totalFilesInBatch, completedFilesCount.get(), failedFilesCount.get());
    }

    // -------------------------------------------------------------------------
    //  Learning helpers
    // -------------------------------------------------------------------------

    public void saveLearnedEstimates()  { if (timeEstimator != null) timeEstimator.saveSessionData(); }
    public void clearLearnedEstimates() { if (timeEstimator != null) timeEstimator.clearLearnedData(); }
    public int  getLearnedPatternCount(){ return timeEstimator != null ? timeEstimator.getLearnedPatternCount() : 0; }
    public long getTotalFilesProcessed(){ return timeEstimator != null ? timeEstimator.getTotalFilesProcessed() : 0; }

    // -------------------------------------------------------------------------
    //  Inner POJOs
    // -------------------------------------------------------------------------

    public static class BatchStatistics {
        private final int totalFiles, completedFiles, failedFiles;
        private final long totalDuration, batchStartTime;

        public BatchStatistics(int total, int completed, int failed, long dur, long start) {
            this.totalFiles = total; this.completedFiles = completed;
            this.failedFiles = failed; this.totalDuration = dur; this.batchStartTime = start;
        }
        public int  getTotalFiles()     { return totalFiles; }
        public int  getCompletedFiles() { return completedFiles; }
        public int  getFailedFiles()    { return failedFiles; }
        public int  getPendingFiles()   { return totalFiles - completedFiles - failedFiles; }
        public long getTotalDuration()  { return totalDuration; }
        public long getBatchStartTime() { return batchStartTime; }
        public double getSuccessRate()  { return totalFiles > 0 ? completedFiles * 100.0 / totalFiles : 0; }
    }

    public static class BatchResult {
        private final int total, completed, failed;
        private final long durationMillis;
        private final boolean cancelled;

        public BatchResult(int total, int completed, int failed, long dur, boolean cancelled) {
            this.total = total; this.completed = completed; this.failed = failed;
            this.durationMillis = dur; this.cancelled = cancelled;
        }
        public int  getTotal()        { return total; }
        public int  getCompleted()    { return completed; }
        public int  getFailed()       { return failed; }
        public int  getPending()      { return total - completed - failed; }
        public long getDurationMillis(){ return durationMillis; }
        public boolean wasCancelled() { return cancelled; }
        public boolean isSuccessful() { return !cancelled && failed == 0; }
    }

    public static class ProgressUpdate {
        private final int total, completed, failed;
        public ProgressUpdate(int total, int completed, int failed) {
            this.total = total; this.completed = completed; this.failed = failed;
        }
        public int getTotal()     { return total; }
        public int getCompleted() { return completed; }
        public int getFailed()    { return failed; }
        public int getPending()   { return total - completed - failed; }
    }

    /** Emoji/pictographic characters stripped from the plain-text logger line — same ranges as MainWindow.EMOJI_PATTERN. */
    private static final java.util.regex.Pattern EMOJI_PATTERN =
            java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\uFE0F]");

    private void log(String message) {
        if (logger != null) logger.accept(message);
        LOGGER.info(EMOJI_PATTERN.matcher(message).replaceAll("").trim());
    }
}