/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

/**
 * A single reversible batch-queue mutation (add, remove, move, priority
 * change, rename, ...), as used by {@link QueueCommandHistory}.
 *
 * <p>Extracted out of {@code FileSelectionPanel} — first step of breaking
 * that class up into smaller, independently-testable pieces (see the
 * project's Technical Requirements Document, Phase 3). Deliberately scoped
 * to queue management only, not to transcription itself — there's no
 * meaningful "undo" for a file that's already been transcribed. Each
 * command captures exactly what it needs to reverse itself.</p>
 */
public interface QueueCommand {
    void undo();
    void redo();
    String description();
}