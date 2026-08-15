package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record DomainEvidencePlan(String json, List<String> candidatePaths, List<Query> queries) {
    private static final String SCHEMA = "business-domain-evidence-plan/v1";
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]{4,}\\b");

    public static DomainEvidencePlan parse(String raw, EvidencePack evidence) throws ModelClientException {
        JsonObject root;
        try {
            root = JsonParser.parseString(stripFence(raw)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new ModelClientException("模型没有返回合法的业务证据计划：" + exception.getMessage(), exception);
        }
        if (!SCHEMA.equals(string(root, "schema"))) {
            throw new ModelClientException("业务证据计划 schema 无效");
        }
        Set<String> manifest = Set.copyOf(evidence.targetManifest());
        List<String> paths = strings(array(root, "candidate_paths")).stream()
                .filter(manifest::contains)
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
        if (paths.isEmpty() && queries.isEmpty()) {
            throw new ModelClientException("模型没有为该业务主题定位到可搜索的源码范围");
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
        return new DomainEvidencePlan(root.toString(), paths, List.copyOf(queries));
    }

    public DomainEvidencePlan withUnresolvedQueries(String currentReportJson) {
        try {
            JsonObject report = JsonParser.parseString(currentReportJson).getAsJsonObject();
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
            JsonObject normalized = JsonParser.parseString(json).getAsJsonObject();
            JsonArray normalizedQueries = new JsonArray();
            for (Query query : combined) {
                JsonObject item = new JsonObject();
                item.addProperty("literal", query.literal());
                item.addProperty("role", query.role());
                item.addProperty("reason", query.reason());
                normalizedQueries.add(item);
            }
            normalized.add("queries", normalizedQueries);
            return new DomainEvidencePlan(normalized.toString(), candidatePaths, List.copyOf(combined));
        } catch (RuntimeException ignored) {
            return this;
        }
    }

    public DomainEvidencePlan unresolvedOnly(String currentReportJson) {
        return new DomainEvidencePlan(json, List.of(), List.of()).withUnresolvedQueries(currentReportJson);
    }

    public DomainEvidencePlan excludingQueries(Set<String> excluded) {
        if (excluded == null || excluded.isEmpty()) return this;
        List<Query> remaining = queries.stream().filter(query -> !excluded.contains(query.literal())).toList();
        if (remaining.size() == queries.size()) return this;
        JsonObject normalized = JsonParser.parseString(json).getAsJsonObject();
        JsonArray normalizedQueries = new JsonArray();
        for (Query query : remaining) {
            JsonObject item = new JsonObject();
            item.addProperty("literal", query.literal());
            item.addProperty("role", query.role());
            item.addProperty("reason", query.reason());
            normalizedQueries.add(item);
        }
        normalized.add("queries", normalizedQueries);
        return new DomainEvidencePlan(normalized.toString(), candidatePaths, remaining);
    }

    public DomainEvidencePlan retainingQueriesIn(String sourceText) {
        String source = sourceText == null ? "" : sourceText;
        Set<String> removed = queries.stream()
                .map(Query::literal)
                .filter(literal -> !source.contains(literal))
                .collect(java.util.stream.Collectors.toSet());
        return excludingQueries(removed);
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
            JsonObject resolution = JsonParser.parseString(stripFence(rawResolution)).getAsJsonObject();
            JsonObject report = JsonParser.parseString(currentReportJson).getAsJsonObject();
            JsonObject evidenceRoot = JsonParser.parseString(sourceEvidence).getAsJsonObject();
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
        return !lower.contains("/.repomind/")
                && !lower.startsWith("graphify-out/")
                && !lower.contains("/graphify-out/")
                && !lower.contains("/vendor/")
                && !lower.contains("/node_modules/")
                && !lower.contains("/dist/")
                && !lower.contains("/build/")
                && !lower.endsWith(".min.js")
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
