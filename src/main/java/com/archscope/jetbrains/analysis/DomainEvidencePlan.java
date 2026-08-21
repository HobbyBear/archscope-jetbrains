package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record DomainEvidencePlan(
        String json,
        List<String> candidatePaths,
        List<Query> queries,
        EditIntent editIntent
) {
    private static final String SCHEMA = "business-domain-evidence-plan/v1";
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]{4,}\\b");

    public DomainEvidencePlan(String json, List<String> candidatePaths, List<Query> queries) {
        this(json, candidatePaths, queries, EditIntent.initial());
    }

    public static DomainEvidencePlan parse(String raw, EvidencePack evidence) throws ModelClientException {
        JsonObject root;
        try {
            root = ModelJsonParser.parseObject(raw);
        } catch (RuntimeException exception) {
            root = parseTextSlots(raw);
        }
        boolean recognizedPlan = SCHEMA.equals(string(root, "schema"));
        JsonObject recovered = recoverLoosePlan(raw, evidence);
        root.addProperty("schema", SCHEMA);
        if (!recognizedPlan) {
            mergeRecoveredArray(root, recovered, "candidate_paths");
            mergeRecoveredArray(root, recovered, "queries");
            mergeRecoveredArray(root, recovered, "likely_domains");
        }
        if (!root.has("refinement_intent") || !root.get("refinement_intent").isJsonObject()) {
            root.add("refinement_intent", recovered.getAsJsonObject("refinement_intent"));
        }
        Set<String> manifest = Set.copyOf(evidence.targetManifest());
        List<String> paths = strings(array(root, "candidate_paths")).stream()
                .map(path -> resolveManifestPath(path, manifest))
                .filter(path -> !path.isBlank())
                .filter(DomainEvidencePlan::isAnalyzablePath)
                .distinct()
                .limit(8)
                .toList();
        List<Query> queries = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray queryItems = array(root, "queries");
        if (queryItems != null) {
            for (JsonElement item : queryItems) {
                if (!item.isJsonObject()) continue;
                JsonObject query = item.getAsJsonObject();
                String literal = string(query, "literal").strip();
                if (literal.length() < 3 || literal.length() > 100 || literal.contains("\n")
                        || isOverbroad(literal) || !seen.add(literal)) continue;
                queries.add(new Query(literal, string(query, "role"), string(query, "reason")));
                if (queries.size() == 8) break;
            }
        }
        EditIntent editIntent = EditIntent.parse(root);
        if (paths.isEmpty() && editIntent.evidenceRequired()) {
            paths = evidence.targetManifest().stream()
                    .filter(DomainEvidencePlan::isAnalyzablePath)
                    .limit(6)
                    .toList();
        }
        if (paths.isEmpty() && editIntent.evidenceRequired()) {
            throw new ModelClientException("当前仓库清单中没有可用于业务分析的源码文件");
        }

        JsonArray normalizedPaths = new JsonArray();
        paths.forEach(normalizedPaths::add);
        root.add("candidate_paths", normalizedPaths);
        JsonArray normalizedQueries = new JsonArray();
        for (Query query : queries) {
            JsonObject item = new JsonObject();
            item.addProperty("literal", query.literal());
            item.addProperty("role", query.role());
            item.addProperty("reason", query.reason());
            normalizedQueries.add(item);
        }
        root.add("queries", normalizedQueries);
        return new DomainEvidencePlan(root.toString(), paths, List.copyOf(queries), editIntent);
    }

    private static JsonObject parseTextSlots(String raw) {
        JsonObject root = new JsonObject();
        JsonObject intent = new JsonObject();
        JsonArray operations = new JsonArray();
        JsonArray domainIds = new JsonArray();
        JsonArray flowIds = new JsonArray();
        JsonArray stepIds = new JsonArray();
        JsonArray topics = new JsonArray();
        JsonArray paths = new JsonArray();
        JsonArray queries = new JsonArray();
        JsonArray domains = new JsonArray();
        for (String line : (raw == null ? "" : raw).lines().toList()) {
            String[] fields = textSlotFields(line);
            if (fields.length == 0) continue;
            String key = normalizeSlotKey(fields[0]);
            String value = fields.length > 1 ? fields[1].strip() : "";
            switch (key) {
                case "SCHEMA" -> root.addProperty("schema", value);
                case "TOPIC" -> root.addProperty("topic", value);
                case "OPERATIONS" -> addCsv(operations, value);
                case "TARGET_DOMAIN_IDS" -> addCsv(domainIds, value);
                case "TARGET_FLOW_IDS" -> addCsv(flowIds, value);
                case "TARGET_STEP_IDS" -> addCsv(stepIds, value);
                case "REQUESTED_TOPICS" -> addCsv(topics, value);
                case "EVIDENCE_REQUIRED" -> intent.addProperty("evidence_required", Boolean.parseBoolean(value));
                case "CANDIDATE_PATH" -> { if (!value.isBlank()) paths.add(value); }
                case "QUERY" -> {
                    if (fields.length < 3 || value.isBlank()) break;
                    JsonObject query = new JsonObject();
                    query.addProperty("literal", value);
                    query.addProperty("role", fields[2].strip());
                    query.addProperty("reason", fields.length > 3 ? fields[3].strip() : "");
                    queries.add(query);
                }
                case "LIKELY_DOMAIN" -> {
                    if (fields.length < 3 || value.isBlank()) break;
                    JsonObject domain = new JsonObject();
                    domain.addProperty("id", value);
                    domain.addProperty("name", fields[2].strip());
                    domain.addProperty("purpose", fields.length > 3 ? fields[3].strip() : "");
                    domains.add(domain);
                }
                default -> { }
            }
        }
        if (operations.isEmpty()) operations.add("initial");
        if (!intent.has("evidence_required")) intent.addProperty("evidence_required", true);
        intent.add("operations", operations);
        intent.add("target_domain_ids", domainIds);
        intent.add("target_flow_ids", flowIds);
        intent.add("target_step_ids", stepIds);
        intent.add("requested_topics", topics);
        root.add("refinement_intent", intent);
        root.add("likely_domains", domains);
        root.add("candidate_paths", paths);
        root.add("queries", queries);
        return root;
    }

    private static String[] textSlotFields(String rawLine) {
        if (rawLine == null) return new String[0];
        String line = rawLine.strip().replace("\\t", "\t");
        if (line.isBlank() || line.startsWith("```")) return new String[0];
        line = line.replace("**", "").replace("`", "").strip();
        line = line.replaceFirst("^[-*+]\\s+", "").strip();
        if (line.contains("\t")) return line.split("\t", 4);

        boolean markdownRow = line.startsWith("|");
        if (markdownRow) line = line.substring(1).strip();
        if (line.endsWith("|")) line = line.substring(0, line.length() - 1).strip();
        if (markdownRow || line.matches("(?i)^[A-Z_]+\\s*\\|.*")) {
            return line.split("\\s*\\|\\s*", 4);
        }

        Matcher delimited = Pattern.compile("^([A-Za-z_]+)\\s*[:：=]\\s*(.*)$").matcher(line);
        if (delimited.matches()) {
            String[] values = delimited.group(2).split("\\s*\\|\\s*", 3);
            String[] fields = new String[Math.min(4, values.length + 1)];
            fields[0] = delimited.group(1);
            System.arraycopy(values, 0, fields, 1, fields.length - 1);
            return fields;
        }
        return new String[0];
    }

    private static String normalizeSlotKey(String value) {
        return value.strip()
                .replace("\"", "")
                .replace("'", "")
                .replace("#", "")
                .strip()
                .toUpperCase(java.util.Locale.ROOT);
    }

    private static JsonObject recoverLoosePlan(String raw, EvidencePack evidence) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonObject intent = new JsonObject();
        JsonArray operations = new JsonArray();
        operations.add("initial");
        intent.add("operations", operations);
        intent.add("target_domain_ids", new JsonArray());
        intent.add("target_flow_ids", new JsonArray());
        intent.add("target_step_ids", new JsonArray());
        intent.add("requested_topics", new JsonArray());
        intent.addProperty("evidence_required", true);
        root.add("refinement_intent", intent);
        root.add("likely_domains", new JsonArray());

        String response = raw == null ? "" : raw.replace('\\', '/');
        String lowerResponse = response.toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashMap<String, Integer> basenameCounts = new java.util.LinkedHashMap<>();
        for (String path : evidence.targetManifest()) {
            String normalized = path.replace('\\', '/');
            String basename = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            basenameCounts.merge(basename, 1, Integer::sum);
        }
        JsonArray paths = new JsonArray();
        for (String path : evidence.targetManifest()) {
            if (!isAnalyzablePath(path)) continue;
            String normalized = path.replace('\\', '/');
            String lowerPath = normalized.toLowerCase(java.util.Locale.ROOT);
            String basename = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
            boolean exactPath = lowerResponse.contains(lowerPath);
            boolean uniqueBasename = basename.length() >= 5
                    && basenameCounts.getOrDefault(basename, 0) == 1
                    && lowerResponse.contains(basename);
            if (exactPath || uniqueBasename) paths.add(path);
            if (paths.size() == 8) break;
        }
        root.add("candidate_paths", paths);

        MapWithOrder identifiers = new MapWithOrder();
        Matcher matcher = IDENTIFIER.matcher(response);
        while (matcher.find()) {
            String identifier = matcher.group();
            if (isUsefulIdentifier(identifier) && !isPlanningKeyword(identifier)) {
                identifiers.add(identifier, identifierScore(identifier));
            }
        }
        JsonArray queries = new JsonArray();
        for (String identifier : identifiers.sorted()) {
            JsonObject query = new JsonObject();
            query.addProperty("literal", identifier);
            query.addProperty("role", "state");
            query.addProperty("reason", "从模型返回的非标准计划中恢复源码符号");
            queries.add(query);
            if (queries.size() == 8) break;
        }
        root.add("queries", queries);
        return root;
    }

    private static boolean isPlanningKeyword(String value) {
        return Set.of(
                "schema", "topic", "operations", "refinement_intent", "target_domain_ids",
                "target_flow_ids", "target_step_ids", "requested_topics", "evidence_required",
                "likely_domain", "likely_domains", "candidate_path", "candidate_paths", "query",
                "queries", "literal", "role", "reason", "purpose"
        ).contains(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static void mergeRecoveredArray(JsonObject root, JsonObject recovered, String name) {
        JsonArray existing = array(root, name);
        if (existing == null || existing.isEmpty()) root.add(name, recovered.getAsJsonArray(name));
    }

    private static String resolveManifestPath(String rawPath, Set<String> manifest) {
        String path = rawPath == null ? "" : rawPath.strip()
                .replace('\\', '/')
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "");
        while (path.startsWith("./")) path = path.substring(2);
        if (manifest.contains(path)) return path;
        String suffix = "/" + path;
        List<String> matches = manifest.stream()
                .filter(candidate -> candidate.replace('\\', '/').endsWith(suffix))
                .limit(2)
                .toList();
        return matches.size() == 1 ? matches.get(0) : "";
    }

    private static void addCsv(JsonArray target, String value) {
        for (String item : value.split(",")) {
            String normalized = item.strip();
            if (!normalized.isBlank()) target.add(normalized);
        }
    }

    public DomainEvidencePlan withUnresolvedQueries(String currentReportJson) {
        try {
            JsonObject report = ModelJsonParser.parseObject(currentReportJson);
            JsonArray unknowns = array(report, "unknowns");
            if (unknowns == null) return this;
            LinkedHashSet<String> existing = new LinkedHashSet<>();
            queries.forEach(query -> existing.add(query.literal()));
            MapWithOrder candidates = new MapWithOrder();
            for (JsonElement unknown : unknowns) {
                String text = unknown.isJsonPrimitive()
                        ? unknown.getAsString()
                        : unknown.isJsonObject() ? unknownText(unknown.getAsJsonObject()) : "";
                if (unknown.isJsonObject()) {
                    JsonArray symbols = array(unknown.getAsJsonObject(), "symbols");
                    for (String symbol : strings(symbols)) {
                        if (isUsefulIdentifier(symbol) && !existing.contains(symbol)) {
                            candidates.add(symbol, identifierScore(symbol) + 10);
                        }
                    }
                }
                Matcher matcher = IDENTIFIER.matcher(text);
                while (matcher.find()) {
                    String identifier = matcher.group();
                    if (!isUsefulIdentifier(identifier) || existing.contains(identifier)) continue;
                    candidates.add(identifier, identifierScore(identifier));
                }
            }
            List<Query> combined = new ArrayList<>(queries);
            for (String identifier : candidates.sorted()) {
                if (combined.size() == 12) break;
                if (existing.add(identifier)) {
                    combined.add(new Query(identifier, "unknown", "补齐当前报告明确标出的源码证据缺口"));
                }
            }
            JsonObject normalized = ModelJsonParser.parseObject(json);
            JsonArray normalizedQueries = new JsonArray();
            for (Query query : combined) {
                JsonObject item = new JsonObject();
                item.addProperty("literal", query.literal());
                item.addProperty("role", query.role());
                item.addProperty("reason", query.reason());
                normalizedQueries.add(item);
            }
            normalized.add("queries", normalizedQueries);
            return new DomainEvidencePlan(normalized.toString(), candidatePaths, List.copyOf(combined), editIntent);
        } catch (RuntimeException ignored) {
            return this;
        }
    }

    public DomainEvidencePlan unresolvedOnly(String currentReportJson) {
        return new DomainEvidencePlan(json, List.of(), List.of(), editIntent).withUnresolvedQueries(currentReportJson);
    }

    public DomainEvidencePlan excludingQueries(Set<String> excluded) {
        if (excluded == null || excluded.isEmpty()) return this;
        List<Query> remaining = queries.stream().filter(query -> !excluded.contains(query.literal())).toList();
        if (remaining.size() == queries.size()) return this;
        JsonObject normalized = ModelJsonParser.parseObject(json);
        JsonArray normalizedQueries = new JsonArray();
        for (Query query : remaining) {
            JsonObject item = new JsonObject();
            item.addProperty("literal", query.literal());
            item.addProperty("role", query.role());
            item.addProperty("reason", query.reason());
            normalizedQueries.add(item);
        }
        normalized.add("queries", normalizedQueries);
        return new DomainEvidencePlan(normalized.toString(), candidatePaths, remaining, editIntent);
    }

    public DomainEvidencePlan retainingQueriesIn(String sourceText) {
        String source = sourceText == null ? "" : sourceText;
        Set<String> removed = queries.stream()
                .map(Query::literal)
                .filter(literal -> !source.contains(literal))
                .collect(java.util.stream.Collectors.toSet());
        return excludingQueries(removed);
    }

    public record EditIntent(
            Set<Operation> operations,
            List<String> targetDomainIds,
            List<String> targetFlowIds,
            List<String> targetStepIds,
            List<String> requestedTopics,
            boolean evidenceRequired
    ) {
        static EditIntent initial() {
            return new EditIntent(Set.of(Operation.INITIAL), List.of(), List.of(), List.of(), List.of(), true);
        }

        static EditIntent parse(JsonObject root) {
            if (!root.has("refinement_intent") || !root.get("refinement_intent").isJsonObject()) return initial();
            JsonObject value = root.getAsJsonObject("refinement_intent");
            LinkedHashSet<Operation> operations = new LinkedHashSet<>();
            JsonArray operationItems = array(value, "operations");
            if (operationItems != null) {
                for (String item : strings(operationItems)) operations.add(Operation.parse(item));
            }
            String singular = string(value, "operation");
            if (!singular.isBlank()) operations.add(Operation.parse(singular));
            operations.remove(Operation.UNKNOWN);
            if (operations.isEmpty()) operations.add(Operation.UNKNOWN);
            boolean evidenceRequired = value.has("evidence_required")
                    ? value.get("evidence_required").getAsBoolean()
                    : operations.stream().anyMatch(Operation::normallyRequiresEvidence);
            if (operations.contains(Operation.UNKNOWN)) evidenceRequired = true;
            return new EditIntent(
                    Set.copyOf(operations),
                    strings(array(value, "target_domain_ids")),
                    strings(array(value, "target_flow_ids")),
                    strings(array(value, "target_step_ids")),
                    strings(array(value, "requested_topics")),
                    evidenceRequired
            );
        }

        boolean structural() {
            return operations.stream().anyMatch(Operation::structural);
        }

        boolean has(Operation operation) {
            return operations.contains(operation);
        }
    }

    public enum Operation {
        INITIAL, UPDATE_EXPLANATION, MERGE_DOMAINS, SUPPLEMENT_DOMAIN, ADD_DOMAIN, CORRECT_FLOW,
        ADD_NODES, REMOVE_NODES, MOVE_NODES, REORDER_NODES, MERGE_FLOWS, SPLIT_FLOW, UNKNOWN;

        static Operation parse(String value) {
            try {
                return value == null ? UNKNOWN : valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }

        boolean normallyRequiresEvidence() {
            return switch (this) {
                case INITIAL, SUPPLEMENT_DOMAIN, ADD_DOMAIN, CORRECT_FLOW, ADD_NODES, UNKNOWN -> true;
                default -> false;
            };
        }

        boolean structural() {
            return switch (this) {
                case MERGE_DOMAINS, ADD_DOMAIN, ADD_NODES, REMOVE_NODES, MOVE_NODES, REORDER_NODES,
                        MERGE_FLOWS, SPLIT_FLOW -> true;
                default -> false;
            };
        }
    }

    static DomainEvidencePlan frontierFromResolution(
            String rawResolution,
            String currentReportJson,
            String sourceEvidence,
            EvidencePack evidence
    ) {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("schema", SCHEMA);
        normalized.add("candidate_paths", new JsonArray());
        normalized.add("queries", new JsonArray());
        try {
            JsonObject resolution = ModelJsonParser.parseObject(rawResolution);
            JsonObject report = ModelJsonParser.parseObject(currentReportJson);
            JsonObject evidenceRoot = ModelJsonParser.parseObject(sourceEvidence);
            Set<String> questions = new LinkedHashSet<>();
            JsonArray unknowns = array(report, "unknowns");
            if (unknowns != null) {
                for (JsonElement unknown : unknowns) {
                    String question = unknown.isJsonPrimitive() ? unknown.getAsString()
                            : unknown.isJsonObject() ? unknownText(unknown.getAsJsonObject()) : "";
                    if (!question.isBlank()) questions.add(question);
                }
            }
            Set<String> manifest = Set.copyOf(evidence.targetManifest());
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<Query> queries = new ArrayList<>();
            JsonArray frontier = array(resolution, "next_frontier_queries");
            if (frontier != null) {
                for (JsonElement element : frontier) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    String question = string(item, "question").strip();
                    String literal = string(item, "literal").strip();
                    if (!questions.contains(question) || literal.length() < 3 || literal.length() > 100
                            || literal.contains("\n") || isOverbroad(literal) || !isUsefulIdentifier(literal)
                            || !seen.add(literal)) continue;
                    List<String> sourcePaths = evidencePathsContaining(evidenceRoot, literal).stream()
                            .filter(manifest::contains).filter(DomainEvidencePlan::isAnalyzablePath).toList();
                    if (sourcePaths.isEmpty()) continue;
                    paths.addAll(sourcePaths);
                    queries.add(new Query(literal, "unknown", fallback(
                            string(item, "reason"), "沿本轮源码新暴露的直接关系继续确认待确认项")));
                    if (queries.size() == 4) break;
                }
            }
            JsonArray normalizedPaths = new JsonArray();
            paths.stream().limit(8).forEach(normalizedPaths::add);
            JsonArray normalizedQueries = new JsonArray();
            for (Query query : queries) {
                JsonObject item = new JsonObject();
                item.addProperty("literal", query.literal());
                item.addProperty("role", query.role());
                item.addProperty("reason", query.reason());
                normalizedQueries.add(item);
            }
            normalized.add("candidate_paths", normalizedPaths);
            normalized.add("queries", normalizedQueries);
            return new DomainEvidencePlan(normalized.toString(),
                    paths.stream().limit(8).toList(), List.copyOf(queries));
        } catch (RuntimeException ignored) {
            return new DomainEvidencePlan(normalized.toString(), List.of(), List.of());
        }
    }

    private static List<String> evidencePathsContaining(JsonObject evidence, String literal) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collectEvidencePaths(array(evidence, "control_flow_excerpts"), "excerpt", literal, paths);
        collectEvidencePaths(array(evidence, "candidate_excerpts"), "excerpt", literal, paths);
        JsonArray results = array(evidence, "query_results");
        if (results != null) {
            for (JsonElement result : results) {
                if (result.isJsonObject()) {
                    collectEvidencePaths(array(result.getAsJsonObject(), "matches"), "snippet", literal, paths);
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void collectEvidencePaths(
            JsonArray sources,
            String textField,
            String literal,
            Set<String> paths
    ) {
        if (sources == null) return;
        for (JsonElement element : sources) {
            if (!element.isJsonObject()) continue;
            JsonObject source = element.getAsJsonObject();
            if (string(source, textField).contains(literal)) paths.add(string(source, "path"));
        }
    }

    static boolean isAnalyzablePath(String path) {
        String lower = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return !lower.startsWith("graphify-out/")
                && !lower.contains("/graphify-out/")
                && !lower.contains("/vendor/")
                && !lower.contains("/node_modules/")
                && !lower.contains("/dist/")
                && !lower.contains("/build/")
                && !lower.endsWith(".min.js")
                && !lower.endsWith(".md")
                && !lower.endsWith(".mdx")
                && !lower.endsWith(".rst")
                && !lower.endsWith(".adoc")
                && !lower.endsWith(".txt")
                && !lower.endsWith(".html")
                && !lower.endsWith(".htm")
                && !lower.endsWith(".lock")
                && !lower.endsWith(".sum");
    }

    private static boolean isOverbroad(String literal) {
        return Set.of(
                "chat", "creator", "data", "error", "info", "profile", "status", "stream",
                "token", "message", "history", "fallback"
        )
                .contains(literal.strip().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isUsefulIdentifier(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (isOverbroad(value)) return false;
        if (Set.of("websocket", "tablestore", "direct_source", "source_backed_walkthrough",
                        "source_evidence", "current_snapshot")
                .contains(lower)) return false;
        boolean underscore = value.indexOf('_') >= 0;
        boolean camel = value.substring(1).chars().anyMatch(Character::isUpperCase);
        boolean pascal = Character.isUpperCase(value.charAt(0));
        return underscore || camel || pascal;
    }

    private static int identifierScore(String value) {
        int score = Math.min(4, value.length() / 8);
        if (value.indexOf('_') >= 0) score += 3;
        if (value.substring(1).chars().anyMatch(Character::isUpperCase)) score += 4;
        if (Character.isUpperCase(value.charAt(0))) score += 2;
        return score;
    }

    private static final class MapWithOrder {
        private final java.util.LinkedHashMap<String, Integer> values = new java.util.LinkedHashMap<>();

        void add(String value, int score) {
            values.merge(value, score, Math::max);
        }

        List<String> sorted() {
            return values.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }

    private static String stripFence(String value) {
        String stripped = value.strip();
        if (!stripped.startsWith("```")) return stripped;
        int firstLine = stripped.indexOf('\n');
        int lastFence = stripped.lastIndexOf("```");
        return firstLine >= 0 && lastFence > firstLine ? stripped.substring(firstLine + 1, lastFence).strip() : stripped;
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : null;
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private static String unknownText(JsonObject unknown) {
        for (String field : List.of("question", "meaning", "title")) {
            String value = string(unknown, field);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> strings(JsonArray array) {
        if (array == null) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement item : array) if (item.isJsonPrimitive()) values.add(item.getAsString());
        return values;
    }

    public record Query(String literal, String role, String reason) {
    }
}
