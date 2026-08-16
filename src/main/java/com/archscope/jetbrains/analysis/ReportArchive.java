package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class ReportArchive {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String SCHEMA = "archscope-report-archive/v1";
    private static final int MAX_REPORTS_PER_REPOSITORY = 100;
    private final Path directory;

    public ReportArchive() {
        this(resolveDirectory());
    }

    ReportArchive(Path directory) {
        this.directory = directory;
    }

    public Entry save(Path repositoryRoot, AnalysisRequest request, AnalysisResult result) throws IOException {
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        Path repositoryDirectory = directory.resolve(repositoryKey(normalizedRoot));
        Files.createDirectories(repositoryDirectory);
        Instant createdAt = Instant.now();
        long elapsedMs = elapsedMs(result.reportJson());
        String id = createdAt.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path reportDirectory = repositoryDirectory.resolve(id);
        Files.createDirectories(reportDirectory);
        try {
            Files.writeString(reportDirectory.resolve("report.json"), result.reportJson(), StandardCharsets.UTF_8);
            Files.writeString(reportDirectory.resolve("report.html"), result.reportHtml(), StandardCharsets.UTF_8);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("schema", SCHEMA);
            metadata.addProperty("id", id);
            metadata.addProperty("repository_root", normalizedRoot.toString());
            metadata.addProperty("focus", request == null ? "" : request.focus());
            metadata.addProperty("mode", request == null ? "" : request.mode().name());
            metadata.addProperty("output_language", request == null ? "zh-CN" : request.outputLanguage().code());
            metadata.addProperty("target_commit", result.targetCommit());
            metadata.addProperty("fingerprint", result.fingerprint());
            metadata.addProperty("created_at", createdAt.toString());
            metadata.addProperty("elapsed_ms", elapsedMs);
            Files.writeString(reportDirectory.resolve("metadata.json"), GSON.toJson(metadata), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            deleteRecursively(reportDirectory);
            throw exception;
        }
        try {
            prune(repositoryDirectory);
        } catch (IOException ignored) {
            // The newly saved report is complete; stale-report cleanup is best effort.
        }
        return new Entry(
                id,
                normalizedRoot,
                request == null ? "" : request.focus(),
                request == null ? null : request.mode(),
                request == null ? AnalysisRequest.OutputLanguage.CHINESE : request.outputLanguage(),
                result.targetCommit(),
                result.fingerprint(),
                createdAt,
                elapsedMs,
                reportDirectory
        );
    }

    public List<Entry> list(Path repositoryRoot) throws IOException {
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        Path repositoryDirectory = directory.resolve(repositoryKey(normalizedRoot));
        if (!Files.isDirectory(repositoryDirectory)) return List.of();
        try (Stream<Path> paths = Files.list(repositoryDirectory)) {
            return paths.filter(Files::isDirectory)
                    .map(this::readEntry)
                    .filter(entry -> entry != null && normalizedRoot.equals(entry.repositoryRoot()))
                    .sorted(Comparator.comparing(Entry::createdAt).reversed())
                    .toList();
        }
    }

    public AnalysisResult load(Entry entry) throws IOException {
        String json = Files.readString(entry.directory().resolve("report.json"), StandardCharsets.UTF_8);
        String html = Files.readString(entry.directory().resolve("report.html"), StandardCharsets.UTF_8);
        return new AnalysisResult(json, html, entry.fingerprint(), entry.targetCommit());
    }

    public void delete(Entry entry) throws IOException {
        if (entry == null) return;
        Path archiveRoot = directory.toAbsolutePath().normalize();
        Path expectedParent = archiveRoot.resolve(repositoryKey(entry.repositoryRoot().toAbsolutePath().normalize()));
        Path reportDirectory = entry.directory().toAbsolutePath().normalize();
        if (!expectedParent.equals(reportDirectory.getParent())
                || !entry.id().equals(reportDirectory.getFileName().toString())) {
            throw new IOException("拒绝删除归档目录之外的路径：" + reportDirectory);
        }
        deleteRecursively(reportDirectory);
    }

    private Entry readEntry(Path reportDirectory) {
        Path metadataPath = reportDirectory.resolve("metadata.json");
        if (!Files.isRegularFile(metadataPath)
                || !Files.isRegularFile(reportDirectory.resolve("report.json"))
                || !Files.isRegularFile(reportDirectory.resolve("report.html"))) return null;
        try {
            JsonObject metadata = JsonParser.parseString(
                    Files.readString(metadataPath, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            if (!SCHEMA.equals(string(metadata, "schema"))) return null;
            String mode = string(metadata, "mode");
            String outputLanguage = string(metadata, "output_language");
            return new Entry(
                    string(metadata, "id"),
                    Path.of(string(metadata, "repository_root")).toAbsolutePath().normalize(),
                    string(metadata, "focus"),
                    mode.isBlank() ? null : AnalysisRequest.Mode.valueOf(mode),
                    "en".equals(outputLanguage)
                            ? AnalysisRequest.OutputLanguage.ENGLISH
                            : AnalysisRequest.OutputLanguage.CHINESE,
                    string(metadata, "target_commit"),
                    string(metadata, "fingerprint"),
                    Instant.parse(string(metadata, "created_at")),
                    nonNegativeLong(metadata, "elapsed_ms"),
                    reportDirectory
            );
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void prune(Path repositoryDirectory) throws IOException {
        try (Stream<Path> paths = Files.list(repositoryDirectory)) {
            List<Path> reports = paths.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            for (Path stale : reports.stream().skip(MAX_REPORTS_PER_REPOSITORY).toList()) {
                deleteRecursively(stale);
            }
        }
    }

    private static String repositoryKey(Path repositoryRoot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(repositoryRoot.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private static Path resolveDirectory() {
        String override = System.getProperty("archscope.reportArchiveDir", "").strip();
        if (!override.isEmpty()) return Path.of(override).toAbsolutePath().normalize();
        return Path.of(PathManager.getSystemPath(), "ai-code-review-understanding", "report-archive");
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private static long nonNegativeLong(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) return 0L;
        try {
            return Math.max(0L, object.get(name).getAsLong());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static long elapsedMs(String reportJson) {
        try {
            JsonObject report = JsonParser.parseString(reportJson).getAsJsonObject();
            if (!report.has("analysis_diagnostics") || !report.get("analysis_diagnostics").isJsonObject()) return 0L;
            return nonNegativeLong(report.getAsJsonObject("analysis_diagnostics"), "elapsed_ms");
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    public record Entry(
            String id,
            Path repositoryRoot,
            String focus,
            AnalysisRequest.Mode mode,
            AnalysisRequest.OutputLanguage outputLanguage,
            String targetCommit,
            String fingerprint,
            Instant createdAt,
            long elapsedMs,
            Path directory
    ) {
    }
}
