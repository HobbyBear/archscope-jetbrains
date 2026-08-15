package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AnalysisCache {
    private static final Logger LOG = Logger.getInstance(AnalysisCache.class);
    private static final String CACHE_SCHEMA = "archscope-analysis-cache/v5";

    private final Path directory;

    AnalysisCache() {
        this(resolveDirectory());
    }

    AnalysisCache(Path directory) {
        this.directory = directory;
    }

    JsonObject load(EvidencePack evidence, String analysisProfile) {
        Path path = cachePath(evidence.fingerprint(), analysisProfile);
        if (!Files.isRegularFile(path)) return null;
        try {
            JsonObject cached = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!CACHE_SCHEMA.equals(string(cached, "cache_schema"))
                    || !evidence.fingerprint().equals(string(cached, "fingerprint"))
                    || !evidence.targetCommit().equals(string(cached, "target_commit"))
                    || !analysisProfile.equals(string(cached, "analysis_profile"))
                    || !cached.has("report")
                    || !cached.get("report").isJsonObject()) {
                return null;
            }
            return cached.getAsJsonObject("report");
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Could not read cached architecture analysis " + path, exception);
            return null;
        }
    }

    void store(EvidencePack evidence, String analysisProfile, JsonObject report) {
        Path target = cachePath(evidence.fingerprint(), analysisProfile);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            JsonObject cached = new JsonObject();
            cached.addProperty("cache_schema", CACHE_SCHEMA);
            cached.addProperty("fingerprint", evidence.fingerprint());
            cached.addProperty("target_commit", evidence.targetCommit());
            cached.addProperty("analysis_profile", analysisProfile);
            cached.add("report", report.deepCopy());
            Files.writeString(temporary, cached.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOG.warn("Could not cache architecture analysis " + target, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private Path cachePath(String fingerprint, String analysisProfile) {
        String safe = (fingerprint + "-" + analysisProfile).replaceAll("[^a-zA-Z0-9._-]", "_");
        return directory.resolve(safe + ".json");
    }

    private static Path resolveDirectory() {
        String override = System.getProperty("archscope.cacheDir", "").strip();
        if (!override.isEmpty()) return Path.of(override).toAbsolutePath().normalize();
        return Path.of(PathManager.getSystemPath(), "ai-code-review-understanding", "analysis-cache");
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }
}
