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
 * caller-supplied handler — e.g. to automatically enqueue new recordings
 * dropped into a "drop folder" for transcription.
 *
 * <p>Runs on its own thread via {@link #run()}; construct with the folder to
 * watch, wrap in a {@code Thread}, start it, and call {@link #stop()} when
 * you're done (e.g. on application exit).</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FolderWatcher watcher = new FolderWatcher("/path/to/watch", file -> {
 *     // e.g. Platform.runLater(() -> addFileToQueue(file));
 * });
 * Thread watcherThread = new Thread(watcher, "folder-watcher");
 * watcherThread.setDaemon(true);
 * watcherThread.start();
 * // ...later...
 * watcher.stop();
 * }</pre>
 */
public class FolderWatcher implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FolderWatcher.class);

    private final Path watchPath;
    private final Consumer<File> fileHandler;
    private volatile boolean running = true;
    private final WatchService watchService;

    /**
     * @param path        directory to watch for newly-created files
     * @param fileHandler invoked (on this watcher's own thread — dispatch
     *                    back to a UI thread yourself if needed) once per
     *                    new file detected
     * @throws IOException if the path doesn't exist or the watch service
     *                      can't be created/registered
     */
    public FolderWatcher(String path, Consumer<File> fileHandler) throws IOException {
        this.watchPath = Paths.get(path);
        if (!java.nio.file.Files.isDirectory(this.watchPath)) {
            throw new IOException("Not a directory: " + path);
        }
        this.fileHandler = fileHandler;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.watchPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        LOGGER.info("Watching folder for new files: {}", this.watchPath);
    }

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

    /** Stop watching and release the underlying watch service. Safe to call more than once. */
    public void stop() {
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            LOGGER.debug("Error closing watch service for {}: {}", watchPath, e.getMessage());
        }
    }
}