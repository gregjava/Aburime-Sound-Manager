/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Utility for running external processes with proper resource management
 */
public class ProcessRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessRunner.class);

    /**
     * Run a command and return exit code
     * @param command  A String list of commands to be run
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @return  The command to be run
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
    public static int runCommand(List<String> command, long timeout, TimeUnit unit) 
            throws IOException, InterruptedException {
        return runCommand(command, timeout, unit, null, null);
    }

    /**
     * Run a command with output callback
     * @param command  A String list of commands to be run
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @param outputCallback  The process output callback
     * @return  The command to be run
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
    public static int runCommand(List<String> command, long timeout, TimeUnit unit, 
                                Consumer<String> outputCallback) 
            throws IOException, InterruptedException {
        return runCommand(command, timeout, unit, outputCallback, null);
    }

    /**
     * Run a command with output callback and custom environment variables
     * @param command  A String list of commands to be run
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @param outputCallback  The process output callback
     * @param environment  Map of environment variables to set (can be null)
     * @return  The exit code of the process
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
public static int runCommand(List<String> command, long timeout, TimeUnit unit, 
                             Consumer<String> outputCallback,
                             Map<String, String> environment) 
        throws IOException, InterruptedException {
    
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        if (environment != null && !environment.isEmpty()) {
            builder.environment().putAll(environment);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Setting environment variables for process:");
                environment.forEach((key, value) -> 
                    LOGGER.debug("  {}={}", key, key.contains("TOKEN") || key.contains("PASSWORD") ? "*****" : value));
            }
        }

        final Process process = builder.start();
        Thread outputReader = null;
        try {
            LOGGER.debug("Executing command: {}", String.join(" ", command));

            if (outputCallback != null) {
                outputReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (Thread.currentThread().isInterrupted()) break;
                            outputCallback.accept(line);
                        }
                    } catch (IOException e) {
                        if (!Thread.currentThread().isInterrupted()) {
                            LOGGER.warn("Error reading process output: {}", e.getMessage());
                        }
                    }
                });
                outputReader.start();
            }

            boolean exited;
            try {
                exited = process.waitFor(timeout, unit);
            } catch (InterruptedException e) {
                // Interrupted while waiting – destroy the process immediately
                if (outputReader != null && outputReader.isAlive()) {
                    outputReader.interrupt();
                }
                process.destroyForcibly();
                Thread.currentThread().interrupt(); // preserve interrupt status
                throw e; // rethrow to caller
            }

            if (!exited) {
                LOGGER.warn("Process timed out after {} {}: {}", timeout, unit, command);
                process.destroyForcibly();
                if (outputReader != null && outputReader.isAlive()) {
                    outputReader.interrupt();
                }
                return -1;
            }

            // Wait for output reader to finish
            if (outputReader != null && outputReader.isAlive()) {
                outputReader.join(2000);
                if (outputReader.isAlive()) {
                    outputReader.interrupt();
                }
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.debug("Process exited with code {}: {}", exitCode, String.join(" ", command));
            } else {
                LOGGER.debug("Process completed successfully");
            }

            return exitCode;

        } catch (Exception e) {
            LOGGER.error("Error executing command: {}", e.getMessage());
            throw e;
        } finally {
            if (process != null && process.isAlive()) {
                try {
                    process.destroyForcibly();
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    LOGGER.warn("Error terminating process: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Run a command and capture all output to a StringBuilder
     * @param command  A String list of commands to be run
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @param outputBuffer  StringBuilder to capture output (can be null)
     * @param environment  Map of environment variables to set (can be null)
     * @return  The exit code of the process
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
    public static int runCommandCaptureOutput(List<String> command, long timeout, TimeUnit unit,
                                             StringBuilder outputBuffer,
                                             Map<String, String> environment) 
            throws IOException, InterruptedException {
        
        return runCommand(command, timeout, unit, line -> {
            if (outputBuffer != null) {
                outputBuffer.append(line).append("\n");
            }
        }, environment);
    }

    /**
     * Run a command with progress tracking
     * @param command  A String list of commands to be run
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @param progressCallback  Consumer that receives progress updates
     * @param environment  Map of environment variables to set (can be null)
     * @return  The exit code of the process
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
    public static int runCommandWithProgress(List<String> command, long timeout, TimeUnit unit,
                                            Consumer<String> progressCallback,
                                            Map<String, String> environment) 
            throws IOException, InterruptedException {
        
        return runCommand(command, timeout, unit, line -> {
            // Filter for progress-related output
            if (line.toLowerCase().contains("progress") || 
                line.toLowerCase().contains("downloading") ||
                line.toLowerCase().contains("loading") ||
                line.toLowerCase().contains("transcribing") ||
                line.toLowerCase().contains("processing") ||
                line.matches(".*\\d+%.*") || // Contains percentage
                line.matches(".*\\d+/\\d+.*")) { // Contains fraction like 1/10
                progressCallback.accept(line);
            }
        }, environment);
    }

    /**
     * Check if a command is available on the system
     * @param commandWithArgs  The command to be executed, including its execution arguments
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @return  A boolean value set to true if the command is available, and false if the command is not.
     */
    public static boolean isCommandAvailable(String commandWithArgs, long timeout, TimeUnit unit) {
        try {
            String[] command;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                command = new String[]{"cmd", "/c", commandWithArgs};
            } else {
                command = new String[]{"sh", "-c", commandWithArgs};
            }
            
            ProcessBuilder builder = new ProcessBuilder(command);
            Process process = builder.start();
            boolean exited = process.waitFor(timeout, unit);
            
            if (!exited) {
                process.destroyForcibly();
                return false;
            }
            
            int exitCode = process.exitValue();
            return exitCode == 0 || exitCode == 1; // Some tools return 1 for help
            
        } catch (IOException | InterruptedException e) {
            LOGGER.debug("Command check failed for: {} ({})", commandWithArgs, e.getMessage());
            return false;
        }
    }

    /**
     * Check if a command is available by running a simple test
     * @param command  The base command to check (without arguments)
     * @param testArg  Test argument (like --version or --help)
     * @param timeoutSeconds  Timeout in seconds
     * @return  true if command is available
     */
    public static boolean checkCommandAvailability(String command, String testArg, int timeoutSeconds) {
        try {
            List<String> cmd = List.of(command, testArg);
            return runCommand(cmd, timeoutSeconds, TimeUnit.SECONDS, null) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read single line output from a command
     * @param command  A String list of commands to be read
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @return  The command to be read
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
    public static String readCommandOutput(List<String> command, long timeout, TimeUnit unit) 
            throws IOException, InterruptedException {
        return readCommandOutput(command, timeout, unit, null);
    }

    /**
     * Read single line output from a command with environment variables
     * @param command  A String list of commands to be read
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @param environment  Map of environment variables to set (can be null)
     * @return  The command output line
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
    public static String readCommandOutput(List<String> command, long timeout, TimeUnit unit,
                                          Map<String, String> environment) 
            throws IOException, InterruptedException {
        
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        
        if (environment != null && !environment.isEmpty()) {
            builder.environment().putAll(environment);
        }
        
        Process process = null;
        try {
            process = builder.start();
            
            // FIX: keep reading until EOF (readLine() returns null) instead of
            // returning as soon as the first matching line is found. Returning
            // early left any further stdout unread on the pipe; if the command
            // wrote more output after the line we wanted (past the OS pipe
            // buffer, typically a handful of KB), the process would block
            // forever trying to write to a full, un-drained pipe — and the
            // waitFor() below would then hang until the timeout on every call,
            // not just occasionally.
            String result = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (result == null && !line.isEmpty() && !isHeaderLine(line)) {
                        result = line;
                    }
                }
            }
            
            boolean exited = process.waitFor(timeout, unit);
            if (!exited) {
                throw new IOException("Command timed out");
            }
            
            if (process.exitValue() != 0) {
                throw new IOException("Command failed with exit code: " + process.exitValue());
            }
            
            return result;
            
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Execute a command and return both exit code and full output
     * @param command  A String list of commands to be run
     * @param timeout  The long representation of the process timeout
     * @param unit  The time unit
     * @param environment  Map of environment variables to set (can be null)
     * @return  A ProcessResult object containing exit code and output
     * @throws java.io.IOException   An Input/Output exception raised on processing error.
     * @throws java.lang.InterruptedException  An interrupt exception raised on processing error.
     */
    public static ProcessResult executeCommand(List<String> command, long timeout, TimeUnit unit,
                                              Map<String, String> environment) 
            throws IOException, InterruptedException {
        
        StringBuilder output = new StringBuilder();
        int exitCode = runCommandCaptureOutput(command, timeout, unit, output, environment);
        return new ProcessResult(exitCode, output.toString());
    }

    private static boolean isHeaderLine(String line) {
        String lower = line.toLowerCase();
        return lower.startsWith("uuid") || 
               lower.startsWith("serialnumber") || 
               lower.startsWith("product_uuid") ||
               lower.startsWith("system-uuid") ||
               lower.startsWith("system-serial-number");
    }

    /**
     * Result container for process execution
     */
    public static class ProcessResult {
        private final int exitCode;
        private final String output;
        
        public ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
        
        public int getExitCode() {
            return exitCode;
        }
        
        public String getOutput() {
            return output;
        }
        
        public boolean isSuccess() {
            return exitCode == 0;
        }
        
        @Override
        public String toString() {
            return String.format("ProcessResult{exitCode=%d, output='%s'}", exitCode, 
                               output.length() > 100 ? output.substring(0, 100) + "..." : output);
        }
    }

    /**
     * Execute a Python script with arguments
     * @param scriptPath  Path to the Python script
     * @param args  Arguments to pass to the script
     * @param timeout  Timeout value
     * @param unit  Timeout unit
     * @param environment  Environment variables
     * @return  ProcessResult with exit code and output
     * @throws IOException  If I/O error occurs
     * @throws InterruptedException  If process is interrupted
     */
    public static ProcessResult executePythonScript(String scriptPath, List<String> args,
                                                   long timeout, TimeUnit unit,
                                                   Map<String, String> environment) 
            throws IOException, InterruptedException {
        
        List<String> command = new java.util.ArrayList<>();
        command.add(resolvePythonExecutable());
        command.add(scriptPath);
        command.addAll(args);
        
        return executeCommand(command, timeout, unit, environment);
    }

    // FIX: was hardcoded to "python". Debian/Ubuntu (since ~20.04), Fedora,
    // and Arch don't ship a "python" symlink at all — only "python3" — so
    // this method failed outright (IOException: Cannot run program "python")
    // on those systems regardless of whether Python was actually installed.
    // Resolve "python3" first and fall back to "python" for systems (mainly
    // Windows, or older Linux) that only provide that name. Cached after the
    // first resolution so we're not spawning a probe process on every call.
    private static volatile String cachedPythonExecutable = null;

    private static String resolvePythonExecutable() {
        String cached = cachedPythonExecutable;
        if (cached != null) return cached;
        String resolved = isCommandAvailable("python3 --version", 5, TimeUnit.SECONDS)
                ? "python3" : "python";
        cachedPythonExecutable = resolved;
        LOGGER.debug("Resolved python executable: {}", resolved);
        return resolved;
    }
}