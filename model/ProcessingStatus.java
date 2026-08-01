/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

/**
 * Status enumeration for batch file processing
 */
public enum ProcessingStatus {
    PENDING,
    PROCESSING, 
    COMPLETED,
    FAILED,
    CANCELLED;

    @Override
    public String toString() {
        return name();
    }
}