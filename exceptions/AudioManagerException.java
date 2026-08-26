/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Root of the AudioManager typed-exception hierarchy.
 *
 * <p>This class serves as the base for all application-specific exceptions,
 * providing consistent error handling with user-friendly messages and
 * recoverability indicators.</p>
 *
 * <h2>Why this exists</h2>
 * The pre-existing code (notably {@code MainWindow}, with 18 separate
 * {@code catch (Exception e)} blocks) has no way to distinguish "FFmpeg isn't
 * installed" from "the audio file is corrupt" from "we ran out of disk
 * space" — every failure surfaces as the same generic alert dialog, and every
 * catch site has to re-derive what actually went wrong from a message
 * string. That makes it impossible to, e.g., offer a "Download FFmpeg" button
 * only when it would actually help.
 *
 * <p>Each subtype below corresponds to one of the failure categories that
 * repeatedly show up in this codebase's own bug-fix comments (dependency
 * resolution, subprocess execution, model lookup, transcription, and
 * batch/segment output integrity). Catch the specific subtype you can act on;
 * catch {@code AudioManagerException} only as a final safety net, and even
 * then log {@code getUserMessage()} rather than a raw stack trace to the UI.
 *
 * <h2>Usage pattern</h2>
 * <pre>{@code
 * try {
 *     dependencyManager.checkFFmpeg();
 * } catch (DependencyException e) {
 *     if (e.isRecoverable()) {
 *         showInstallPrompt(e.getUserMessage(), e.getInstallationHint());
 *     } else {
 *         showFatalError(e.getUserMessage());
 *     }
 * } catch (AudioManagerException e) {
 *     LOGGER.error("Unhandled AudioManager failure", e);
 *     showGenericError(e.getUserMessage());
 * }
 * }</pre>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see DependencyException
 * @see FfmpegException
 * @see ModelNotFoundException
 * @see TranscriptionException
 * @see OutputIntegrityException
 */
public class AudioManagerException extends Exception {

    /**
     * Short, non-technical message safe to show directly in a dialog.
     * Distinct from {@link #getMessage()}, which may carry technical detail
     * (stderr output, stack context) meant for logs, not end users.
     */
    private final String userMessage;

    /**
     * Whether the operation that threw this can plausibly be retried or
     * fixed by the user without restarting the app (e.g. "install FFmpeg and
     * click Retry") vs. requiring the batch/segment to be abandoned.
     */
    private final boolean recoverable;

    /**
     * Constructs a new AudioManagerException with a technical message,
     * user-facing message, and recoverability flag.
     *
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     * @param recoverable {@code true} if the operation can be retried
     */
    public AudioManagerException(String technicalMessage, String userMessage, boolean recoverable) {
        super(technicalMessage);
        this.userMessage = userMessage;
        this.recoverable = recoverable;
    }

    /**
     * Constructs a new AudioManagerException with a technical message,
     * user-facing message, recoverability flag, and cause.
     *
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     * @param recoverable {@code true} if the operation can be retried
     * @param cause the underlying cause of this exception
     */
    public AudioManagerException(String technicalMessage, String userMessage, boolean recoverable, Throwable cause) {
        super(technicalMessage, cause);
        this.userMessage = userMessage;
        this.recoverable = recoverable;
    }

    /**
     * Returns the user-facing message.
     *
     * <p>This message is safe to render directly in a dialog or status label.
     * If no user message was provided, a generic message is returned.</p>
     *
     * @return the user-friendly message
     */
    public String getUserMessage() {
        return userMessage != null ? userMessage : "An unexpected error occurred.";
    }

    /**
     * Returns whether the operation can be retried.
     *
     * @return {@code true} if the user can plausibly retry after taking some action
     */
    public boolean isRecoverable() {
        return recoverable;
    }
}