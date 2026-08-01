/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when an external dependency (FFmpeg, FFprobe, Whisper/WhisperX,
 * Python) cannot be located or invoked.
 *
 * <p>Maps to failures currently reported by {@code DependencyManager}'s
 * {@code DependencyStatus} objects. Wrapping those in an exception (rather
 * than a status object callers must remember to check) lets call sites use
 * normal control flow and lets the UI layer catch this specific type to show
 * the installation hint — instead of {@code MainWindow} catching a bare
 * {@code Exception} and showing a generic "processing failed" dialog for what
 * is actually a simple "FFmpeg isn't on your PATH" problem.</p>
 */
public class DependencyException extends AudioManagerException {

    private final String dependencyName;
    private final String installationHint;

    public DependencyException(String dependencyName, String technicalMessage,
                                String userMessage, String installationHint) {
        // Missing dependencies are always recoverable: install it, retry.
        super(technicalMessage, userMessage, true);
        this.dependencyName = dependencyName;
        this.installationHint = installationHint;
    }

    /** e.g. "FFmpeg", "Whisper CLI", "FFmpeg (WhisperX visibility)". */
    public String getDependencyName() {
        return dependencyName;
    }

    /** Multi-line install instructions, suitable for an expandable detail pane. */
    public String getInstallationHint() {
        return installationHint;
    }

    public boolean hasInstallationHint() {
        return installationHint != null && !installationHint.isBlank();
    }
}
