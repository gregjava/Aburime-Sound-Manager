/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when an FFmpeg (or FFprobe) subprocess invocation fails or times
 * out — as distinct from {@link DependencyException}, which means FFmpeg
 * couldn't even be *found*. This means FFmpeg ran and rejected the input, or
 * the process had to be killed.
 *
 * <p>Distinguishing this from a generic {@code IOException} lets the UI say
 * "this specific file appears to be corrupt or in an unsupported format"
 * instead of the current generic failure alert, and lets batch-processing
 * code decide to skip-and-continue rather than abort the whole run.</p>
 */
public class FfmpegException extends AudioManagerException {

    private final int exitCode;
    private final String stderrTail;

    public FfmpegException(String technicalMessage, String userMessage,
                            int exitCode, String stderrTail) {
        // A single file failing FFmpeg processing shouldn't be fatal to a batch —
        // mark recoverable so batch code can skip-and-continue rather than abort.
        super(technicalMessage, userMessage, true);
        this.exitCode = exitCode;
        this.stderrTail = stderrTail;
    }

    public FfmpegException(String technicalMessage, String userMessage, Throwable cause) {
        super(technicalMessage, userMessage, true, cause);
        this.exitCode = -1;
        this.stderrTail = null;
    }

    /** FFmpeg/FFprobe process exit code, or -1 if the process never produced one (e.g. timeout, IOException). */
    public int getExitCode() {
        return exitCode;
    }

    /** Last portion of stderr captured before failure — useful in a "show details" expander. */
    public String getStderrTail() {
        return stderrTail;
    }
}
