/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for locating a faster-whisper model folder inside
 * HuggingFace's on-disk cache.
 *
 * <h2>What this replaces</h2>
 * {@code ModelManager} previously had <b>three separate, independently
 * hand-rolled</b> implementations of "guess where HuggingFace might have put
 * this model" — {@code isModelInHuggingFaceCache}, {@code
 * getModelSizeFromHuggingFaceCache}, and {@code isModelCachedAnywhere} each
 * built their own candidate-path list, and each did it slightly differently
 * (different file-extension filters, different minimum-file-count
 * thresholds, different subsets of candidate roots). That triplication is
 * exactly the kind of maintenance trap flagged in review: a future change to
 * HuggingFace's cache layout would need to be applied in three places, and
 * it would be easy to fix one and miss the others.
 *
 * <p>It also had a live bug: two of the three built
 * {@code Paths.get(System.getenv("LOCALAPPDATA"), ...)} unconditionally.
 * {@code LOCALAPPDATA} is {@code null} on Linux/macOS, and
 * {@code Paths.get(null, ...)} throws {@code NullPointerException}
 * immediately — not caught anywhere in those two call paths — so any model
 * lookup that reached {@code isModelInHuggingFaceCache} or {@code
 * isModelCachedAnywhere} on a non-Windows machine would crash rather than
 * simply report "not found". (The third implementation, {@code
 * findModelPath}, happened to null-guard it correctly — which is exactly the
 * kind of inconsistency you get from copy-pasted logic.)
 *
 * <h2>What this is not</h2>
 * This is still filesystem-pattern matching, not a query against
 * HuggingFace's own manifest/index — a genuinely robust fix would shell out
 * to {@code huggingface-cli scan-cache --format json} (or use the
 * `huggingface_hub` Python API, since this app already depends on Python for
 * WhisperX) and parse the real cache index instead of guessing folder name
 * patterns. That's flagged as a follow-up in the class-level TODO below
 * rather than done here, since it changes the runtime dependency surface
 * (requires huggingface_hub's CLI to be present and is a bigger behavioral
 * change) and deserves its own review rather than being bundled into a
 * refactor-and-bugfix pass.
 *
 * <h3>TODO (follow-up, not done here)</h3>
 * Replace {@link #candidateRoots()} pattern-matching with a call to
 * {@code huggingface-cli scan-cache --format json}, parsed the same way
 * {@code WhisperXTranscriptionService} already parses subprocess JSON
 * output, falling back to this pattern-matching implementation only if that
 * command is unavailable.
 */
public final class HuggingFaceCacheResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuggingFaceCacheResolver.class);

    /** Minimum files to consider a folder a "real", fully-downloaded model rather than a stub/partial download. */
    private static final int MIN_MODEL_FILE_COUNT = 4;

    private static final List<String> MODEL_FILE_EXTENSIONS =
            List.of(".bin", ".safetensors", ".json", ".txt", ".pt");

    private HuggingFaceCacheResolver() {}

    /**
     * All plausible HuggingFace cache root directories on this machine,
     * skipping any that depend on an environment variable that isn't set on
     * this platform (fixes the {@code LOCALAPPDATA} NPE described above).
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

    /** Standard HuggingFace folder-naming pattern for a faster-whisper model. */
    public static String folderNameFor(String modelName) {
        return "models--Systran--faster-whisper-" + modelName.toLowerCase().replace("-", "--");
    }

    /**
     * Locate the cache folder for {@code modelName}, if it exists and
     * contains what looks like a real (non-partial) download.
     *
     * @return the resolved path, or empty if not found in any known root
     */
    public static Optional<Path> resolve(String modelName) {
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

    /** Total on-disk size (bytes) of a resolved model folder, or 0 if not found. */
    public static long sizeOf(String modelName) {
        return resolve(modelName)
                .map(HuggingFaceCacheResolver::directorySize)
                .orElse(0L);
    }

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

    private static boolean hasModelExtension(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return MODEL_FILE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

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
