/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.exceptions.ModelNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages model caching, verification, and download tracking for WhisperX models.
 *
 * <p>This class provides comprehensive model management for the application,
 * including:
 * <ul>
 *   <li><b>Model caching:</b> Stores models in a platform-appropriate cache directory</li>
 *   <li><b>Model verification:</b> Validates model integrity using size and hash checking</li>
 *   <li><b>HuggingFace integration:</b> Finds models in HuggingFace's cache</li>
 *   <li><b>Resumable downloads:</b> Tracks partial downloads for resumption</li>
 *   <li><b>Integrity metadata:</b> Persists model verification status across sessions</li>
 *   <li><b>Cache cleanup:</b> Removes corrupted or outdated model files</li>
 * </ul>
 *
 * <p><b>Cache locations:</b>
 * <ul>
 *   <li>Windows: {@code %LOCALAPPDATA%\AudioManager\models}</li>
 *   <li>macOS: {@code ~/Library/Caches/AudioManager/models}</li>
 *   <li>Linux: {@code ~/.cache/audiomanager/models}</li>
 * </ul>
 *
 * <p><b>Model metadata:</b> Model integrity information is stored in
 * {@code model_integrity.json} within the cache directory.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see HuggingFaceCacheResolver
 * @see PartialDownloadProgress
 */
