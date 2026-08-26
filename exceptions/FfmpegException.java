/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when an FFmpeg (or FFprobe) subprocess invocation fails or times out.
 *
 * <p>This exception is distinct from {@link DependencyException}, which
 * means FFmpeg couldn't even be <em>found</em>. This exception means
 * FFmpeg ran and rejected the input, or the process had to be killed.</p>
 *
 * <p>Distinguishing this from a generic {@code IOException} lets the UI say
 * "this specific file appears to be corrupt or in an unsupported format"
 * instead of the current generic failure alert, and lets batch-processing
 * code decide to skip-and-continue rather than abort the whole run.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see AudioManagerException
 * @see DependencyException
 */
public class FfmpegException extends AudioManagerException {

    private final int exitCode;
    private final String stderrTail;

    /**
     * Best-effort, non-exhaustive hints for common FFmpeg/FFprobe exit codes.
     * Intentionally conservative — only codes with an unambiguous, common
     * real-world cause are mapped; anything else falls back to the
     * exception's own {@code getUserMessage()} plus the raw stderr tail
     * rather than guessing.
     */
    private static final java.util.Map<Integer, String> EXIT_CODE_HINTS = java.util.Map.of(
            1,   "FFmpeg reported a general processing error — the file may be corrupt, truncated, or use an unsupported codec/container.",
            127, "FFmpeg does not appear to be installed, or its location isn't on PATH.",
            137, "The process was killed by the OS — this usually means it ran out of memory.",
            143, "The process was terminated (e.g. cancelled by the user, or it exceeded a timeout)."
    );

    /**
     * Constructs a new FfmpegException with exit code and stderr tail.
     *
     * <p>A single file failing FFmpeg processing shouldn't be fatal to a batch —
     * the exception is marked recoverable so batch code can skip-and-continue
     * rather than abort.</p>
     *
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     * @param exitCode the FFmpeg process exit code
     * @param stderrTail the last portion of stderr output
     */
    public FfmpegException(String technicalMessage, String userMessage,
                            int exitCode, String stderrTail) {
        super(technicalMessage, userMessage, true);
        this.exitCode = exitCode;
        this.stderrTail = stderrTail;
    }

    /**
     * Constructs a new FfmpegException with a cause.
     *
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     * @param cause the underlying cause of this exception
     */
    public FfmpegException(String technicalMessage, String userMessage, Throwable cause) {
        super(technicalMessage, userMessage, true, cause);
        this.exitCode = -1;
        this.stderrTail = null;
    }

    /**
     * Returns the FFmpeg process exit code.
     *
     * @return the exit code, or {@code -1} if no exit code was produced
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Returns the last portion of stderr captured before failure.
     *
     * <p>This is useful in a "Show Details" expander in a dialog.</p>
     *
     * @return the stderr tail, or {@code null} if not available
     */
    public String getStderrTail() {
        return stderrTail;
    }

    /**
     * Returns a short, specific explanation for the exit code.
     *
     * <p>This method returns a hint only for well-known exit codes.
     * Deliberately does not attempt to explain every possible exit code —
     * an incorrect guess is worse than no guess. Callers should fall back to
     * {@link #getUserMessage()} if this returns {@code null}.</p>
     *
     * @return an explanation for the exit code, or {@code null} if not known
     */
    public String getExitCodeHint() {
        return EXIT_CODE_HINTS.get(exitCode);
    }
}