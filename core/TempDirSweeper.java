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
 * JVM shutdown hook that performs two tasks when the application exits
 * (cleanly or via SIGTERM):
 *
 * <ol>
 *   <li>Calls {@link BatchProcessor#cancel()} so in-flight transcription jobs
 *       write their partial state before the process dies.</li>
 *   <li>Sweeps the system temp directory for {@code segment_work_*} and
 *       {@code whisperx_output_*} orphan directories older than 24 hours.</li>
 * </ol>
 *
 * <h2>Registration</h2>
 * Register this hook once at application startup, typically in
 * {@code MainWindow.start()}:
 * <pre>{@code
 * TempDirSweeper.registerShutdownHook(batchProcessor);
 * }</pre>
 *
 * <p>If the hook has already been registered a second call is a no-op.</p>
 */
public final class TempDirSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(TempDirSweeper.class);
    private static final long ORPHAN_MAX_AGE_MS = 24 * 60 * 60 * 1_000L;

    private static volatile boolean registered = false;

    private TempDirSweeper() { /* utility class */ }

    /**
     * Register the shutdown hook (idempotent).
     *
     * @param batchProcessor the active {@link BatchProcessor}; may be
     *                       {@code null} if the processor has not been created
     *                       yet (the hook will still sweep temp dirs).
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