package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record EvidencePlan(String json, List<ChangeGroup> groups) {
    private static final String SCHEMA = "change-evidence-plan/v1";

    public static EvidencePlan parse(String raw, EvidencePack evidence) throws ModelClientException {
        JsonObject root;
        try {
            root = JsonParser.parseString(stripFence(raw)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new ModelClientException("Codex 没有返回合法的改动主题计划：" + exception.getMessage(), exception);
        }
        if (!SCHEMA.equals(string(root, "schema"))) {
            throw new ModelClientException("Codex 改动主题计划 schema 无效");
        }

        Set<String> allowedPaths = new LinkedHashSet<>(evidence.aggregateChangedPaths());
        Set<String> allowedCommits = evidence.commits().stream()
                .map(item -> item.commit().hash())
                .collect(java.util.stream.Collectors.toSet());
        List<ChangeGroup> groups = new ArrayList<>();
        JsonArray items = array(root, "change_groups");
        if (items != null) {
            for (JsonElement item : items) {
                if (!item.isJsonObject()) continue;
                JsonObject group = item.getAsJsonObject();
                String id = string(group, "id");
                String title = string(group, "title");
                if (id.isBlank() || title.isBlank()) continue;
                List<String> paths = strings(array(group, "changed_paths")).stream()
                        .filter(allowedPaths::contains).distinct().toList();
                List<String> commits = strings(array(group, "commit_ids")).stream()
                        .filter(allowedCommits::contains).distinct().toList();
                List<EvidenceQuery> queries = new ArrayList<>();
                JsonArray requested = array(group, "evidence_queries");
                if (requested != null) {
                    for (JsonElement queryItem : requested) {
                        if (!queryItem.isJsonObject()) continue;
                        JsonObject query = queryItem.getAsJsonObject();
                        String literal = string(query, "literal").strip();
                        if (literal.length() < 3 || literal.length() > 120 || literal.contains("\n")) continue;
                        queries.add(new EvidenceQuery(literal, string(query, "reason")));
                    }
                }
                groups.add(new ChangeGroup(id, title, string(group, "purpose"), paths, commits, List.copyOf(queries)));
            }
        }
        groups = mergeDuplicateGroups(groups);
        if (groups.isEmpty()) {
            throw new ModelClientException("Codex 没有从聚合差异中识别出任何改动主题");
        }
        JsonArray normalizedGroups = new JsonArray();
        for (ChangeGroup group : groups) {
            JsonObject item = new JsonObject();
            item.addProperty("id", group.id());
            item.addProperty("title", group.title());
            item.addProperty("purpose", group.purpose());
            item.add("changed_paths", jsonStrings(group.changedPaths()));
            item.add("commit_ids", jsonStrings(group.commitIds()));
            JsonArray queries = new JsonArray();
            for (EvidenceQuery query : group.evidenceQueries()) {
                JsonObject queryJson = new JsonObject();
                queryJson.addProperty("literal", query.literal());
                queryJson.addProperty("reason", query.reason());
                queries.add(queryJson);
            }
            item.add("evidence_queries", queries);
            normalizedGroups.add(item);
        }
        root.add("change_groups", normalizedGroups);
        return new EvidencePlan(root.toString(), List.copyOf(groups));
    }

    private static JsonArray jsonStrings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static List<ChangeGroup> mergeDuplicateGroups(List<ChangeGroup> groups) {
        List<ChangeGroup> merged = new ArrayList<>();
        for (ChangeGroup candidate : groups) {
            int existingIndex = -1;
            for (int index = 0; index < merged.size(); index++) {
                ChangeGroup existing = merged.get(index);
                if (existing.id().equals(candidate.id())) {
                    existingIndex = index;
                    break;
                }
            }
            if (existingIndex < 0) {
                merged.add(candidate);
                continue;
            }
            ChangeGroup existing = merged.get(existingIndex);
            LinkedHashSet<String> queries = new LinkedHashSet<>();
            List<EvidenceQuery> combinedQueries = new ArrayList<>();
            for (EvidenceQuery query : concat(existing.evidenceQueries(), candidate.evidenceQueries())) {
                if (queries.add(query.literal())) combinedQueries.add(query);
            }
            merged.set(existingIndex, new ChangeGroup(
                    existing.id(),
                    existing.title() + "；" + candidate.title(),
                    existing.purpose() + "；" + candidate.purpose(),
                    distinct(concat(existing.changedPaths(), candidate.changedPaths())),
                    distinct(concat(existing.commitIds(), candidate.commitIds())),
                    List.copyOf(combinedQueries)
            ));
        }
        return merged;
    }

    private static <T> List<T> distinct(List<T> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> values = new ArrayList<>(first);
        values.addAll(second);
        return values;
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

    private static List<String> strings(JsonArray array) {
        if (array == null) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement item : array) if (item.isJsonPrimitive()) values.add(item.getAsString());
        return values;
    }

    public record ChangeGroup(
            String id,
            String title,
            String purpose,
            List<String> changedPaths,
            List<String> commitIds,
            List<EvidenceQuery> evidenceQueries
    ) {
    }

    public record EvidenceQuery(String literal, String reason) {
    }
}
