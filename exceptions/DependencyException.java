/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.exceptions;

/**
 * Thrown when an external dependency cannot be located or invoked.
 *
 * <p>This exception covers failures related to external tools required by
 * the application, including:
 * <ul>
 *   <li>FFmpeg - audio processing</li>
 *   <li>FFprobe - audio duration probing</li>
 *   <li>Whisper/WhisperX - transcription</li>
 *   <li>Python - running WhisperX scripts</li>
 * </ul>
 *
 * <p>Maps to failures currently reported by {@code DependencyManager}'s
 * {@code DependencyStatus} objects. Wrapping those in an exception (rather
 * than a status object callers must remember to check) lets call sites use
 * normal control flow and lets the UI layer catch this specific type to show
 * the installation hint — instead of {@code MainWindow} catching a bare
 * {@code Exception} and showing a generic "processing failed" dialog for what
 * is actually a simple "FFmpeg isn't on your PATH" problem.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see AudioManagerException
 * @see DependencyManager
 */
public class DependencyException extends AudioManagerException {

    private final String dependencyName;
    private final String installationHint;

    /**
     * Constructs a new DependencyException.
     *
     * <p>Missing dependencies are always marked as recoverable:
     * install the dependency and retry.</p>
     *
     * @param dependencyName the name of the missing dependency (e.g., "FFmpeg")
     * @param technicalMessage the technical message for logging
     * @param userMessage the user-friendly message for display
     * @param installationHint installation instructions for the user
     */
    public DependencyException(String dependencyName, String technicalMessage,
                                String userMessage, String installationHint) {
        super(technicalMessage, userMessage, true);
        this.dependencyName = dependencyName;
        this.installationHint = installationHint;
    }

    /**
     * Returns the name of the missing dependency.
     *
     * @return the dependency name (e.g., "FFmpeg", "Whisper CLI")
     */
    public String getDependencyName() {
        return dependencyName;
    }

    /**
     * Returns the installation hint for the missing dependency.
     *
     * <p>This is a multi-line string suitable for an expandable detail pane
     * in a dialog.</p>
     *
     * @return the installation instructions
     */
    public String getInstallationHint() {
        return installationHint;
    }

    /**
     * Returns whether an installation hint is available.
     *
     * @return {@code true} if an installation hint is present
     */
    public boolean hasInstallationHint() {
        return installationHint != null && !installationHint.isBlank();
    }
}