/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

    /**
     * A {@link Semaphore} whose permit count can be adjusted up or down at
     * runtime without disturbing anything currently holding a permit —
     * shrinking just means future {@link #acquire()} calls wait a little
     * longer, exactly the behaviour needed for throttling
     * {@link ModelInstancePool} without ever touching object lifecycle.
     *
     * <p>Package-private for direct unit testing (see
     * {@code ResizableSemaphoreTest}).</p>
     */
    final class ResizableSemaphore extends Semaphore {
        private final AtomicInteger currentPermits;

        ResizableSemaphore(int initial) {
            super(initial, true);
            this.currentPermits = new AtomicInteger(initial);
        }

        synchronized void setPermits(int target) {
            int diff = target - currentPermits.get();
            if (diff > 0) {
                release(diff);
                currentPermits.addAndGet(diff);
            } else if (diff < 0) {
                reducePermits(-diff);
                currentPermits.addAndGet(diff);
            }
        }

        /** The configured target permit count (distinct from {@link #availablePermits()}, which drops while permits are held). */
        int getTargetPermits() {
            return currentPermits.get();
        }
    }