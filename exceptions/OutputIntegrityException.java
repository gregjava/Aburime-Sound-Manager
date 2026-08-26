/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when a processing step reports completion but its expected output
 * is missing, empty, or otherwise invalid.
 *
 * <h2>Why this exists</h2>
 * This is the single most important addition to the hierarchy. Several of
 * this codebase's documented past bugs share one shape: a batch or segment
 * step returns normally (no exception thrown, status set to
 * {@code COMPLETED}) while the actual output file is zero bytes, missing, or
 * was written to a temp directory that got cleaned up before use. Nothing in
 * the original code distinguished "this step threw" from "this step lied
 * about succeeding" — both looked like success to the caller.
 *
 * <p>The fix is procedural as much as it is a type: every place that marks a
 * unit of work {@code COMPLETED} must first assert the output actually exists
 * and is non-empty, and throw this (not just log a warning) if not. See
 * {@code OutputIntegrityChecks} for the shared assertion helper.</p>
 *
 * <p>This exception is marked as recoverable because the step needs to be
 * re-run, but the user can take action (retry, check disk space, etc.)
 * rather than being forced to abandon the batch.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see AudioManagerException
 * @see OutputIntegrityChecks
 */
public class OutputIntegrityException extends AudioManagerException {

    private final String expectedOutputPath;

    /**
     * Constructs a new OutputIntegrityException.
     *
     * @param expectedOutputPath the path that should have contained output
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     */
    public OutputIntegrityException(String expectedOutputPath, String technicalMessage, String userMessage) {
        super(technicalMessage, userMessage, true);
        this.expectedOutputPath = expectedOutputPath;
    }

    /**
     * Returns the path that should have contained output but didn't.
     *
     * @return the expected output path
     */
    public String getExpectedOutputPath() {
        return expectedOutputPath;
    }
}