/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import java.io.File;

public class FileResult {
    public final File    file;
    public final boolean success;
    public final String  message;

    public FileResult(File file, boolean success, String message) {
        this.file = file; this.success = success; this.message = message;
    }
}