/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Single source of truth for locating a faster-whisper model folder inside
 * HuggingFace's on-disk cache.
 *
 * <p>This class consolidates all HuggingFace cache lookup logic into one
 * place, replacing three separate hand-rolled implementations that were
 * scattered across {@code ModelManager}. Key features:
 * <ul>
 *   <li><b>Manifest-based resolution:</b> Queries {@code huggingface-cli scan-cache}
 *       for authoritative cache location</li>
 *   <li><b>Pattern-matching fallback:</b> Falls back to filesystem pattern
 *       matching when the CLI is not available</li>
 *   <li><b>Null-safe path resolution:</b> Handles {@code LOCALAPPDATA} being
 *       {@code null} on non-Windows platforms</li>
 *   <li><b>Model size calculation:</b> Computes total on-disk size of a model</li>
 *   <li><b>Sufficient file detection:</b> Checks for minimum file count to
 *       identify complete downloads</li>
 * </ul>
 *
 * <p><b>Resolution order:</b>
 * <ol>
 *   <li>Query {@code huggingface-cli scan-cache --format json} for the manifest</li>
 *   <li>If CLI is unavailable or fails, use filesystem pattern matching</li>
 * </ol>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see ModelManager
 */
public final class HuggingFaceCacheResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuggingFaceCacheResolver.class);

    /** Minimum files to consider a folder a "real", fully-downloaded model. */
    private static final int MIN_MODEL_FILE_COUNT = 4;

    private static final List<String> MODEL_FILE_EXTENSIONS =
            List.of(".bin", ".safetensors", ".json", ".txt", ".pt");

    private HuggingFaceCacheResolver() {}

    /**
     * Returns all plausible HuggingFace cache root directories on this machine.
     *
     * <p>This method skips any path that depends on an environment variable
     * that isn't set on this platform (fixes the {@code LOCALAPPDATA} NPE
     * on Linux/macOS).</p>
     *
     * @return a list of candidate cache root paths
     */
    public static List<Path> candidateRoots() {
        String userHome = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();

        if (userHome != null) {
            roots.add(Paths.get(userHome, ".cache", "huggingface", "hub"));
            roots.add(Paths.get(userHome, ".cache", "huggingface"));
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            roots.add(Paths.get(localAppData, "huggingface", "hub"));
        } else {
            LOGGER.debug("LOCALAPPDATA not set (expected on non-Windows) — skipping that cache root.");
        }

        return roots;
    }

    /**
     * Returns the standard HuggingFace folder name for a faster-whisper model.
     *
     * @param modelName the model name (e.g., "base")
     * @return the folder name (e.g., "models--Systran--faster-whisper-base")
     */
    public static String folderNameFor(String modelName) {
        return "models--Systran--faster-whisper-" + modelName.toLowerCase().replace("-", "--");
    }

    /**
     * Returns the repo_id as reported by {@code huggingface-cli scan-cache}.
     *
     * @param modelName the model name
     * @return the repo_id (e.g., "Systran/faster-whisper-base")
     */
    private static String repoIdFor(String modelName) {
        return "Systran/faster-whisper-" + modelName.toLowerCase();
    }

    /**
     * Locates the cache folder for a model, if it exists and contains a
     * complete download.
     *
     * <p>Tries the real HuggingFace cache manifest first; falls back to
     * filesystem pattern-matching only if the CLI is not available,
     * times out, or its output can't be parsed.</p>
     *
     * @param modelName the model name (e.g., "base", "small", "medium", "large")
     * @return the resolved path, or {@link Optional#empty()} if not found
     */
    public static Optional<Path> resolve(String modelName) {
        Optional<Path> viaCli = resolveViaCli(modelName);
        if (viaCli.isPresent()) {
            return viaCli;
        }

        String folderName = folderNameFor(modelName);

        for (Path root : candidateRoots()) {
            if (!Files.exists(root)) continue;

            Path candidate = root.resolve(folderName);
            if (hasSufficientModelFiles(candidate)) {
                LOGGER.debug("Resolved model '{}' to {}", modelName, candidate);
                return Optional.of(candidate);
            }

            // Also check the snapshots subdirectory some HF cache layouts use.
            Path snapshots = candidate.resolve("snapshots");
            if (hasSufficientModelFiles(snapshots)) {
                LOGGER.debug("Resolved model '{}' to snapshots dir {}", modelName, snapshots);
                return Optional.of(snapshots);
            }
        }
        return Optional.empty();
    }

    /**
     * Queries the real HuggingFace cache manifest via CLI.
     *
     * <p>Every failure mode here (CLI missing, non-zero exit, timeout,
     * unparseable JSON) is caught and logged at debug level, returning
     * empty so {@link #resolve} falls back to pattern-matching.</p>
     *
     * @param modelName the model name
     * @return the resolved path, or {@link Optional#empty()} if not found
     */
    private static Optional<Path> resolveViaCli(String modelName) {
        String targetRepoId = repoIdFor(modelName);
        try {
            Process process = new ProcessBuilder("huggingface-cli", "scan-cache", "--format", "json")
                    .redirectErrorStream(false)
                    .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                LOGGER.debug("huggingface-cli scan-cache timed out — falling back to pattern matching.");
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                LOGGER.debug("huggingface-cli scan-cache exited {} — falling back to pattern matching.",
                        process.exitValue());
                return Optional.empty();
            }
            if (output.isBlank()) {
                return Optional.empty();
            }

            JsonElement root = JsonParser.parseString(output);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("repos")) {
                LOGGER.debug("huggingface-cli scan-cache output missing expected \"repos\" array — falling back.");
                return Optional.empty();
            }

            JsonArray repos = root.getAsJsonObject().getAsJsonArray("repos");
            for (JsonElement repoElement : repos) {
                if (!repoElement.isJsonObject()) continue;
                JsonObject repo = repoElement.getAsJsonObject();
                if (!repo.has("repo_id")) continue;

                String repoId = repo.get("repo_id").getAsString();
                if (!targetRepoId.equalsIgnoreCase(repoId)) continue;

                if (repo.has("revisions") && repo.getAsJsonArray("revisions").size() > 0) {
                    JsonObject firstRevision = repo.getAsJsonArray("revisions").get(0).getAsJsonObject();
                    if (firstRevision.has("snapshot_path")) {
                        Path snapshotPath = Paths.get(firstRevision.get("snapshot_path").getAsString());
                        if (Files.isDirectory(snapshotPath)) {
                            LOGGER.debug("Resolved model '{}' via huggingface-cli manifest to {}",
                                    modelName, snapshotPath);
                            return Optional.of(snapshotPath);
                        }
                    }
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            LOGGER.debug("huggingface-cli not available ({}) — falling back to pattern matching.", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            // Covers JsonParseException and any JSON-shape surprises
            LOGGER.debug("Unexpected huggingface-cli scan-cache output shape ({}) — falling back to pattern matching.",
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns the total on-disk size (bytes) of a resolved model folder.
     *
     * @param modelName the model name
     * @return the total size in bytes, or {@code 0} if not found
     */
    public static long sizeOf(String modelName) {
        return resolve(modelName)
                .map(HuggingFaceCacheResolver::directorySize)
                .orElse(0L);
    }

    /**
     * Checks if a directory contains a sufficient number of model files.
     *
     * @param dir the directory to check
     * @return {@code true} if the directory contains at least
     *         {@value #MIN_MODEL_FILE_COUNT} model files
     */
    private static boolean hasSufficientModelFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            long count = stream
                    .filter(Files::isRegularFile)
                    .filter(HuggingFaceCacheResolver::hasModelExtension)
                    .count();
            return count >= MIN_MODEL_FILE_COUNT;
        } catch (IOException e) {
            LOGGER.debug("Error scanning candidate cache dir {}: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a file has a model file extension.
     *
     * @param p the file path
     * @return {@code true} if the file ends with {@code .bin}, {@code .safetensors},
     *         {@code .json}, {@code .txt}, or {@code .pt}
     */
    private static boolean hasModelExtension(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return MODEL_FILE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * Calculates the total size of a directory recursively.
     *
     * @param dir the directory
     * @return the total size in bytes
     */
    private static long directorySize(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            LOGGER.warn("Error calculating size for {}: {}", dir, e.getMessage());
            return 0L;
        }
    }
}