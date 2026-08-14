/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo/redo command-history stack for batch-queue mutations (add, remove,
 * move-to-top/bottom, priority changes, rename, ...).
 *
 * <p>Extracted out of {@code FileSelectionPanel} — first step of breaking
 * that class up into smaller, independently-testable pieces (see the
 * project's Technical Requirements Document, Phase 3). This is a pure
 * stack manager with no UI dependency of its own: {@link #undo()}/
 * {@link #redo()} return the {@link QueueCommand} that was actually
 * processed (or {@code null} if there was nothing to undo/redo), so the
 * caller — {@code FileSelectionPanel}, which still owns the log line and
 * queue-totals refresh that should follow a successful undo/redo — decides
 * what UI-visible side effects to run, rather than this class reaching
 * back into panel-specific behavior. That boundary is what makes this
 * class safely unit-testable without any JavaFX scene graph at all.</p>
 *
 * <p>Both stacks are cleared of "future" redo history on a fresh command
 * push after an undo — standard redo-invalidation behaviour, matching
 * every conventional undo/redo implementation (browsers, editors, etc.):
 * once the user does something new after undoing, the undone branch is no
 * longer a valid "redo" target.</p>
 */
public class QueueCommandHistory {

    private static final int MAX_UNDO_HISTORY = 50;

    private final Deque<QueueCommand> undoStack = new ArrayDeque<>();
    private final Deque<QueueCommand> redoStack = new ArrayDeque<>();

    /** Records a newly-applied command and invalidates any pending redo history. */
    public void push(QueueCommand command) {
        undoStack.push(command);
        while (undoStack.size() > MAX_UNDO_HISTORY) undoStack.removeLast();
        redoStack.clear();
    }

    /**
     * Reverses the most recent command, if any.
     *
     * @return the command that was undone, or {@code null} if the undo
     *         stack was empty (nothing to undo).
     */
    public QueueCommand undo() {
        if (undoStack.isEmpty()) return null;
        QueueCommand command = undoStack.pop();
        command.undo();
        redoStack.push(command);
        return command;
    }

    /**
     * Reapplies the most recently undone command, if any.
     *
     * @return the command that was redone, or {@code null} if the redo
     *         stack was empty (nothing to redo).
     */
    public QueueCommand redo() {
        if (redoStack.isEmpty()) return null;
        QueueCommand command = redoStack.pop();
        command.redo();
        undoStack.push(command);
        return command;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** Clears both stacks — e.g. when a fresh batch queue is loaded and the prior history no longer applies to it. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}