/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/**
 * JVM shutdown hook that performs cleanup tasks when the application exits.
 *
 * <p>This class registers a shutdown hook that performs two critical tasks:
 * <ol>
 *   <li>Calls {@link BatchProcessor#cancel()} so in-flight transcription jobs
 *       write their partial state before the process dies</li>
 *   <li>Sweeps the system temp directory for orphaned directories older than
 *       24 hours ({@code segment_work_*} and {@code whisperx_output_*})</li>
 * </ol>
 *
 * <p><b>Usage:</b> Register the hook once at application startup:</p>
 * <pre>{@code
 * TempDirSweeper.registerShutdownHook(batchProcessor);
 * }</pre>
 *
 * <p>If the hook has already been registered, a second call is a no-op.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see BatchProcessor
 */
public final class TempDirSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(TempDirSweeper.class);
    private static final long ORPHAN_MAX_AGE_MS = 24 * 60 * 60 * 1_000L;

    private static volatile boolean registered = false;

    private TempDirSweeper() { /* utility class */ }

    /**
     * Registers the shutdown hook (idempotent).
     *
     * <p>If {@code batchProcessor} is {@code null}, the hook will still
     * perform the temp directory sweep.</p>
     *
     * @param batchProcessor the active {@link BatchProcessor}; may be {@code null}
     */
    public static synchronized void registerShutdownHook(BatchProcessor batchProcessor) {
        if (registered) return;
        registered = true;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown hook fired — cancelling batch and sweeping temp dirs.");

            // 1. Cancel any in-progress batch so state is saved
            if (batchProcessor != null) {
                try {
                    batchProcessor.cancel();
                } catch (Exception e) {
                    LOGGER.warn("Could not cancel batch on shutdown: {}", e.getMessage());
                }
            }

            // 2. Sweep orphaned temp directories
            sweepTempDirs();

        }, "audiomanager-shutdown-hook"));

        LOGGER.info("Shutdown hook registered.");
    }

    // -------------------------------------------------------------------------
    //  Temp-dir sweep
    // -------------------------------------------------------------------------

    /**
     * Sweeps the system temp directory for orphaned directories older than 24 hours.
     *
     * <p>This method looks for directories with names starting with
     * {@code segment_work_} or {@code whisperx_output_} and deletes them
     * if their last modified time is older than the cutoff.</p>
     */
    static void sweepTempDirs() {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        long cutoff  = System.currentTimeMillis() - ORPHAN_MAX_AGE_MS;
        try {
            Files.list(tempDir)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("segment_work_")
                                || name.startsWith("whisperx_output_");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoff;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(TempDirSweeper::deleteTree);
        } catch (IOException e) {
            LOGGER.debug("Temp sweep failed: {}", e.getMessage());
        }
    }

    /**
     * Deletes a directory tree recursively.
     *
     * @param root the root directory to delete
     */
    private static void deleteTree(Path root) {
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (IOException e) {
                            LOGGER.debug("Sweep: could not delete {}: {}", p, e.getMessage());
                        }
                    });
            LOGGER.info("Swept orphan dir: {}", root);
        } catch (IOException e) {
            LOGGER.warn("Sweep: could not walk {}: {}", root, e.getMessage());
        }
    }
}