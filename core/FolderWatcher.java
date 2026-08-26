/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a directory for newly-created files and hands each one to a
 * caller-supplied handler.
 *
 * <p>This class provides automatic file monitoring for use cases such as:
 * <ul>
 *   <li>Auto-enqueueing new recordings dropped into a "watch folder"</li>
 *   <li>Processing files as they are added to a monitored directory</li>
 *   <li>Automated workflow triggering</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * FolderWatcher watcher = new FolderWatcher("/path/to/watch", file -> {
 *     // Handle new file - dispatch back to UI thread if needed
 *     Platform.runLater(() -> addFileToQueue(file));
 * });
 * Thread watcherThread = new Thread(watcher, "folder-watcher");
 * watcherThread.setDaemon(true);
 * watcherThread.start();
 * // ...later...
 * watcher.stop();
 * }</pre>
 *
 * <p><b>Thread-safety:</b> The file handler is invoked on the watcher's
 * own thread. Callers should dispatch back to the UI thread if updating
 * JavaFX components.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see WatchService
 */
public class FolderWatcher implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FolderWatcher.class);

    private final Path watchPath;
    private final Consumer<File> fileHandler;
    private volatile boolean running = true;
    private final WatchService watchService;

    /**
     * Constructs a new folder watcher.
     *
     * @param path the directory to watch for newly-created files
     * @param fileHandler the handler to invoke for each new file (on the watcher thread)
     * @param errorReporter the error reporter for diagnostics (may be {@code null})
     * @throws IOException if the path doesn't exist, is not a directory,
     *         or the watch service cannot be created or registered
     */
    public FolderWatcher(String path, Consumer<File> fileHandler, ErrorReporter errorReporter) throws IOException {
        this.watchPath = Paths.get(path);
        if (!java.nio.file.Files.isDirectory(this.watchPath)) {
            throw new IOException("Not a directory: " + path);
        }
        this.fileHandler = fileHandler;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.watchPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        LOGGER.info("Watching folder for new files: {}", this.watchPath);
    }

    /**
     * The main watch loop.
     *
     * <p>This method runs continuously until {@link #stop()} is called.
     * It polls for new file creation events and invokes the file handler
     * for each new file.</p>
     */
    @Override
    public void run() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        LOGGER.warn("Watch event overflow for {} — some file-creation events may have been missed.", watchPath);
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    File newFile = watchPath.resolve(fileName).toFile();
                    if (newFile.isFile()) {
                        LOGGER.info("New file detected: {}", newFile);
                        try {
                            fileHandler.accept(newFile);
                        } catch (Exception e) {
                            // A misbehaving handler must not kill the watch loop.
                            LOGGER.error("Folder watcher handler failed for {}: {}", newFile, e.getMessage(), e);
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    LOGGER.warn("Watch key no longer valid for {} — the directory may have been deleted. Stopping watcher.", watchPath);
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOGGER.info("Folder watcher stopped for: {}", watchPath);
    }

    /**
     * Stops the folder watcher and releases the underlying watch service.
     *
     * <p>This method is safe to call more than once.</p>
     */
    public void stop() {
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            LOGGER.debug("Error closing watch service for {}: {}", watchPath, e.getMessage());
        }
    }
}