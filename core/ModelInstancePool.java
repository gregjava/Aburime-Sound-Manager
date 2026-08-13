/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;


    /**
     * A per-model pool of {@link WhisperXTranscriptionService} instances,
     * with concurrency throttled dynamically based on live CPU/heap
     * pressure (see {@link #startResourceMonitor}) rather than a fixed
     * decision made once at batch start.
     *
     * <p>FIX (regression): the first version of this pool eagerly created
     * every instance up front and discarded/recreated instances whenever
     * the live resource monitor shrank its target below the current live
     * count. That combination turned a 45-90 minute job into a ~360 minute
     * one: {@code WhisperXTranscriptionService}'s constructor makes a real
     * subprocess call to verify the WhisperX installation (many seconds
     * each), and the monitor's "CPU > 90% -> shrink" rule fires constantly
     * during transcription, because CPU-bound transcription work is
     * <em>supposed</em> to peg the CPU — that's normal, expected load, not
     * contention to back off from. Every time the pool then needed an
     * instance again with none recycled, it paid that expensive
     * construction cost again, potentially several times over the course
     * of one segmented file.</p>
     *
     * <p>Concurrency throttling is now fully decoupled from instance
     * lifecycle. Instances are created lazily — only the first time actual
     * concurrent demand needs one — and once created are <b>never</b>
     * discarded; they're recycled for the lifetime of this pool. The
     * resource monitor only resizes a separate {@link ResizableSemaphore}
     * gating how many borrows are allowed at once, which costs nothing to
     * adjust, however often or aggressively, since it never touches an
     * actual instance.</p>
     *
     * <p>Package-private (not {@code private}) so unit tests in
     * {@code audiomanager.core} can exercise lazy growth, borrow/release, and
     * concurrency-gate resizing directly — this concurrency logic previously
     * had no test coverage because there was no way to reach it except by
     * running a full batch through {@link ParallelProcessingManager}.</p>
     *
     * <p>FIX: instance creation used to be hard-wired to
     * {@code new WhisperXTranscriptionService(dependencyManagerFactory.get(), timeEstimator)}
     * inline, which meant testing this class's lazy-growth and
     * concurrency-gate logic in isolation was impossible without also
     * triggering real GPU/Python/FFmpeg probing on every borrowed instance.
     * The constructor now takes a plain
     * {@code Supplier<WhisperXTranscriptionService>}, so production code
     * passes the real constructor call and tests can pass a cheap fake.</p>
     */
    final class ModelInstancePool {
        private final String model;
        private final java.util.function.Supplier<WhisperXTranscriptionService> instanceFactory;
        private final Logger logger;
        private final LinkedBlockingQueue<WhisperXTranscriptionService> available = new LinkedBlockingQueue<>();
        private final AtomicInteger liveCount = new AtomicInteger(0);
        private final int maxSize;
        private final ResizableSemaphore concurrencyGate;

        ModelInstancePool(String model, int maxConcurrent,
                          java.util.function.Supplier<WhisperXTranscriptionService> instanceFactory,
                          Logger logger) {
            this.model = model;
            this.instanceFactory = instanceFactory;
            this.logger = logger;
            this.maxSize = Math.max(1, maxConcurrent);
            this.concurrencyGate = new ResizableSemaphore(this.maxSize);
            // FIX: no eager instance creation here anymore — see class doc.
        }

        private synchronized void createAndAdd() {
            try {
                WhisperXTranscriptionService instance = instanceFactory.get();
                available.offer(instance);
                liveCount.incrementAndGet();
            } catch (Exception e) {
                logger.warn("Failed to create model instance for {}: {}", model, e.getMessage());
            }
        }

        /** Borrows an exclusively-owned instance, blocking if the concurrency gate is full. */
        WhisperXTranscriptionService borrow() throws InterruptedException {
            concurrencyGate.acquire();
            WhisperXTranscriptionService instance = available.poll();
            if (instance != null) return instance;
            // FIX: grows lazily, purely driven by actual demand, up to
            // maxSize — not eagerly at pool construction. A single
            // sequential file only ever needs one instance and will only
            // ever construct one, regardless of what maxSize is.
            if (liveCount.get() < maxSize) {
                createAndAdd();
                instance = available.poll();
                if (instance != null) return instance;
            }
            return available.take();
        }

        /** Returns an instance after use. Always recycled — never discarded. */
        void release(WhisperXTranscriptionService instance) {
            available.offer(instance);
            concurrencyGate.release();
        }

        /** Live-adjusts allowed concurrency between 1 and this pool's capacity. Free to call as often as needed — never touches an instance. */
        void adjustTarget(int newTarget) {
            concurrencyGate.setPermits(Math.max(1, Math.min(maxSize, newTarget)));
        }

        int size() { return liveCount.get(); }

        /** Current live-adjusted concurrency ceiling (see {@link #adjustTarget}) — for observability/logging only. */
        int targetConcurrency() { return concurrencyGate.getTargetPermits(); }
    }