public class ModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelManager.class);

    private final Path cacheDir;
    private final Path modelIntegrityFile;
    private final Map<String, ModelMetadata> modelMetadata;
    private final Set<String> verifiedModels;
    private final Gson gson;

    /**
     * Internal model metadata structure for persistence.
     */
    private static class ModelMetadata {
        String modelName;
        String expectedHash;
        long expectedSize;
        boolean verified;
        long lastVerified;
        String modelType;

        ModelMetadata(String modelName, String expectedHash, long expectedSize, String modelType) {
            this.modelName = modelName;
            this.expectedHash = expectedHash;
            this.expectedSize = expectedSize;
            this.verified = false;
            this.lastVerified = 0;
            this.modelType = modelType;
        }
    }

    /**
     * Constructs a new ModelManager and initialises the cache directory.
     */
    public ModelManager() {
        this.gson = new Gson();
        this.cacheDir = getStableCacheDir();
        this.modelIntegrityFile = cacheDir.resolve("model_integrity.json");
        this.modelMetadata = Collections.synchronizedMap(new HashMap<>());
        this.verifiedModels = Collections.newSetFromMap(new ConcurrentHashMap<>());

        try {
            Files.createDirectories(cacheDir);
            LOGGER.info("Model cache directory: {}", cacheDir.toAbsolutePath());

            // Load existing metadata
            loadModelMetadata();
        } catch (IOException e) {
            LOGGER.error("Failed to create cache directory", e);
        }
    }

    /**
     * Returns the platform-appropriate cache directory.
     *
     * @return the cache directory path
     */
    public Path getStableCacheDir() {
        String os = System.getProperty("os.name").toLowerCase();
        Path baseCacheDir;

        if (os.contains("win")) {
            baseCacheDir = Paths.get(System.getenv("LOCALAPPDATA"), "AudioManager", "models");
        } else if (os.contains("mac")) {
            baseCacheDir = Paths.get(System.getProperty("user.home"), "Library", "Caches", "AudioManager", "models");
        } else {
            baseCacheDir = Paths.get(System.getProperty("user.home"), ".cache", "audiomanager", "models");
        }

        return baseCacheDir;
    }

    // ========================================================================
    //  Model Validation
    // ========================================================================

    /**
     * Checks if a model is valid and available in the cache.
     *
     * <p>This method checks:
     * <ol>
     *   <li>Already verified in metadata</li>
     *   <li>Integrity verification of cached model</li>
     *   <li>Presence in HuggingFace cache</li>
     *   <li>Presence in the managed cache root</li>
     * </ol>
     *
     * @param modelName the model name (e.g., "base", "small")
     * @param modelType the model type (e.g., "whisper", "whisperx")
     * @return {@code true} if the model is valid and available
     */
    public boolean isModelValid(String modelName, String modelType) {
        String key = createModelKey(modelName, modelType);

        // First check: already verified in our metadata
        if (verifiedModels.contains(key)) {
            LOGGER.debug("Model {} (type: {}) already verified", modelName, modelType);
            return true;
        }

        // Second check: check our metadata and verify integrity
        ModelMetadata metadata = modelMetadata.get(key);
        if (metadata != null) {
            try {
                boolean isValid = verifyModelIntegrity(key, metadata);
                if (isValid) {
                    verifiedModels.add(key);
                    metadata.verified = true;
                    metadata.lastVerified = System.currentTimeMillis();
                    saveModelMetadata();
                    return true;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to verify model {}: {}", modelName, e.getMessage());
            }
        }

        // Third check: check if model exists in HuggingFace cache
        if (isModelInHuggingFaceCache(modelName, modelType)) {
            LOGGER.info("✓ Model '{}' found in HuggingFace cache", modelName);
            long size = getModelSizeFromHuggingFaceCache(modelName, modelType);
            metadata = new ModelMetadata(modelName, null, size, modelType);
            metadata.verified = true;
            metadata.lastVerified = System.currentTimeMillis();
            modelMetadata.put(key, metadata);
            verifiedModels.add(key);
            saveModelMetadata();
            return true;
        }

        // Fourth check: check if model exists directly in our managed cache root
        if (isModelInManagedCacheRoot(modelName, modelType)) {
            LOGGER.info("✓ Model '{}' found in managed cache root", modelName);
            long size = getModelSizeFromManagedCacheRoot(modelName, modelType);
            metadata = new ModelMetadata(modelName, null, size, modelType);
            metadata.verified = true;
            metadata.lastVerified = System.currentTimeMillis();
            modelMetadata.put(key, metadata);
            verifiedModels.add(key);
            saveModelMetadata();
            return true;
        }

        LOGGER.debug("Model {} not found in any cache", modelName);
        return false;
    }

    /**
     * Checks if a model is cached (even if not verified).
     *
     * @param modelName the model name
     * @param modelType the model type
     * @return {@code true} if the model is in the cache
     */
    public boolean isModelCached(String modelName, String modelType) {
        String key = createModelKey(modelName, modelType);
        Path modelPath = getModelCachePath(key);
        return Files.exists(modelPath);
    }

    /**
     * Returns the expected size of a model for resumable downloads.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @return the expected size in bytes, or {@code -1} if unknown
     */
    public long getModelExpectedSize(String modelName, String modelType) {
        String key = createModelKey(modelName, modelType);
        ModelMetadata metadata = modelMetadata.get(key);
        return metadata != null ? metadata.expectedSize : -1;
    }

    // ========================================================================
    //  Download Management
    // ========================================================================

    /**
     * Registers a model that will be downloaded.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @param expectedHash the expected SHA-256 hash (may be {@code null})
     * @param expectedSize the expected size in bytes
     */
    public void registerModel(String modelName, String modelType, String expectedHash, long expectedSize) {
        String key = createModelKey(modelName, modelType);
        ModelMetadata metadata = new ModelMetadata(modelName, expectedHash, expectedSize, modelType);
        modelMetadata.put(key, metadata);
        saveModelMetadata();
        LOGGER.info("Registered model {} (type: {}) for download. Expected size: {} bytes",
                   modelName, modelType, expectedSize);
    }

    /**
     * Marks a model as successfully downloaded and verified.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @param actualHash the actual SHA-256 hash (may be {@code null})
     * @param actualSize the actual size in bytes
     */
    public void markModelDownloaded(String modelName, String modelType, String actualHash, long actualSize) {
        String key = createModelKey(modelName, modelType);
        ModelMetadata metadata = modelMetadata.get(key);
        if (metadata != null) {
            metadata.expectedHash = actualHash;
            metadata.expectedSize = actualSize;
            metadata.verified = true;
            metadata.lastVerified = System.currentTimeMillis();
            verifiedModels.add(key);
            saveModelMetadata();
            LOGGER.info("Model {} (type: {}) marked as successfully downloaded and verified. Size: {} bytes",
                       modelName, modelType, actualSize);
        }
    }
    
    /**
     * Old method for backward compatibility
     */
    public boolean verifyInstallation() {
        // Check if we have at least one valid model
        return !verifiedModels.isEmpty() || isModelValid("base", "whisper");
    }

    // ========================================================================
    //  Partial Download Progress
    // ========================================================================

    /**
     * Tracks partial download progress for resumable downloads.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @param downloadedBytes the number of bytes downloaded so far
     * @param totalBytes the total expected size
     */
    public void trackPartialDownload(String modelName, String modelType,
                                        long downloadedBytes, long totalBytes) {
        String key = createModelKey(modelName, modelType);
        ModelMetadata metadata = modelMetadata.get(key);

        if (metadata == null) {
            metadata = new ModelMetadata(modelName, null, totalBytes, modelType);
            modelMetadata.put(key, metadata);
        }

        metadata.expectedSize = totalBytes;
        metadata.verified = false;

        try {
            Path progressFile = getPartialProgressFile(modelName, modelType);
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("downloadedBytes", downloadedBytes);
            progressData.put("totalBytes", totalBytes);
            progressData.put("lastUpdated", System.currentTimeMillis());
            progressData.put("modelName", modelName);
            progressData.put("modelType", modelType);

            String json = gson.toJson(progressData);
            Files.writeString(progressFile, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            LOGGER.debug("Tracked partial download: {}/{} bytes for {}", 
                                    downloadedBytes, totalBytes, key);
        } catch (Exception e) {
            LOGGER.warn("Failed to track partial download progress", e);
        }
    }

    /**
     * Returns the partial download progress for a model.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @return the partial download progress, or {@code null} if none exists
     */
    public PartialDownloadProgress getPartialDownloadProgress(String modelName, String modelType) {
        try {
            Path progressFile = getPartialProgressFile(modelName, modelType);
            if (!Files.exists(progressFile)) {
                    return null;
            }

            String json = Files.readString(progressFile, StandardCharsets.UTF_8);
            Map<String, Object> data = gson.fromJson(json,
                    new TypeToken<Map<String, Object>>(){}.getType());

            long downloadedBytes = ((Number) data.get("downloadedBytes")).longValue();
            long totalBytes = ((Number) data.get("totalBytes")).longValue();
            long lastUpdated = ((Number) data.get("lastUpdated")).longValue();

            // Check if progress is stale (older than 24 hours)
            if (System.currentTimeMillis() - lastUpdated > 24 * 60 * 60 * 1000) {
                    LOGGER.info("Stale partial download detected, removing...");
                    Files.deleteIfExists(progressFile);
                    return null;
            }

            return new PartialDownloadProgress(downloadedBytes, totalBytes, lastUpdated);

        } catch (Exception e) {
            LOGGER.warn("Failed to read partial download progress", e);
            return null;
        }
    }

    /**
     * Clears partial download tracking for a model.
     *
     * @param modelName the model name
     * @param modelType the model type
     */
    public void clearPartialDownload(String modelName, String modelType) {
        try {
            Path progressFile = getPartialProgressFile(modelName, modelType);
            Files.deleteIfExists(progressFile);
            LOGGER.debug("Cleared partial download tracking for {}:{}", modelName, modelType);
        } catch (Exception e) {
            LOGGER.warn("Failed to clear partial download tracking", e);
        }
    }

    // ========================================================================
    //  Model Path Resolution
    // ========================================================================

    /**
     * Finds the actual path where a model is cached.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @return the model path, or {@code null} if not found
     */
    public Path findModelPath(String modelName, String modelType) {
        String key = createModelKey(modelName, modelType);

        // 1. Check our managed cache (directly under cacheDir)
        Path rootPath = cacheDir.resolve(key);
        if (hasModelFilesRecursive(rootPath)) return rootPath;

        // 2. Check the old nested "models" folder
        Path nestedPath = getModelCachePath(key);
        if (hasModelFilesRecursive(nestedPath)) return nestedPath;

        // 3. Check our own cacheDir directly under the HF folder-name pattern
        Path ownCacheHfStyle = cacheDir.resolve(HuggingFaceCacheResolver.folderNameFor(modelName));
        if (hasModelFilesRecursive(ownCacheHfStyle)) return ownCacheHfStyle;

        // 4. Delegate to the shared HuggingFace cache resolver
        return HuggingFaceCacheResolver.resolve(modelName).orElse(null);
    }

    /**
     * Finds a model path or throws a typed exception if not found.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @return the model path
     * @throws ModelNotFoundException if the model is not found in any cache location
     */
    public Path requireModelPath(String modelName, String modelType) throws ModelNotFoundException {
        Path path = findModelPath(modelName, modelType);
        if (path == null) {
            throw new ModelNotFoundException(modelName, modelType,
                "The '" + modelName + "' model isn't downloaded yet. " +
                "Select a different model, or download this one, and try again.");
        }
        return path;
    }

    // ========================================================================
    //  Cache Cleanup
    // ========================================================================

    /**
     * Clears corrupted cache entries and outdated models.
     */
    public void clearCorruptedCache() {
        LOGGER.info("Clearing corrupted cache entries");

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ModelMetadata> entry : modelMetadata.entrySet()) {
            String modelKey = entry.getKey();
            ModelMetadata metadata = entry.getValue();

            if (!metadata.verified ||
                (System.currentTimeMillis() - metadata.lastVerified) > 7 * 24 * 60 * 60 * 1000L) {
                // Not verified or not verified in last 7 days
                try {
                    Path modelDir = getModelCachePath(modelKey);
                    if (Files.exists(modelDir)) {
                        deleteDirectory(modelDir);
                        LOGGER.info("Removed potentially corrupted model: {}", modelKey);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to remove model {}: {}", modelKey, e.getMessage());
                }
                toRemove.add(modelKey);
            }
        }

        toRemove.forEach(modelMetadata::remove);
        toRemove.forEach(verifiedModels::remove);
        saveModelMetadata();
        clearTraditionalCacheDirs();
    }

    // ========================================================================
    //  Debugging
    // ========================================================================

    /**
     * Prints cache contents for debugging purposes.
     */
    public void debugCacheContents() {
        LOGGER.info("🔍 Debugging cache contents:");
        LOGGER.info("Managed cache dir: {}", cacheDir);

        try {
            if (Files.exists(cacheDir)) {
                Files.walk(cacheDir, 2)
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            long fileCount = Files.list(dir)
                                .filter(Files::isRegularFile)
                                .count();
                            if (fileCount > 0) {
                                LOGGER.info("  📁 {} ({} files)", dir, fileCount);
                            }
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
            }
        } catch (IOException e) {
            LOGGER.warn("Error walking cache dir", e);
        }

        // Check HuggingFace cache
        String userHome = System.getProperty("user.home");
        Path hfCache = Paths.get(userHome, ".cache", "huggingface", "hub");
        LOGGER.info("HuggingFace cache: {}", hfCache);

        if (Files.exists(hfCache)) {
            try {
                Files.list(hfCache)
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        String name = dir.getFileName().toString();
                        if (name.contains("whisper")) {
                            try {
                                long size = Files.walk(dir)
                                    .filter(Files::isRegularFile)
                                    .mapToLong(p -> {
                                        try { return Files.size(p); }
                                        catch (IOException e) { return 0; }
                                    })
                                    .sum();
                                LOGGER.info("  📁 {} ({})", name, formatBytes(size));
                            } catch (IOException e) {
                                LOGGER.info("  📁 {}", name);
                            }
                        }
                    });
            } catch (IOException e) {
                LOGGER.warn("Error reading HF cache", e);
            }
        }
    }

    // ========================================================================
    //  Private Helpers
    // ========================================================================

    /**
     * Gets the current downloaded size for resumable downloads.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @return the current download size in bytes
     */
    public long getCurrentDownloadSize(String modelName, String modelType) {
        try {
            String key = createModelKey(modelName, modelType);
            Path modelDir = getModelCachePath(key);
            long totalSize = 0;

            if (Files.exists(modelDir)) {
                totalSize = Files.walk(modelDir)
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try { return Files.size(path); } catch (IOException e) { return 0; }
                    })
                    .sum();
            }

            Path hfPath = findModelPath(modelName, modelType);
            if (hfPath != null && !hfPath.equals(modelDir)) {
                long hfSize = Files.walk(hfPath)
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try { return Files.size(path); } catch (IOException e) { return 0; }
                    })
                    .sum();
                totalSize += hfSize;
            }

            return totalSize;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Gets the model cache path for a model key.
     *
     * @param modelKey the model key
     * @return the cache path
     */
    public Path getModelCachePath(String modelKey) {
        return cacheDir.resolve("models").resolve(modelKey);
    }

    /**
     * Helper to create a model key from name and type.
     */
    private String createModelKey(String modelName, String modelType) {
        return modelType + "_" + modelName.replace("/", "_").replace(":", "_");
    }

    /**
     * Checks if a model exists in the managed cache root.
     */
    private boolean isModelInManagedCacheRoot(String modelName, String modelType) {
        Path modelPath = cacheDir.resolve(modelName);
        if (Files.exists(modelPath) && Files.isDirectory(modelPath)) {
            try {
                long fileCount = Files.list(modelPath)
                    .filter(Files::isRegularFile)
                    .count();
                if (fileCount > 0) {
                    LOGGER.info("Found model in managed cache root: {}", modelPath);
                    return true;
                }
            } catch (IOException e) {
                LOGGER.debug("Error checking root cache for {}: {}", modelName, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Gets the model size from the managed cache root.
     */
    private long getModelSizeFromManagedCacheRoot(String modelName, String modelType) {
        Path modelPath = cacheDir.resolve(modelName);
        if (!Files.exists(modelPath)) return 0;
        try {
            return Files.walk(modelPath)
                .filter(Files::isRegularFile)
                .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                .sum();
        } catch (IOException e) {
            LOGGER.warn("Error calculating size for root cache model {}: {}", modelName, e.getMessage());
            return 0;
        }
    }

    /**
     * Checks if a model exists in the HuggingFace cache.
     */
    private boolean isModelInHuggingFaceCache(String modelName, String modelType) {
        return HuggingFaceCacheResolver.resolve(modelName).isPresent();
    }

    /**
     * Gets the model size from the HuggingFace cache.
     */
    private long getModelSizeFromHuggingFaceCache(String modelName, String modelType) {
        return HuggingFaceCacheResolver.sizeOf(modelName);
    }

    /**
     * Verifies model integrity using size and optional hash checking.
     */
    private boolean verifyModelIntegrity(String modelKey, ModelMetadata metadata) throws Exception {
        Path modelDir = getModelCachePath(modelKey);

        if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) {
            LOGGER.debug("Model directory does not exist: {}", modelDir);
            return false;
        }

        if (metadata.expectedSize <= 0) {
            LOGGER.warn("No expected size metadata for model: {}", modelKey);
            return false;
        }

        long totalSize = Files.walk(modelDir)
            .filter(Files::isRegularFile)
            .mapToLong(path -> { try { return Files.size(path); } catch (IOException e) { return 0; } })
            .sum();

        double sizeTolerance = 0.01;
        long minSize = (long) (metadata.expectedSize * (1 - sizeTolerance));
        long maxSize = (long) (metadata.expectedSize * (1 + sizeTolerance));

        if (totalSize < minSize || totalSize > maxSize) {
            LOGGER.warn("Model size mismatch for {}: expected {}, got {} (tolerance: {}%)",
                       modelKey, metadata.expectedSize, totalSize, (int)(sizeTolerance * 100));
            return false;
        }

        if (metadata.expectedHash != null && !metadata.expectedHash.isEmpty()) {
            String actualHash = calculateModelHash(modelDir);
            if (!actualHash.equalsIgnoreCase(metadata.expectedHash)) {
                LOGGER.warn("Model hash mismatch for {}: expected {}, got {}",
                           modelKey, metadata.expectedHash, actualHash);
                return false;
            }
        }

        LOGGER.debug("Model {} integrity check passed", modelKey);
        return true;
    }

    /**
     * Calculates SHA-256 hash of a model directory.
     */
    private String calculateModelHash(Path modelDir) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files = new ArrayList<>();

        Files.walk(modelDir)
            .filter(Files::isRegularFile)
            .sorted()
            .forEach(files::add);

        for (Path file : files) {
            try (InputStream fis = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            digest.update(file.getFileName().toString().getBytes());
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    /**
     * Sets up environment variables for resumable downloads.
     *
     * @param modelName the model name
     * @param modelType the model type
     * @param progressCallback the progress callback
     * @return a map of environment variables
     */
    public Map<String, String> getResumableDownloadEnv(String modelName, String modelType, Consumer<Double> progressCallback) {
        Map<String, String> env = new HashMap<>();

        String key = createModelKey(modelName, modelType);

        PartialDownloadProgress partialProgress = getPartialDownloadProgress(modelName, modelType);
        long currentSize = getCurrentDownloadSize(modelName, modelType);

        if (partialProgress != null && !partialProgress.isComplete()) {
            LOGGER.info("Resuming partial download for {}: {}/{} bytes ({:.1f}%)",
                       key, partialProgress.getDownloadedBytes(),
                       partialProgress.getTotalBytes(),
                       partialProgress.getPercentage());

            if (progressCallback != null) {
                progressCallback.accept(partialProgress.getPercentage());
            }
        } else if (currentSize > 0) {
            LOGGER.info("Found existing download for {}: {} bytes", key, currentSize);
        }

        env.put("HF_RESUME_DOWNLOAD", "true");
        env.put("HF_HUB_RESUME_DOWNLOAD", "true");
        env.put("WHISPERX_RESUME_DOWNLOAD", "true");
        env.put("TRANSFORMERS_RESUME_DOWNLOAD", "true");

        env.put("HF_HOME", cacheDir.toString());
        env.put("TORCH_HOME", cacheDir.toString());
        env.put("PYANNOTE_CACHE", cacheDir.toString());
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "UTF-8");
        env.put("HF_HUB_DISABLE_TELEMETRY", "1");

        env.put("HF_HUB_DISABLE_PROGRESS_BARS", "false");
        env.put("HF_HUB_SHOW_PROGRESS_BARS", "true");

        return env;
    }

    /**
     * Saves model metadata to disk.
     */
    private void saveModelMetadata() {
        try {
            Map<String, Map<String, Object>> data = new HashMap<>();
            for (Map.Entry<String, ModelMetadata> entry : modelMetadata.entrySet()) {
                ModelMetadata metadata = entry.getValue();
                Map<String, Object> metaMap = new HashMap<>();
                metaMap.put("expectedHash", metadata.expectedHash);
                metaMap.put("expectedSize", metadata.expectedSize);
                metaMap.put("verified", metadata.verified);
                metaMap.put("lastVerified", metadata.lastVerified);
                metaMap.put("modelType", metadata.modelType);
                metaMap.put("modelName", metadata.modelName);
                data.put(entry.getKey(), metaMap);
            }

            String json = gson.toJson(data);
            Files.writeString(modelIntegrityFile, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        } catch (Exception e) {
            LOGGER.error("Failed to save model metadata", e);
        }
    }

    /**
     * Loads model metadata from disk.
     */
    private void loadModelMetadata() {
        if (!Files.exists(modelIntegrityFile)) {
            LOGGER.info("No existing model metadata found, starting fresh");
            return;
        }

        try {
            String json = Files.readString(modelIntegrityFile, StandardCharsets.UTF_8);
            Map<String, Map<String, Object>> data = gson.fromJson(
                json,
                new TypeToken<Map<String, Map<String, Object>>>(){}.getType()
            );

            if (data != null) {
                for (Map.Entry<String, Map<String, Object>> entry : data.entrySet()) {
                    Map<String, Object> metaMap = entry.getValue();
                    String modelKey = entry.getKey();
                    String modelName = (String) metaMap.getOrDefault("modelName", modelKey);
                    String hash = (String) metaMap.get("expectedHash");
                    long size = ((Number) metaMap.getOrDefault("expectedSize", 0L)).longValue();
                    boolean verified = (Boolean) metaMap.getOrDefault("verified", false);
                    long lastVerified = ((Number) metaMap.getOrDefault("lastVerified", 0L)).longValue();
                    String modelType = (String) metaMap.getOrDefault("modelType", "whisper");

                    ModelMetadata modelMetadataObj = new ModelMetadata(modelName, hash, size, modelType);
                    modelMetadataObj.verified = verified;
                    modelMetadataObj.lastVerified = lastVerified;

                    modelMetadata.put(modelKey, modelMetadataObj);
                    if (verified) {
                        verifiedModels.add(modelKey);
                    }
                }

                LOGGER.info("Loaded metadata for {} models", modelMetadata.size());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load model metadata, starting fresh", e);
        }
    }

    /**
     * Deletes a directory recursively.
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Clears traditional cache directories.
     */
    private void clearTraditionalCacheDirs() {
        try {
            Path[] cacheDirs = {
                Paths.get(System.getProperty("user.home"), ".cache", "whisperx"),
                Paths.get(System.getProperty("user.home"), ".cache", "torch"),
                Paths.get(System.getProperty("user.home"), ".cache", "huggingface"),
                Paths.get(System.getProperty("user.home"), ".cache", "pyannote"),
                Paths.get(System.getProperty("user.home"), ".cache", "transformers")
            };

            for (Path cacheDir : cacheDirs) {
                if (Files.exists(cacheDir)) {
                    deleteDirectory(cacheDir);
                    LOGGER.info("Cleared traditional cache: {}", cacheDir);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error clearing traditional cache: {}", e.getMessage());
        }
    }

    /**
     * Recursively checks if a directory contains any model files.
     */
    private boolean hasModelFilesRecursive(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".bin") || name.endsWith(".safetensors") || name.endsWith(".pt");
            });
        } catch (IOException e) {
            LOGGER.debug("Error walking {}: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * Formats bytes to a human-readable string.
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Gets the partial progress file path for a model.
     */
    private Path getPartialProgressFile(String modelName, String modelType) {
        String key = createModelKey(modelName, modelType);
        return cacheDir.resolve("partial_" + key + ".progress");
    }

    // ========================================================================
    //  Inner Class: PartialDownloadProgress
    // ========================================================================

    /**
     * Data class for partial download progress.
     */
    public static class PartialDownloadProgress {
        private final long downloadedBytes;
        private final long totalBytes;
        private final long lastUpdated;

        public PartialDownloadProgress(long downloadedBytes, long totalBytes, long lastUpdated) {
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.lastUpdated = lastUpdated;
        }

        public long getDownloadedBytes() { return downloadedBytes; }
        public long getTotalBytes() { return totalBytes; }
        public long getLastUpdated() { return lastUpdated; }

        public double getPercentage() {
            return totalBytes > 0 ? (downloadedBytes * 100.0 / totalBytes) : 0.0;
        }

        public boolean isComplete() {
            return totalBytes > 0 && downloadedBytes >= totalBytes;
        }

        @Override
        public String toString() {
            return String.format("%.1f%% (%s/%s)",
                    getPercentage(),
                    formatBytes(downloadedBytes),
                    formatBytes(totalBytes));
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}