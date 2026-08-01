/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.exceptions.OutputIntegrityException;

import java.io.File;

/**
 * Single shared place to assert "the output this step claims to have
 * produced actually exists and is non-trivial" before anything is allowed to
 * mark a unit of work as {@code COMPLETED}.
 *
 * <h2>Where to call this</h2>
 * Every point in {@code SegmentProcessor}, {@code BatchProcessor}, and
 * {@code AudioProcessor} that currently does the equivalent of:
 * <pre>{@code
 * item.setStatus(ProcessingStatus.COMPLETED.name());
 * item.setResult(outputFile);
 * }</pre>
 * should instead do:
 * <pre>{@code
 * OutputIntegrityChecks.requireNonEmptyFile(outputFile,
 *     "Segment output for " + item.getFileName());
 * item.setStatus(ProcessingStatus.COMPLETED.name());
 * item.setResult(outputFile);
 * }</pre>
 * so a step that silently produced nothing throws instead of reporting
 * success — this is the fix for the "batch reports success with zero output
 * files" bug class described in the codebase's own fix-comments.
 *
 * <p>This class has no knowledge of FFmpeg, WhisperX, or any specific
 * pipeline stage on purpose — it's a generic, dependency-free assertion
 * layer so it can be unit-tested in isolation (see {@code RegressionTests})
 * and reused everywhere output integrity matters.</p>
 */
public final class OutputIntegrityChecks {

    private OutputIntegrityChecks() {}

    /**
     * Require that {@code file} exists, is a regular file, and is non-empty.
     *
     * @param file        the file that should have been produced
     * @param description short human-readable description of what produced it,
     *                    used in both the technical and user-facing messages
     * @throws OutputIntegrityException if the file is null, missing, a directory, or zero-length
     */
    public static void requireNonEmptyFile(File file, String description) throws OutputIntegrityException {
        String path = (file != null) ? file.getAbsolutePath() : "<null>";

        if (file == null) {
            throw new OutputIntegrityException(
                path,
                description + ": output file reference was null",
                description + " did not produce an output file."
            );
        }
        if (!file.exists()) {
            throw new OutputIntegrityException(
                path,
                description + ": expected output file does not exist: " + path,
                description + " finished, but its output file is missing. " +
                "This usually means a temp directory was cleaned up before the file was used, " +
                "or the underlying process failed without raising an error."
            );
        }
        if (!file.isFile()) {
            throw new OutputIntegrityException(
                path,
                description + ": expected output path is not a regular file: " + path,
                description + " produced an unexpected result at its output location."
            );
        }
        if (file.length() == 0) {
            throw new OutputIntegrityException(
                path,
                description + ": output file is zero bytes: " + path,
                description + " produced an empty output file, which almost always means the " +
                "underlying process failed silently."
            );
        }
    }

    /**
     * Require that a directory used to hold in-flight work still exists and
     * hasn't been cleaned up out from under an active operation — guards
     * against the temp-directory-race class of bug specifically.
     */
    public static void requireDirectoryStillPresent(File dir, String description) throws OutputIntegrityException {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            String path = (dir != null) ? dir.getAbsolutePath() : "<null>";
            throw new OutputIntegrityException(
                path,
                description + ": working directory disappeared during processing: " + path,
                description + " failed because its temporary working directory was removed " +
                "while still in use. If this happens repeatedly, another process or a cleanup " +
                "task may be racing with active jobs."
            );
        }
    }
}