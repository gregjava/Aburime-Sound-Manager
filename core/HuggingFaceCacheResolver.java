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
 * <h2>Manifest-based resolution (this class's main lookup path)</h2>
 * {@link #resolve(String)} now queries HuggingFace's own cache index via
 * {@code huggingface-cli scan-cache --format json} first — the real
 * manifest, not a guess at folder-naming conventions — parsed the same way
 * {@code WhisperXTranscriptionService} already parses subprocess JSON
 * output (see {@code isWhisperXInstalled} there for the established
 * ProcessBuilder + redirected-stream + timeout pattern this follows).
 * {@link #candidateRoots()}-based pattern-matching is kept as the fallback
 * for when that command isn't on PATH (huggingface_hub not installed, or
 * installed without the CLI extras) or its output can't be parsed — so a
 * user without huggingface_hub's CLI still gets a working, if less
 * authoritative, lookup rather than a hard failure.
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

    /** repo_id as huggingface-cli's scan-cache reports it for a faster-whisper model. */
    private static String repoIdFor(String modelName) {
        return "Systran/faster-whisper-" + modelName.toLowerCase();
    }

    /**
     * Locate the cache folder for {@code modelName}, if it exists and
     * contains what looks like a real (non-partial) download.
     *
     * <p>Tries the real HuggingFace cache manifest first ({@code
     * huggingface-cli scan-cache}); falls back to filesystem pattern-
     * matching only if that command isn't available, times out, or its
     * output can't be parsed as expected — see {@link #resolveViaCli}.</p>
     *
     * @return the resolved path, or empty if not found in any known root
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
     * Query the real HuggingFace cache manifest via {@code huggingface-cli
     * scan-cache --format json} rather than guessing folder-naming
     * conventions. Every failure mode here (CLI missing, non-zero exit,
     * timeout, unparseable/unexpected JSON shape) is caught and logged at
     * debug level, returning empty so {@link #resolve} falls back to
     * pattern-matching — this must never throw out to a caller expecting a
     * simple "found or not" answer.
     *
     * <p>Expected shape (huggingface_hub's documented scan-cache JSON
     * output): a top-level object with a {@code "repos"} array; each repo
     * has {@code "repo_id"} (e.g. {@code "Systran/faster-whisper-base"}),
     * {@code "size_on_disk"} (bytes), and a {@code "revisions"} array whose
     * entries have a {@code "snapshot_path"}.</p>
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
            // Repo simply isn't in the cache — a legitimate "not found",
            // not a parse/tooling failure, so no fallback needed for this
            // specific case; resolve() will still try pattern-matching
            // harmlessly on top, which will also correctly find nothing.
            return Optional.empty();
        } catch (IOException e) {
            LOGGER.debug("huggingface-cli not available ({}) — falling back to pattern matching.", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            // Covers JsonParseException and any JSON-shape surprises
            // (missing keys, unexpected types) — a huggingface_hub version
            // bump changing its JSON schema must degrade to the fallback,
            // not crash model resolution app-wide.
            LOGGER.debug("Unexpected huggingface-cli scan-cache output shape ({}) — falling back to pattern matching.",
                    e.getMessage());
            return Optional.empty();
        }
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