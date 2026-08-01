/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted state of a batch run, used to resume interrupted sessions.
 */
public class BatchState {

    private String batchId;
    private long batchStartTime;
    private int currentFileIndex = -1;
    private List<FileState> files = new ArrayList<>();

    // ── top-level getters / setters ──────────────────────────────────────────

    public String getBatchId()                    { return batchId; }
    public void   setBatchId(String batchId)      { this.batchId = batchId; }

    public long   getBatchStartTime()                      { return batchStartTime; }
    public void   setBatchStartTime(long batchStartTime)   { this.batchStartTime = batchStartTime; }

    public int    getCurrentFileIndex()                      { return currentFileIndex; }
    public void   setCurrentFileIndex(int currentFileIndex)  { this.currentFileIndex = currentFileIndex; }

    public List<FileState> getFiles()                  { return files; }
    public void            setFiles(List<FileState> f) { this.files = f; }

    // ── nested FileState ─────────────────────────────────────────────────────

    public static class FileState {

        private String filePath;
        private String status;
        private double progress;
        private String errorMessage;

        public String getFilePath()                    { return filePath; }
        public void   setFilePath(String filePath)     { this.filePath = filePath; }

        public String getStatus()                 { return status; }
        public void   setStatus(String status)    { this.status = status; }

        public double getProgress()                { return progress; }
        public void   setProgress(double progress) { this.progress = progress; }

        public String getErrorMessage()                      { return errorMessage; }
        public void   setErrorMessage(String errorMessage)   { this.errorMessage = errorMessage; }
    }
}