package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.render.FunctionFlowRenderer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Stream;

public final class ReportArchive {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String SCHEMA = "archscope-report-archive/v3";
    private static final String PREVIOUS_SCHEMA = "archscope-report-archive/v2";
    private static final String LEGACY_SCHEMA = "archscope-report-archive/v1";
    private static final String RECEIPT_SCHEMA = "codebecause-report-delivery/v1";
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
        ReportIdentity identity = reportIdentity(result.reportJson());
        String id = createdAt.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path reportDirectory = repositoryDirectory.resolve(id);
        Path stagingDirectory = repositoryDirectory.resolve("." + id + ".staging-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(stagingDirectory);
        try {
            Files.writeString(stagingDirectory.resolve("report.json"), result.reportJson(), StandardCharsets.UTF_8);
            Files.writeString(stagingDirectory.resolve("report.html"), result.reportHtml(), StandardCharsets.UTF_8);
            String jsonHash = sha256(result.reportJson());
            String htmlHash = sha256(result.reportHtml());
            JsonObject receipt = new JsonObject();
            receipt.addProperty("schema", RECEIPT_SCHEMA);
            receipt.addProperty("report_json_sha256", jsonHash);
            receipt.addProperty("report_json_bytes", result.reportJson().getBytes(StandardCharsets.UTF_8).length);
            receipt.addProperty("report_html_sha256", htmlHash);
            receipt.addProperty("report_html_bytes", result.reportHtml().getBytes(StandardCharsets.UTF_8).length);
            receipt.addProperty("artifact_pairing", "verified");
            String diagramValidation = result.reportHtml().contains("data-diagram-checks=\"12/12\"")
                    ? "12/12" : result.reportHtml().contains("data-diagram-checks=\"9/9\"") ? "9/9" : "not_applicable";
            receipt.addProperty("diagram_validation", diagramValidation);
            Files.writeString(stagingDirectory.resolve("receipt.json"), GSON.toJson(receipt), StandardCharsets.UTF_8);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("schema", SCHEMA);
            metadata.addProperty("id", id);
            metadata.addProperty("repository_root", normalizedRoot.toString());
            metadata.addProperty("focus", request == null ? "" : request.focus());
            metadata.addProperty("title", identity.title());
            metadata.addProperty("function_symbol", identity.functionSymbol());
            metadata.addProperty("function_file", identity.functionFile());
            metadata.addProperty("mode", request == null ? "" : request.mode().name());
            metadata.addProperty("output_language", request == null ? "zh-CN" : request.outputLanguage().code());
            metadata.addProperty("target_commit", result.targetCommit());
            metadata.addProperty("fingerprint", result.fingerprint());
            metadata.addProperty("created_at", createdAt.toString());
            metadata.addProperty("elapsed_ms", elapsedMs);
            metadata.addProperty("report_json_sha256", jsonHash);
            metadata.addProperty("report_html_sha256", htmlHash);
            Files.writeString(stagingDirectory.resolve("metadata.json"), GSON.toJson(metadata), StandardCharsets.UTF_8);
            verifyArtifact(stagingDirectory);
            moveCommitted(stagingDirectory, reportDirectory);
        } catch (IOException exception) {
            deleteRecursively(stagingDirectory);
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
                identity.title(),
                identity.functionSymbol(),
                identity.functionFile(),
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
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .map(this::readEntry)
                    .filter(entry -> entry != null && normalizedRoot.equals(entry.repositoryRoot()))
                    .sorted(Comparator.comparing(Entry::createdAt).reversed())
                    .toList();
        }
    }

    public AnalysisResult load(Entry entry) throws IOException {
        verifyArtifact(entry.directory());
        String json = Files.readString(entry.directory().resolve("report.json"), StandardCharsets.UTF_8);
        String html = Files.readString(entry.directory().resolve("report.html"), StandardCharsets.UTF_8);
        if (entry.mode() == AnalysisRequest.Mode.FUNCTION_FLOW) {
            try {
                html = new FunctionFlowRenderer().render(
                        JsonParser.parseString(json).getAsJsonObject(), html.contains("data-theme=\"dark\""));
            } catch (RuntimeException exception) {
                throw new IOException("无法重新渲染函数流程历史报告", exception);
            }
        }
        return new AnalysisResult(json, html, entry.fingerprint(), entry.targetCommit());
    }

