/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.exceptions.OutputIntegrityException;

import java.io.File;

/**
 * Single shared place to assert that output files exist and are non-trivial
 * before marking work as complete.
 *
 * <p>This class provides dependency-free integrity checks that can be reused
 * across the entire pipeline. It addresses the "batch reports success with
 * zero output files" bug class by validating outputs before they are
 * marked as completed.</p>
 *
 * <p><b>Where to use this:</b>
 * <ul>
 *   <li>{@link SegmentProcessor} - after segment transcription</li>
 *   <li>{@link BatchProcessor} - before marking a file as completed</li>
 *   <li>{@link AudioProcessor} - after audio conversion</li>
 *   <li>Any other component that produces files</li>
 * </ul>
 *
 * <p><b>Usage example:</b></p>
 * <pre>{@code
 * OutputIntegrityChecks.requireNonEmptyFile(outputFile,
 *     "Segment output for " + item.getFileName());
 * item.setStatus(ProcessingStatus.COMPLETED.name());
 * }</pre>
 *
 * <p>This class has no knowledge of FFmpeg, WhisperX, or any specific
 * pipeline stage, making it easy to unit-test in isolation.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see OutputIntegrityException
 */
public final class OutputIntegrityChecks {

    private OutputIntegrityChecks() {}

    /**
     * Requires that a file exists, is a regular file, and is non-empty.
     *
     * <p>This method performs three checks:
     * <ol>
     *   <li>The file reference is not {@code null}</li>
     *   <li>The file exists on disk</li>
     *   <li>The file is a regular file (not a directory)</li>
     *   <li>The file has a size greater than zero bytes</li>
     * </ol>
     *
     * @param file the file that should have been produced
     * @param description a short human-readable description of what produced it
     * @throws OutputIntegrityException if the file is null, missing,
     *         a directory, or zero-length
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
     * Requires that a directory used for in-flight work still exists.
     *
     * <p>This check guards against the temp-directory-race class of bug
     * where a directory is deleted while still in use by another process.</p>
     *
     * @param dir the directory that should exist
     * @param description a short description of what the directory is for
     * @throws OutputIntegrityException if the directory is missing or not a directory
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