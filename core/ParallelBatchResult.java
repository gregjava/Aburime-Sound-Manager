/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ParallelBatchResult {
    private final int  total, completed, failed;
    private final long durationMillis;
    private final boolean cancelled;
    private final Set<String> successfulFiles = new HashSet<>();

    public ParallelBatchResult(int total, int completed, int failed,
                               long durationMillis, boolean cancelled) {
        this.total = total; this.completed = completed; this.failed = failed;
        this.durationMillis = durationMillis; this.cancelled = cancelled;
    }

    public int  getTotal()        { return total; }
    public int  getCompleted()    { return completed; }
    public int  getFailed()       { return failed; }
    public long getDurationMillis(){ return durationMillis; }
    public boolean wasCancelled() { return cancelled; }
    public void addSuccessfulFile(String name) { successfulFiles.add(name); }
    public Set<String> getSuccessfulFiles() { return Collections.unmodifiableSet(successfulFiles); }
}