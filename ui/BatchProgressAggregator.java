/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.model.BatchFileItem;

import java.util.List;

/**
 * Computes overall batch progress the way a user actually expects to read
 * it — weighted by each in-flight file's own partial progress, not just a
 * count of fully-COMPLETED items.
 *
 * <h2>The bug this fixes</h2>
 * A batch of, say, 10 large files showed 0% overall progress for the
 * entire time the first file was transcribing — sometimes tens of minutes
 * — because a naive {@code completedCount / total} calculation reports
 * exactly 0% right up until the very first file crosses the COMPLETED
 * finish line, no matter how far through it actually is. For a batch of
 * few, large files, that reads as "frozen" or "hung" even though the app
 * is working correctly.
 *
 * <p>{@link #compute} instead treats each item's contribution to the
 * overall percentage as:</p>
 * <ul>
 *   <li>COMPLETED → 1.0, always — regardless of whatever partial-progress
 *       value happened to be recorded last (which can be stale, e.g. 0.95
 *       right before the item flipped to COMPLETED)</li>
 *   <li>FAILED → its last recorded progress, as-is — a file that failed
 *       60% of the way through contributes 0.6, not 0 (which would make
 *       overall progress visibly jump backward the instant something
 *       fails) and not 1.0 (which would misrepresent it as done)</li>
 *   <li>PROCESSING (or any other in-flight state) → its last recorded
 *       progress, as-is</li>
 *   <li>PENDING (not yet started) → 0.0</li>
 * </ul>
 *
 * <p>Stateless — {@link #compute} is a pure function of the list handed
 * to it; call it fresh whenever you need a snapshot rather than holding
 * onto one.</p>
 */
public final class BatchProgressAggregator {

    private BatchProgressAggregator() {
        // utility class — not instantiable
    }

    public static Snapshot compute(List<BatchFileItem> items) {
        if (items == null || items.isEmpty()) {
            // 0 total must never read as "finished" — an empty/not-yet-populated
            // queue is not the same state as a batch that ran and completed.
            return new Snapshot(0, 0.0, 0, 0, 0, 0, false);
        }

        int total = items.size();
        int completed = 0, failed = 0, inProgress = 0, pending = 0;
        double progressSum = 0.0;

        for (BatchFileItem item : items) {
            String status = item.getStatus();
            if ("COMPLETED".equals(status)) {
                completed++;
                progressSum += 1.0;
            } else if ("FAILED".equals(status)) {
                failed++;
                progressSum += clamp(item.getIndividualProgress());
            } else if ("PROCESSING".equals(status)) {
                inProgress++;
                progressSum += clamp(item.getIndividualProgress());
            } else {
                // PENDING, or any other/unrecognised status — treated as
                // not-yet-started (0 contribution) rather than throwing on
                // a status value this class doesn't specifically know about.
                pending++;
            }
        }

        double overallProgressPercent = (progressSum / total) * 100.0;
        boolean batchFinished = (completed + failed) == total;

        return new Snapshot(total, overallProgressPercent, completed, failed, inProgress, pending, batchFinished);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Immutable result of {@link #compute}. */
    public static final class Snapshot {
        private final int total;
        private final double overallProgressPercent;
        private final int completed;
        private final int failed;
        private final int inProgress;
        private final int pending;
        private final boolean batchFinished;

        private Snapshot(int total, double overallProgressPercent, int completed, int failed,
                          int inProgress, int pending, boolean batchFinished) {
            this.total = total;
            this.overallProgressPercent = overallProgressPercent;
            this.completed = completed;
            this.failed = failed;
            this.inProgress = inProgress;
            this.pending = pending;
            this.batchFinished = batchFinished;
        }

        public int getTotal() { return total; }
        /** 0-100. */
        public double getOverallProgressPercent() { return overallProgressPercent; }
        public int getCompleted() { return completed; }
        public int getFailed() { return failed; }
        public int getInProgress() { return inProgress; }
        public int getPending() { return pending; }
        /** True once every item is in a terminal state (COMPLETED or FAILED) — never true for an empty/zero-total batch. */
        public boolean isBatchFinished() { return batchFinished; }
    }
}