    public void delete(Entry entry) throws IOException {
        if (entry == null) return;
        deleteAll(List.of(entry));
    }

    public void deleteAll(Collection<Entry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) return;
        LinkedHashSet<Path> reportDirectories = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry != null) reportDirectories.add(validatedDirectory(entry));
        }
        for (Path reportDirectory : reportDirectories) deleteRecursively(reportDirectory);
    }

    private Path validatedDirectory(Entry entry) throws IOException {
        Path archiveRoot = directory.toAbsolutePath().normalize();
        Path expectedParent = archiveRoot.resolve(repositoryKey(entry.repositoryRoot().toAbsolutePath().normalize()));
        Path reportDirectory = entry.directory().toAbsolutePath().normalize();
        if (!expectedParent.equals(reportDirectory.getParent())
                || !entry.id().equals(reportDirectory.getFileName().toString())) {
            throw new IOException("拒绝删除归档目录之外的路径：" + reportDirectory);
        }
        return reportDirectory;
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
            String schema = string(metadata, "schema");
            if (!SCHEMA.equals(schema) && !PREVIOUS_SCHEMA.equals(schema) && !LEGACY_SCHEMA.equals(schema)) return null;
            String mode = string(metadata, "mode");
            String outputLanguage = string(metadata, "output_language");
            ReportIdentity identity = reportIdentity(reportDirectory, metadata);
            return new Entry(
                    string(metadata, "id"),
                    Path.of(string(metadata, "repository_root")).toAbsolutePath().normalize(),
                    string(metadata, "focus"),
                    identity.title(),
                    identity.functionSymbol(),
                    identity.functionFile(),
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

    private static void verifyArtifact(Path reportDirectory) throws IOException {
        Path receiptPath = reportDirectory.resolve("receipt.json");
        if (!Files.isRegularFile(receiptPath)) return;
        try {
            JsonObject receipt = JsonParser.parseString(Files.readString(receiptPath, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!RECEIPT_SCHEMA.equals(string(receipt, "schema"))) throw new IOException("报告交付收据格式无效");
            String json = Files.readString(reportDirectory.resolve("report.json"), StandardCharsets.UTF_8);
            String html = Files.readString(reportDirectory.resolve("report.html"), StandardCharsets.UTF_8);
            if (!sha256(json).equals(string(receipt, "report_json_sha256"))
                    || !sha256(html).equals(string(receipt, "report_html_sha256"))) {
                throw new IOException("报告 JSON、HTML 与交付收据不一致");
            }
        } catch (RuntimeException exception) {
            throw new IOException("无法解析报告交付收据", exception);
        }
    }

    private static void moveCommitted(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
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

    private static ReportIdentity reportIdentity(Path reportDirectory, JsonObject metadata) {
        ReportIdentity stored = new ReportIdentity(
                string(metadata, "title"),
                string(metadata, "function_symbol"),
                string(metadata, "function_file")
        );
        if (!stored.title().isBlank() && (!stored.functionSymbol().isBlank() || !stored.functionFile().isBlank())) {
            return stored;
        }
        try {
            ReportIdentity parsed = reportIdentity(Files.readString(
                    reportDirectory.resolve("report.json"), StandardCharsets.UTF_8));
            return new ReportIdentity(
                    stored.title().isBlank() ? parsed.title() : stored.title(),
                    stored.functionSymbol().isBlank() ? parsed.functionSymbol() : stored.functionSymbol(),
                    stored.functionFile().isBlank() ? parsed.functionFile() : stored.functionFile()
            );
        } catch (IOException ignored) {
            return stored;
        }
    }

    private static ReportIdentity reportIdentity(String reportJson) {
        try {
            JsonObject report = JsonParser.parseString(reportJson).getAsJsonObject();
            JsonObject target = report.has("function_target") && report.get("function_target").isJsonObject()
                    ? report.getAsJsonObject("function_target") : new JsonObject();
            return new ReportIdentity(string(report, "title"), string(target, "symbol"), string(target, "file"));
        } catch (RuntimeException ignored) {
            return new ReportIdentity("", "", "");
        }
    }

    private record ReportIdentity(String title, String functionSymbol, String functionFile) {
    }

    public record Entry(
            String id,
            Path repositoryRoot,
            String focus,
            String title,
            String functionSymbol,
            String functionFile,
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
