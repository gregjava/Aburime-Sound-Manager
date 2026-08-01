/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when a processing step reports completion but its expected output
 * is missing, empty, or otherwise doesn't match what a successful run should
 * have produced.
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
 * unit of work COMPLETED must first assert the output actually exists and is
 * non-empty, and throw this (not just log a warning) if not. See
 * {@code OutputIntegrityChecks} for the shared assertion helper, and
 * {@code RegressionTests} for tests that pin this behavior down so it can't
 * silently regress again.</p>
 */
public class OutputIntegrityException extends AudioManagerException {

    private final String expectedOutputPath;

    public OutputIntegrityException(String expectedOutputPath, String technicalMessage, String userMessage) {
        // Not user-recoverable in place — the step needs to be re-run, not "fixed" by the user.
        super(technicalMessage, userMessage, true);
        this.expectedOutputPath = expectedOutputPath;
    }

    /** The path that should have contained output but didn't (missing or empty). */
    public String getExpectedOutputPath() {
        return expectedOutputPath;
    }
}
