package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.SensitiveTextSanitizer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ModelRunAuditLog {
    private static final Logger LOG = Logger.getInstance(ModelRunAuditLog.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String REQUEST_SCHEMA = "codebecause-model-audit-request/v1";
    private static final String EVENT_SCHEMA = "codebecause-model-audit-event/v1";
    private static final String SUMMARY_SCHEMA = "codebecause-model-audit-summary/v1";
    private static final int MAX_RUNS = 100;
    private static final Pattern REPOMIND_FILE = Pattern.compile(
            "(?:^|[\\s'\"=])([^\\s'\";,]*\\.repomind/[^\\s'\";,]+)", Pattern.CASE_INSENSITIVE);

    private final Path root;

    ModelRunAuditLog() {
        this(resolveDirectory());
    }

    ModelRunAuditLog(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Run start(
            String provider,
            String stage,
            Path workingDirectory,
            ModelClient.WorkspaceAccess workspaceAccess,
            String systemPrompt,
            String userPrompt
    ) {
        Instant startedAt = Instant.now();
        String id = startedAt.toEpochMilli() + "-" + safeName(provider) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path directory = root.resolve(id);
        try {
            Files.createDirectories(directory);
            JsonObject request = new JsonObject();
            request.addProperty("schema", REQUEST_SCHEMA);
            request.addProperty("id", id);
            request.addProperty("provider", provider);
            request.addProperty("stage", stage);
            request.addProperty("started_at", startedAt.toString());
            request.addProperty("working_directory", workingDirectory.toAbsolutePath().normalize().toString());
            request.addProperty("workspace_access", workspaceAccess.name());
            request.addProperty("system_prompt", redact(systemPrompt));
            request.addProperty("user_prompt", redact(userPrompt));
            Files.writeString(directory.resolve("request.json"), GSON.toJson(request), StandardCharsets.UTF_8);
            prune();
            LOG.info("Model audit started: provider=" + provider + ", stage=" + stage + ", directory=" + directory);
            return new Run(root, directory, id, provider, stage, startedAt, systemPrompt, userPrompt);
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Could not start model audit log in " + directory, exception);
            return Run.disabled(provider, stage, startedAt, systemPrompt, userPrompt);
        }
    }

    private void prune() {
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> runs = paths.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            for (Path stale : runs.stream().skip(MAX_RUNS).toList()) deleteRecursively(stale);
        } catch (IOException exception) {
            LOG.warn("Could not prune model audit logs in " + root, exception);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private static Path resolveDirectory() {
        String override = System.getProperty("archscope.modelAuditDir", "").strip();
        if (!override.isEmpty()) return Path.of(override);
        return Path.of(PathManager.getSystemPath(), "ai-code-review-understanding", "model-audit");
    }

    private static String safeName(String value) {
        String safe = value == null ? "model" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "model" : safe;
    }

    private static String redact(String value) {
        return SensitiveTextSanitizer.redact(value == null ? "" : value);
    }

    private static JsonElement redact(JsonElement value) {
        if (value == null || value.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isString()) return new com.google.gson.JsonPrimitive(redact(value.getAsString()));
            return value.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) result.add(redact(item));
            return result;
        }
        JsonObject result = new JsonObject();
        value.getAsJsonObject().entrySet().forEach(entry -> result.add(entry.getKey(),
                sensitiveKey(entry.getKey())
                        ? new com.google.gson.JsonPrimitive("[REDACTED_SECRET]")
                        : redact(entry.getValue())));
        return result;
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT)
                .replace("-", "").replace("_", "");
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("apikey")
                || normalized.contains("accesstoken")
                || normalized.contains("authorization")
                || normalized.contains("clientsecret")
                || normalized.contains("credential");
    }

    static final class Run implements AutoCloseable {
        private final Path root;
        private final Path directory;
        private final String id;
        private final String provider;
        private final String stage;
        private final Instant startedAt;
        private final boolean enabled;
        private final boolean repomindMentionedInInitialInput;
        private final Set<String> fingerprints = new LinkedHashSet<>();
        private final Set<String> repomindEvidence = new LinkedHashSet<>();
        private final Set<String> knowledgeFiles = new LinkedHashSet<>();
        private int eventCount;
        private int toolCallCount;
        private boolean repomindSkillLoaded;
        private boolean repomindMetadataExecuted;
        private boolean repomindMigrateExecuted;
        private boolean repomindSummaryUsed;
        private boolean finished;

        private Run(
                Path root,
                Path directory,
                String id,
                String provider,
                String stage,
                Instant startedAt,
                String systemPrompt,
                String userPrompt
        ) {
            this.root = root;
            this.directory = directory;
            this.id = id;
            this.provider = provider;
            this.stage = stage;
            this.startedAt = startedAt;
            this.enabled = true;
            this.repomindMentionedInInitialInput = containsRepoMind(systemPrompt + "\n" + userPrompt);
        }

        private Run(String provider, String stage, Instant startedAt, String systemPrompt, String userPrompt) {
            this.root = null;
            this.directory = null;
            this.id = "";
            this.provider = provider;
            this.stage = stage;
            this.startedAt = startedAt;
            this.enabled = false;
            this.repomindMentionedInInitialInput = containsRepoMind(systemPrompt + "\n" + userPrompt);
        }

        static Run disabled(String provider, String stage, Instant startedAt, String systemPrompt, String userPrompt) {
            return new Run(provider, stage, startedAt, systemPrompt, userPrompt);
        }

        synchronized void recordCodexEvent(JsonObject event) {
            eventCount++;
            JsonObject item = object(event, "item");
            if (item == null) return;
            String itemType = string(item, "type");
            if ("command_execution".equals(itemType)) {
                JsonObject input = selected(item, "command", "cwd");
                recordTool("codex", string(event, "type"), "command_execution", string(item, "id"), input);
            } else if ("mcp_tool_call".equals(itemType)) {
                JsonObject input = selected(item, "server", "tool", "arguments");
                recordTool("codex", string(event, "type"), fallback(string(item, "tool"), "mcp_tool_call"),
                        string(item, "id"), input);
            }
        }

        synchronized void recordClaudeEvent(JsonObject event) {
            eventCount++;
            if (!"assistant".equals(string(event, "type"))) return;
            JsonObject message = object(event, "message");
            JsonArray content = message == null ? null : array(message, "content");
            if (content == null) return;
            for (JsonElement element : content) {
                if (!element.isJsonObject()) continue;
                JsonObject block = element.getAsJsonObject();
                String type = string(block, "type");
                if (!"tool_use".equals(type) && !"server_tool_use".equals(type)) continue;
                JsonElement input = block.has("input") ? block.get("input") : new JsonObject();
                recordTool("claude", type, fallback(string(block, "name"), type), string(block, "id"), input);
            }
        }

        synchronized void recordResult(String rawResult) {
            if (!enabled) return;
            JsonObject result = new JsonObject();
            result.addProperty("schema", "codebecause-model-audit-result/v1");
            try {
                result.add("response", redact(com.google.gson.JsonParser.parseString(rawResult)));
            } catch (RuntimeException ignored) {
                result.addProperty("response", redact(rawResult));
            }
            try {
                Files.writeString(directory.resolve("result.json"), GSON.toJson(result), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                LOG.warn("Could not record model result in " + directory, exception);
            }
        }

        synchronized void finish(String status, long elapsedMs, int resultChars) {
            if (finished) return;
            finished = true;
            if (!enabled) return;
            JsonObject summary = new JsonObject();
            summary.addProperty("schema", SUMMARY_SCHEMA);
            summary.addProperty("id", id);
            summary.addProperty("provider", provider);
            summary.addProperty("stage", stage);
            summary.addProperty("status", status);
            summary.addProperty("started_at", startedAt.toString());
            summary.addProperty("finished_at", Instant.now().toString());
            summary.addProperty("elapsed_ms", Math.max(0L, elapsedMs));
            summary.addProperty("result_chars", Math.max(0, resultChars));
            summary.addProperty("event_count", eventCount);
            summary.addProperty("tool_call_count", toolCallCount);
            summary.addProperty("repomind_mentioned_in_initial_input", repomindMentionedInInitialInput);
            summary.addProperty("repomind_runtime_evidence", !repomindEvidence.isEmpty());
            summary.addProperty("repomind_skill_loaded", repomindSkillLoaded);
            summary.addProperty("repomind_kb_metadata_executed", repomindMetadataExecuted);
            summary.addProperty("repomind_kb_migrate_executed", repomindMigrateExecuted);
            summary.addProperty("repomind_summary_used", repomindSummaryUsed);
            summary.add("repomind_evidence", strings(repomindEvidence));
            summary.add("knowledge_files_read", strings(knowledgeFiles));
            try {
                Files.writeString(directory.resolve("summary.json"), GSON.toJson(summary), StandardCharsets.UTF_8);
                JsonObject latest = summary.deepCopy();
                latest.addProperty("run_directory", directory.toString());
                Files.writeString(root.resolve("latest-" + safeName(provider) + ".json"),
                        GSON.toJson(latest), StandardCharsets.UTF_8);
                Files.writeString(root.resolve("latest.json"), GSON.toJson(latest), StandardCharsets.UTF_8);
                LOG.info("Model audit completed: provider=" + provider + ", stage=" + stage
                        + ", repomindRuntimeEvidence=" + !repomindEvidence.isEmpty()
                        + ", directory=" + directory);
            } catch (IOException exception) {
                LOG.warn("Could not finish model audit log in " + directory, exception);
            }
        }

        Path directory() {
            return directory;
        }

        @Override
        public synchronized void close() {
            if (!finished) finish("incomplete", java.time.Duration.between(startedAt, Instant.now()).toMillis(), 0);
        }

        private void recordTool(
                String source,
                String sourceEvent,
                String toolName,
                String callId,
                JsonElement input
        ) {
            String inputText = input == null ? "" : input.toString();
            String fingerprint = callId == null || callId.isBlank()
                    ? source + "\n" + toolName + "\n" + inputText
                    : source + "\n" + callId;
            if (!fingerprints.add(fingerprint)) return;
            toolCallCount++;
            inspectRepoMind(toolName + "\n" + inputText);
            if (!enabled) return;
            JsonObject auditEvent = new JsonObject();
            auditEvent.addProperty("schema", EVENT_SCHEMA);
            auditEvent.addProperty("timestamp", Instant.now().toString());
            auditEvent.addProperty("source", source);
            auditEvent.addProperty("source_event", sourceEvent);
            auditEvent.addProperty("tool_name", toolName);
            if (callId != null && !callId.isBlank()) auditEvent.addProperty("call_id", callId);
            auditEvent.add("input", redact(input));
            try {
                Files.writeString(directory.resolve("events.jsonl"), auditEvent + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                LOG.warn("Could not append model audit event in " + directory, exception);
            }
        }

        private void inspectRepoMind(String raw) {
            String normalized = raw == null ? "" : raw.replace('\\', '/');
            String lower = normalized.toLowerCase(java.util.Locale.ROOT);
            if (!containsRepoMind(lower)) return;
            repomindEvidence.add(redact(normalized).strip());
            if (lower.contains("repomind-query")
                    && (lower.contains("skill.md") || lower.startsWith("skill\n"))) {
                repomindSkillLoaded = true;
            }
            if (lower.contains("kb-metadata")) repomindMetadataExecuted = true;
            if (lower.contains("kb-migrate")) repomindMigrateExecuted = true;
            if (lower.contains("repomind-summary")) repomindSummaryUsed = true;
            Matcher matcher = REPOMIND_FILE.matcher(normalized);
            while (matcher.find()) knowledgeFiles.add(matcher.group(1));
        }

        private static JsonObject selected(JsonObject source, String... names) {
            JsonObject selected = new JsonObject();
            for (String name : names) if (source.has(name)) selected.add(name, source.get(name).deepCopy());
            return selected;
        }

        private static JsonObject object(JsonObject owner, String name) {
            return owner != null && owner.has(name) && owner.get(name).isJsonObject()
                    ? owner.getAsJsonObject(name) : null;
        }

        private static JsonArray array(JsonObject owner, String name) {
            return owner != null && owner.has(name) && owner.get(name).isJsonArray()
                    ? owner.getAsJsonArray(name) : null;
        }

        private static String string(JsonObject owner, String name) {
            return owner != null && owner.has(name) && owner.get(name).isJsonPrimitive()
                    ? owner.get(name).getAsString() : "";
        }

        private static String fallback(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        private static boolean containsRepoMind(String value) {
            return value != null && value.toLowerCase(java.util.Locale.ROOT).contains("repomind");
        }

        private static JsonArray strings(Set<String> values) {
            JsonArray result = new JsonArray();
            values.forEach(result::add);
            return result;
        }
    }
}
