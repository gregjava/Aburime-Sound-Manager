/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * GPU configuration and detection for CUDA support.
 *
 * <p>This class detects NVIDIA GPUs and provides configuration for
 * WhisperX to use CUDA acceleration when available. Key features:
 * <ul>
 *   <li><b>GPU detection:</b> Uses {@code nvidia-smi} to detect NVIDIA GPUs</li>
 *   <li><b>Capability reporting:</b> Reports GPU name, memory, compute capability</li>
 *   <li><b>CUDA core estimation:</b> Estimates CUDA core count from GPU name</li>
 *   <li><b>Device string generation:</b> Provides {@code "cuda"} or {@code "cpu"} for WhisperX</li>
 *   <li><b>Singleton pattern:</b> Single instance shared across the application</li>
 * </ul>
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * GpuConfig gpu = GpuConfig.getInstance();
 * gpu.detectGpu();
 * if (gpu.isGpuAvailable()) {
 *     String device = gpu.getDeviceString(); // "cuda"
 *     String computeType = gpu.getComputeType(); // "float16"
 * }
 * }</pre>
 *
 * <p><b>Thread-safety:</b> All methods are thread-safe with lazy
 * initialisation and volatile fields.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see WhisperXTranscriptionService
 */
public class GpuConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(GpuConfig.class);

    private static volatile GpuConfig instance;
    private volatile boolean gpuAvailable = false;
    private volatile String gpuName = "Unknown";
    private volatile int cudaCores = 0;
    private volatile long gpuMemoryMB = 0;
    private volatile String computeCapability = "Unknown";
    private volatile boolean initialized = false;

    private GpuConfig() {
        // Private constructor for singleton
    }

    /**
     * Returns the singleton instance of GpuConfig.
     *
     * @return the GpuConfig instance
     */
    public static GpuConfig getInstance() {
        if (instance == null) {
            synchronized (GpuConfig.class) {
                if (instance == null) {
                    instance = new GpuConfig();
                }
            }
        }
        return instance;
    }

    /**
     * Detects GPU availability and capabilities.
     *
     * <p>This method is called once during application startup and caches
     * the results. It uses {@code nvidia-smi} to query GPU information.</p>
     */
    public synchronized void detectGpu() {
        if (initialized) {
            return;
        }

        LOGGER.info("Detecting GPU...");

        // Check if nvidia-smi is available
        if (!isNvidiaSmiAvailable()) {
            LOGGER.info("No NVIDIA GPU detected (nvidia-smi not found)");
            initialized = true;
            return;
        }

        try {
            // Get GPU information from nvidia-smi
            Process process = new ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=name,memory.total,compute_cap,driver_version",
                "--format=csv,noheader"
            ).start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    parseGpuInfo(line);
                    gpuAvailable = true;
                    LOGGER.info("✅ GPU detected: {} ({} MB, Compute Capability {}, CUDA Cores: {})",
                        gpuName, gpuMemoryMB, computeCapability, cudaCores);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("nvidia-smi timed out");
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to detect GPU: {}", e.getMessage());
        }

        initialized = true;
    }

    /**
     * Checks if nvidia-smi is available on the system.
     *
     * @return {@code true} if nvidia-smi is available
     */
    private boolean isNvidiaSmiAvailable() {
        try {
            Process process = new ProcessBuilder("nvidia-smi", "--version")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOGGER.debug("nvidia-smi not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parses GPU information from nvidia-smi output.
     *
     * @param line the CSV line from nvidia-smi
     */
    private void parseGpuInfo(String line) {
        String[] parts = line.split(", ");
        if (parts.length >= 3) {
            gpuName = parts[0].trim();
            try {
                gpuMemoryMB = parseMemorySize(parts[1].trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Failed to parse GPU memory: {}", parts[1]);
            }
            computeCapability = parts[2].trim();

            // Estimate CUDA cores based on GPU name
            cudaCores = estimateCudaCores(gpuName);
        }
    }

    /**
     * Parses memory size from strings like "8192 MiB" or "16 GiB".
     *
     * @param memoryStr the memory string
     * @return the memory in megabytes
     */
    private long parseMemorySize(String memoryStr) {
        String[] parts = memoryStr.split(" ");
        double value = Double.parseDouble(parts[0]);
        String unit = parts.length > 1 ? parts[1] : "";

        if (unit.toLowerCase().contains("gi") || unit.toLowerCase().contains("g")) {
            return (long) (value * 1024);
        }
        return (long) value;
    }

    /**
     * Estimates CUDA core count based on GPU name.
     *
     * <p>This is a rough estimate for display purposes only.</p>
     *
     * @param gpuName the GPU name
     * @return the estimated number of CUDA cores
     */
    private int estimateCudaCores(String gpuName) {
        String name = gpuName.toLowerCase();
        if (name.contains("a100")) return 6912;
        if (name.contains("v100")) return 5120;
        if (name.contains("rtx 4090")) return 16384;
        if (name.contains("rtx 4080")) return 9728;
        if (name.contains("rtx 4070")) return 5888;
        if (name.contains("rtx 4060")) return 3072;
        if (name.contains("rtx 3090")) return 10496;
        if (name.contains("rtx 3080")) return 8704;
        if (name.contains("rtx 3070")) return 5888;
        if (name.contains("rtx 3060")) return 3584;
        if (name.contains("titan")) return 4608;
        if (name.contains("quadro")) return 3072;
        if (name.contains("tesla")) return 4096;
        return 0;
    }

    // ========================================================================
    //  Getters
    // ========================================================================

    /**
     * Returns whether a CUDA-capable GPU is available.
     *
     * @return {@code true} if a GPU is available
     */
    public boolean isGpuAvailable() {
        if (!initialized) {
            detectGpu();
        }
        return gpuAvailable;
    }

    /**
     * Returns the GPU name.
     *
     * @return the GPU name (e.g., "NVIDIA GeForce RTX 3080")
     */
    public String getGpuName() {
        if (!initialized) {
            detectGpu();
        }
        return gpuName;
    }

    /**
     * Returns the GPU memory in megabytes.
     *
     * @return the GPU memory in MB
     */
    public long getGpuMemoryMB() {
        if (!initialized) {
            detectGpu();
        }
        return gpuMemoryMB;
    }

    /**
     * Returns the CUDA compute capability.
     *
     * @return the compute capability (e.g., "8.6")
     */
    public String getComputeCapability() {
        if (!initialized) {
            detectGpu();
        }
        return computeCapability;
    }

    /**
     * Returns the estimated number of CUDA cores.
     *
     * @return the estimated CUDA core count
     */
    public int getCudaCores() {
        if (!initialized) {
            detectGpu();
        }
        return cudaCores;
    }

    /**
     * Returns whether GPU acceleration should be used.
     *
     * <p>This checks both availability and user preference.</p>
     *
     * @return {@code true} if GPU should be used
     */
    public boolean shouldUseGpu() {
        return isGpuAvailable(); // && userPrefersGpu();
    }

    /**
     * Returns the device string for WhisperX.
     *
     * @return {@code "cuda"} if GPU is available and enabled, otherwise {@code "cpu"}
     */
    public String getDeviceString() {
        return shouldUseGpu() ? "cuda" : "cpu";
    }

    /**
     * Returns the compute type for WhisperX.
     *
     * @return {@code "float16"} for CUDA, {@code "int8"} for CPU
     */
    public String getComputeType() {
        return shouldUseGpu() ? "float16" : "int8";
    }

    /**
     * Returns a summary of GPU configuration for display.
     *
     * @return a human-readable GPU summary
     */
    public String getGpuSummary() {
        if (!isGpuAvailable()) {
            return "No GPU detected (CPU mode)";
        }
        return String.format(
            "GPU: %s (%d MB, Compute %s, %d cores) — CUDA enabled",
            gpuName, gpuMemoryMB, computeCapability, cudaCores
        );
    }

    /**
     * Resets GPU detection (for testing or after driver updates).
     */
    public synchronized void resetDetection() {
        initialized = false;
        gpuAvailable = false;
        gpuName = "Unknown";
        cudaCores = 0;
        gpuMemoryMB = 0;
        computeCapability = "Unknown";
    }
}