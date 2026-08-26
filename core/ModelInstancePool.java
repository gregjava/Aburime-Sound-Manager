/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * A per-model pool of {@link WhisperXTranscriptionService} instances with
 * concurrency throttling.
 *
 * <p>This class provides a pool of transcription service instances for
 * parallel processing of multiple files with the same model. Key features:
 * <ul>
 *   <li><b>Lazy instance creation:</b> Instances are created on-demand,
 *       not pre-allocated</li>
 *   <li><b>Instance recycling:</b> Instances are never discarded once created;
 *       they are returned to the pool after use</li>
 *   <li><b>Dynamic concurrency throttling:</b> A {@link ResizableSemaphore}
 *       controls how many borrows are allowed simultaneously, adjustable
 *       at runtime without affecting existing instances</li>
 *   <li><b>Model-specific pooling:</b> Separate pools for each model
 *       (base, small, medium, large)</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b> Instances are created lazily only when actual demand
 * requires them. A single sequential file will only construct one instance,
 * regardless of the pool's maximum size.</p>
 *
 * <p>Package-private for unit testing. Tests in {@code audiomanager.core}
 * can exercise lazy growth, borrow/release, and concurrency-gate resizing
 * directly.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see WhisperXTranscriptionService
 * @see ResizableSemaphore
 * @see ParallelProcessingManager
 */
final class ModelInstancePool {
    private final String model;
    private final java.util.function.Supplier<WhisperXTranscriptionService> instanceFactory;
    private final Logger logger;
    private final LinkedBlockingQueue<WhisperXTranscriptionService> available = new LinkedBlockingQueue<>();
    private final AtomicInteger liveCount = new AtomicInteger(0);
    private final int maxSize;
    private final ResizableSemaphore concurrencyGate;

    /**
     * Constructs a new model instance pool.
     *
     * @param model the model name this pool serves
     * @param maxConcurrent the maximum number of concurrent instances
     * @param instanceFactory the factory for creating new service instances
     * @param logger the logger for this pool
     */
    ModelInstancePool(String model, int maxConcurrent,
                      java.util.function.Supplier<WhisperXTranscriptionService> instanceFactory,
                      Logger logger) {
        this.model = model;
        this.instanceFactory = instanceFactory;
        this.logger = logger;
        this.maxSize = Math.max(1, maxConcurrent);
        this.concurrencyGate = new ResizableSemaphore(this.maxSize);
        // No eager instance creation - instances are created lazily
    }

    /**
     * Creates a new instance and adds it to the available queue.
     * Called synchronously when demand exceeds current capacity.
     */
    private synchronized void createAndAdd() {
        try {
            WhisperXTranscriptionService instance = instanceFactory.get();
            available.offer(instance);
            liveCount.incrementAndGet();
        } catch (Exception e) {
            logger.warn("Failed to create model instance for {}: {}", model, e.getMessage());
        }
    }

    /**
     * Borrows an exclusively-owned instance, blocking if the concurrency gate is full.
     *
     * <p>This method:
     * <ol>
     *   <li>Acquires a permit from the concurrency gate</li>
     *   <li>Returns an available instance if one exists</li>
     *   <li>Creates a new instance if the pool has not reached max capacity</li>
     *   <li>Blocks if no instance is available</li>
     * </ol>
     *
     * @return a borrowed transcription service instance
     * @throws InterruptedException if the operation is interrupted
     */
    WhisperXTranscriptionService borrow() throws InterruptedException {
        concurrencyGate.acquire();
        WhisperXTranscriptionService instance = available.poll();
        if (instance != null) return instance;
        // Grows lazily, purely driven by actual demand, up to maxSize
        if (liveCount.get() < maxSize) {
            createAndAdd();
            instance = available.poll();
            if (instance != null) return instance;
        }
        return available.take();
    }

    /**
     * Returns an instance after use.
     *
     * <p>Instances are always recycled and never discarded.</p>
     *
     * @param instance the instance to return to the pool
     */
    void release(WhisperXTranscriptionService instance) {
        available.offer(instance);
        concurrencyGate.release();
    }

    /**
     * Adjusts the allowed concurrency between 1 and the pool's capacity.
     *
     * <p>This method is safe to call as often as needed and never touches
     * an instance or recreates the pool.</p>
     *
     * @param newTarget the new concurrency target
     */
    void adjustTarget(int newTarget) {
        concurrencyGate.setPermits(Math.max(1, Math.min(maxSize, newTarget)));
    }

    /**
     * Returns the current number of live instances.
     *
     * @return the number of instances created so far
     */
    int size() {
        return liveCount.get();
    }

    /**
     * Returns the current live-adjusted concurrency ceiling.
     *
     * @return the current concurrency target
     */
    int targetConcurrency() {
        return concurrencyGate.getTargetPermits();
    }
}