package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class DomainEvidenceResolutionAssembler {
    private static final String SCHEMA = "business-domain-evidence-resolution/v1";
    private static final Set<String> FLOW_STRING_FIELDS = Set.of("summary", "outcome", "end_title");
    private static final Set<String> FLOW_ARRAY_FIELDS = Set.of(
            "data_reads", "data_writes", "failure_paths", "business_rules", "data_origins", "data_flow",
            "consumer_targets"
    );
    private static final Set<String> FLOW_OBJECT_FIELDS = Set.of("entry_source");
    private static final Set<String> BUSINESS_OBJECT_STRING_FIELDS = Set.of(
            "plain_meaning", "storage_kind", "lifecycle"
    );

    JsonObject apply(String raw, JsonObject currentReport, EvidencePack evidence) throws ModelClientException {
        JsonObject patch = parse(raw);
        if (!SCHEMA.equals(string(patch, "schema"))) {
            throw new ModelClientException("业务补证结果 schema 无效");
        }
        JsonObject report = currentReport.deepCopy();
        Map<String, JsonElement> unresolved = currentUnknowns(report);
        Set<String> manifest = Set.copyOf(evidence.targetManifest());
        JsonArray nodes = array(report, "nodes");
        JsonArray history = array(report, "evidence_resolutions");
        int nodeSequence = nodes.size() + 1;
        int originalUnknownCount = unresolved.size();
        int resolvedCount = 0;
        Map<String, String> resolutionNodeIds = new LinkedHashMap<>();

        for (JsonElement element : array(patch, "resolutions")) {
            if (!element.isJsonObject()) continue;
            JsonObject resolution = element.getAsJsonObject();
            String question = string(resolution, "question");
            if (!unresolved.containsKey(question)) continue;
            String status = normalizeStatus(string(resolution, "status"));
            if ("unresolved".equals(status)) continue;
            String file = string(resolution, "file").replace('\\', '/');
            int line = positiveInteger(resolution, "line", 1);
            String symbol = string(resolution, "symbol");
            if (!manifest.contains(file) || symbol.isBlank()) continue;

            String nodeId = "domain-resolution-node-" + nodeSequence++;
            JsonObject node = new JsonObject();
            node.addProperty("id", nodeId);
            node.addProperty("kind", "method");
            node.addProperty("label", symbol);
            node.addProperty("service", "evidence-resolution");
            node.addProperty("module", "evidence-resolution");
            node.addProperty("file", file);
            node.addProperty("line", line);
            node.addProperty("end_line", line);
            node.addProperty("responsibility", string(resolution, "conclusion"));
            node.add("inputs", new JsonArray());
            node.add("outputs", new JsonArray());
            String flowId = string(resolution, "flow_id");
            JsonArray featureIds = new JsonArray();
            if (!flowId.isBlank()) featureIds.add(flowId);
            node.add("feature_ids", featureIds);
            node.add("feature_roles", new JsonObject());
            node.addProperty("change", "unchanged");
            node.add("changed_in_commits", new JsonArray());
            node.addProperty("source_kind", "repository");
            node.addProperty("evidence", normalizeEvidence(string(resolution, "evidence")));
            node.addProperty("confidence", normalizeConfidence(string(resolution, "confidence")));
            nodes.add(node);

            JsonObject archived = new JsonObject();
            archived.addProperty("question", question);
            archived.addProperty("status", status);
            archived.addProperty("conclusion", string(resolution, "conclusion"));
            archived.addProperty("source_node_id", nodeId);
            history.add(archived);
            resolutionNodeIds.put(question, nodeId);
            unresolved.remove(question);
            resolvedCount++;
        }

        // A follow-up round exists to converge the current questions, not expand their wording.
        if (resolvedCount > 0 && unresolved.size() < originalUnknownCount) {
            for (JsonElement element : array(patch, "new_unknowns")) {
                String question = element.isJsonPrimitive() ? element.getAsString()
                        : element.isJsonObject() ? unknownText(element.getAsJsonObject()) : "";
                if (!question.isBlank() && unresolved.size() < originalUnknownCount) {
                    unresolved.putIfAbsent(question, normalizedUnknown(element, question));
                    break;
                }
            }
        }
        JsonArray remaining = new JsonArray();
        unresolved.forEach((question, value) -> remaining.add(normalizedUnknown(value, question)));
        report.add("unknowns", remaining);
        report.add("nodes", nodes);
        report.add("evidence_resolutions", history);

        if (resolvedCount > 0) {
            String summary = string(patch, "report_summary");
            if (!summary.isBlank()) {
                report.addProperty("summary", summary);
                JsonObject readerGuide = object(report, "reader_guide");
                readerGuide.addProperty("subtitle", summary);
                report.add("reader_guide", readerGuide);
            }
            applyOverviewUpdate(report, object(patch, "overview_update"));
            applyFlowUpdates(report, array(patch, "flow_updates"));
            applyStepUpdates(report, array(patch, "step_updates"), resolutionNodeIds);
        }
        return report;
    }

    private void applyStepUpdates(JsonObject report, JsonArray updates, Map<String, String> resolutionNodeIds) {
        JsonObject flowMap = object(report, "flow_map");
        for (JsonElement element : updates) {
            if (!element.isJsonObject()) continue;
            JsonObject update = element.getAsJsonObject();
            JsonObject step = findFlow(flowMap, string(update, "step_id"));
            if (step == null) continue;
            String summary = string(update, "summary");
            if (!summary.isBlank()) step.addProperty("summary", summary);
            if (update.has("state_effects") && update.get("state_effects").isJsonArray()) {
                step.add("state_effects", update.getAsJsonArray("state_effects").deepCopy());
            }
            String sourceNodeId = resolutionNodeIds.get(string(update, "source_question"));
            if (sourceNodeId != null) {
                removeInferredStepSources(step, array(report, "nodes"));
                appendUnique(step, "source_node_ids", sourceNodeId);
            }
        }
    }

    private void applyOverviewUpdate(JsonObject report, JsonObject update) {
        if (update == null || update.isEmpty()) return;
        JsonObject overview = object(report, "business_overview");
        String purpose = string(update, "purpose");
        if (!purpose.isBlank()) overview.addProperty("purpose", purpose);
        if (update.has("plain_story") && update.get("plain_story").isJsonArray()) {
            overview.add("plain_story", update.getAsJsonArray("plain_story").deepCopy());
        }
        for (String field : java.util.List.of("actors", "domain_relationships", "terms")) {
            if (update.has(field) && update.get(field).isJsonArray()) {
                overview.add(field, update.getAsJsonArray(field).deepCopy());
            }
        }
        JsonArray objects = array(overview, "business_objects");
        for (JsonElement element : array(update, "business_object_updates")) {
            if (!element.isJsonObject()) continue;
            JsonObject objectUpdate = element.getAsJsonObject();
            JsonObject businessObject = findById(objects, string(objectUpdate, "id"));
            if (businessObject == null) continue;
            for (String field : BUSINESS_OBJECT_STRING_FIELDS) {
                String value = string(objectUpdate, field);
                if (!value.isBlank()) businessObject.addProperty(field, value);
            }
            if (objectUpdate.has("field_groups") && objectUpdate.get("field_groups").isJsonArray()) {
                businessObject.add("field_groups", objectUpdate.getAsJsonArray("field_groups").deepCopy());
            }
            if (objectUpdate.has("supporting_sources") && objectUpdate.get("supporting_sources").isJsonArray()) {
                businessObject.add("supporting_sources", objectUpdate.getAsJsonArray("supporting_sources").deepCopy());
            }
        }
        report.add("business_overview", overview);
    }

    private JsonObject findById(JsonArray values, String id) {
        if (id.isBlank()) return null;
        for (JsonElement element : values) {
            if (element.isJsonObject() && id.equals(string(element.getAsJsonObject(), "id"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private void appendUnique(JsonObject object, String field, String value) {
        JsonArray values = array(object, field);
        for (JsonElement element : values) {
            if (element.isJsonPrimitive() && value.equals(element.getAsString())) return;
        }
        values.add(value);
        object.add(field, values);
    }

    private void removeInferredStepSources(JsonObject step, JsonArray nodes) {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement element : nodes) {
            if (element.isJsonObject()) byId.put(string(element.getAsJsonObject(), "id"), element.getAsJsonObject());
        }
        JsonArray retained = new JsonArray();
        for (JsonElement element : array(step, "source_node_ids")) {
            if (!element.isJsonPrimitive()) continue;
            JsonObject node = byId.get(element.getAsString());
            if (node == null || !isStaleInferredNode(node)) retained.add(element.deepCopy());
        }
        step.add("source_node_ids", retained);
    }

    private boolean isStaleInferredNode(JsonObject node) {
        if (!"inferred".equals(string(node, "evidence"))) return false;
        String responsibility = string(node, "responsibility");
        return java.util.List.of("待确认", "无法确认", "不能确认", "未完整展示", "未展示", "未知")
                .stream().anyMatch(responsibility::contains);
    }

    private void applyFlowUpdates(JsonObject report, JsonArray updates) {
        JsonObject flowMap = object(report, "flow_map");
        for (JsonElement element : updates) {
            if (!element.isJsonObject()) continue;
            JsonObject update = element.getAsJsonObject();
            JsonObject flow = findFlow(flowMap, string(update, "flow_id"));
            if (flow == null) continue;
            for (String field : FLOW_STRING_FIELDS) {
                String value = string(update, field);
                if (!value.isBlank()) flow.addProperty(field, value);
            }
            for (String field : FLOW_ARRAY_FIELDS) {
                if (update.has(field) && update.get(field).isJsonArray()) {
                    JsonArray replacement = update.getAsJsonArray(field);
                    JsonArray existing = array(flow, field);
                    boolean protectsMainDataLineage = Set.of("data_origins", "data_flow").contains(field);
                    if (!protectsMainDataLineage || existing.isEmpty() || replacement.size() >= existing.size()) {
                        flow.add(field, replacement.deepCopy());
                    }
                }
            }
            for (String field : FLOW_OBJECT_FIELDS) {
                if (update.has(field) && update.get(field).isJsonObject()) {
                    flow.add(field, update.getAsJsonObject(field).deepCopy());
                }
            }
        }
    }

    private JsonObject findFlow(JsonObject flow, String id) {
        if (id.isBlank() || flow == null) return null;
        if (id.equals(string(flow, "id"))) return flow;
        for (JsonElement child : array(flow, "children")) {
            if (!child.isJsonObject()) continue;
            JsonObject found = findFlow(child.getAsJsonObject(), id);
            if (found != null) return found;
        }
        return null;
    }

    private Map<String, JsonElement> currentUnknowns(JsonObject report) {
        Map<String, JsonElement> values = new LinkedHashMap<>();
        for (JsonElement item : array(report, "unknowns")) {
            String text = item.isJsonPrimitive() ? item.getAsString()
                    : item.isJsonObject() ? unknownText(item.getAsJsonObject()) : "";
            if (!text.isBlank()) values.putIfAbsent(text, item.deepCopy());
        }
        return values;
    }

    private JsonElement normalizedUnknown(JsonElement element, String question) {
        if (element.isJsonObject()) return element.deepCopy();
        JsonObject unknown = new JsonObject();
        unknown.addProperty("question", question);
        unknown.addProperty("kind", "outcome");
        unknown.addProperty("flow_id", "");
        unknown.add("symbols", new JsonArray());
        unknown.addProperty("why_material", question);
        return unknown;
    }

    private String unknownText(JsonObject unknown) {
        for (String field : java.util.List.of("question", "meaning", "title")) {
            String value = string(unknown, field);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private JsonObject parse(String raw) throws ModelClientException {
        String value = raw.strip();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) value = value.substring(firstLine + 1, lastFence).strip();
        }
        try {
            return JsonParser.parseString(value).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new ModelClientException("模型没有返回合法的业务补证结果：" + exception.getMessage(), exception);
        }
    }

    private String normalizeStatus(String value) {
        return Set.of("confirmed", "disproved").contains(value) ? value : "unresolved";
    }

    private String normalizeEvidence(String value) {
        return Set.of("direct_source", "source_backed_walkthrough").contains(value) ? value : "inferred";
    }

    private String normalizeConfidence(String value) {
        return Set.of("high", "medium", "low").contains(value) ? value : "low";
    }

    private int positiveInteger(JsonObject object, String name, int fallback) {
        try {
            return object.has(name) ? Math.max(1, object.get(name).getAsInt()) : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private JsonArray array(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonArray()
                ? object.getAsJsonArray(name)
                : new JsonArray();
    }

    private JsonObject object(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonObject()
                ? object.getAsJsonObject(name)
                : new JsonObject();
    }

    private String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString()
                : "";
    }
}
