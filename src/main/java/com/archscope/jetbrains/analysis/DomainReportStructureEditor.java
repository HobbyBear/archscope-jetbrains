package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Applies explicit report-layout instructions locally so model prose cannot silently preserve stale structure. */
final class DomainReportStructureEditor {
    Intent intent(String instruction) {
        String value = instruction == null ? "" : instruction.strip().toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.contains("不要合并") || value.contains("别合并")
                || value.contains("do not merge") || value.contains("don't merge")) return Intent.NONE;
        boolean mergeWord = value.contains("合并") || value.contains("合成") || value.contains("整合")
                || value.contains("merge") || value.contains("combine") || value.contains("consolidate");
        boolean oneDiagram = value.contains("一个流程图") || value.contains("一张流程图")
                || value.contains("单个流程图") || value.contains("统一流程图")
                || value.contains("single flow") || value.contains("one flow diagram");
        boolean allTargets = value.contains("所有") || value.contains("全部") || value.contains("整体")
                || value.contains("all ") || value.contains("every ") || value.contains(" into one")
                || value.contains("成一个") || value.contains("为一个");
        boolean flows = oneDiagram
                || (mergeWord && allTargets && (value.contains("流程") || value.contains("flow")));
        boolean domains = mergeWord && allTargets
                && (value.contains("业务域") || value.contains("领域") || value.contains("domain"))
                && !oneDiagram;
        if (flows && domains) return Intent.MERGE_FLOWS_AND_DOMAINS;
        if (domains) return Intent.MERGE_DOMAINS;
        return flows ? Intent.MERGE_FLOWS : Intent.NONE;
    }

    JsonObject apply(JsonObject currentReport, Intent intent, AnalysisRequest.OutputLanguage language)
            throws ModelClientException {
        JsonObject report = currentReport.deepCopy();
        if (intent.mergeFlows()) mergeFlows(report, language.isEnglish());
        if (intent.mergeDomains()) mergeDomains(report, language.isEnglish());
        verify(report, intent);
        return report;
    }

    private void mergeFlows(JsonObject report, boolean english) throws ModelClientException {
        JsonObject root = object(report, "flow_map");
        JsonArray flows = array(root, "children");
        if (flows.size() <= 1) return;
        JsonObject first = flows.get(0).getAsJsonObject();
        JsonObject merged = first.deepCopy();
        String mergedId = "merged-business-flow";
        Set<String> oldFlowIds = new LinkedHashSet<>();
        JsonArray children = new JsonArray();
        JsonArray origins = new JsonArray();
        JsonArray dataFlow = new JsonArray();
        Map<String, String> originIds = new LinkedHashMap<>();
        for (String field : MERGED_ARRAY_FIELDS) merged.add(field, new JsonArray());

        for (int flowIndex = 0; flowIndex < flows.size(); flowIndex++) {
            JsonObject flow = flows.get(flowIndex).getAsJsonObject();
            oldFlowIds.add(string(flow, "id"));
            appendObjects(children, array(flow, "children"));
            for (String field : MERGED_ARRAY_FIELDS) appendUnique(merged.getAsJsonArray(field), array(flow, field));

            int originIndex = 0;
            for (JsonElement element : array(flow, "data_origins")) {
                if (!element.isJsonObject()) continue;
                JsonObject origin = element.getAsJsonObject().deepCopy();
                String oldId = string(origin, "id");
                String newId = "merged-origin-" + (flowIndex + 1) + '-' + (++originIndex);
                originIds.put((flowIndex + 1) + "\u0000" + oldId, newId);
                origin.addProperty("id", newId);
                if (flowIndex > 0 && "primary".equals(string(origin, "role"))) {
                    origin.addProperty("role", "control");
                }
                origins.add(origin);
            }
            for (JsonElement element : array(flow, "data_flow")) {
                if (!element.isJsonObject()) continue;
                JsonObject hop = element.getAsJsonObject().deepCopy();
                String oldLineage = string(hop, "lineage_id");
                String newLineage = originIds.get((flowIndex + 1) + "\u0000" + oldLineage);
                if (newLineage != null) hop.addProperty("lineage_id", newLineage);
                dataFlow.add(hop);
            }
        }
        merged.addProperty("id", mergedId);
        merged.addProperty("title", fallback(string(report, "title"), english ? "Unified business flow" : "统一业务流程"));
        merged.addProperty("summary", fallback(string(report, "summary"), string(first, "summary")));
        merged.add("children", children);
        merged.add("data_origins", origins);
        merged.add("data_flow", dataFlow);
        if (!origins.isEmpty()) merged.addProperty("primary_origin_id", string(origins.get(0).getAsJsonObject(), "id"));
        root.add("children", arrayOf(merged));
        remapFlowReferences(report, oldFlowIds, mergedId);
    }

    private void mergeDomains(JsonObject report, boolean english) throws ModelClientException {
        JsonArray domains = array(report, "business_domains");
        if (domains.size() <= 1) return;
        JsonObject first = domains.get(0).getAsJsonObject().deepCopy();
        String domainId = string(first, "id");
        Set<String> oldDomainIds = new LinkedHashSet<>();
        JsonArray sourceNodeIds = new JsonArray();
        JsonArray flowIds = new JsonArray();
        for (JsonElement element : domains) {
            JsonObject domain = element.getAsJsonObject();
            oldDomainIds.add(string(domain, "id"));
            appendUnique(sourceNodeIds, array(domain, "source_node_ids"));
            appendUnique(flowIds, array(domain, "flow_ids"));
        }
        first.addProperty("name", fallback(string(report, "title"), english ? "Unified business domain" : "统一业务域"));
        first.addProperty("purpose", fallback(string(report, "summary"), string(first, "purpose")));
        first.add("source_node_ids", sourceNodeIds);
        first.add("flow_ids", flowIds);
        first.add("depends_on", new JsonArray());
        report.add("business_domains", arrayOf(first));

        for (JsonElement element : array(report, "nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (oldDomainIds.contains(string(node, "service"))) node.addProperty("service", domainId);
            if (oldDomainIds.contains(string(node, "module"))) node.addProperty("module", domainId);
        }
        JsonObject design = object(report, "architecture_design");
        JsonArray lanes = array(design, "lanes");
        if (!lanes.isEmpty()) {
            JsonObject lane = lanes.get(0).getAsJsonObject().deepCopy();
            String laneId = string(lane, "id");
            lane.addProperty("name", string(first, "name"));
            lane.addProperty("represents", string(first, "purpose"));
            lane.add("source_node_ids", sourceNodeIds.deepCopy());
            design.add("lanes", arrayOf(lane));
            design.add("contracts", new JsonArray());
            remapLaneReferences(object(report, "flow_map"), laneId);
        }
        JsonObject overview = object(report, "business_overview");
        overview.add("domain_relationships", new JsonArray());
        JsonArray readingOrder = new JsonArray();
        readingOrder.add(domainId);
        overview.add("reading_order", readingOrder);
    }

    private void remapFlowReferences(JsonObject report, Set<String> oldIds, String mergedId) {
        for (JsonElement element : array(report, "business_domains")) {
            JsonObject domain = element.getAsJsonObject();
            if (containsAny(array(domain, "flow_ids"), oldIds)) domain.add("flow_ids", strings(mergedId));
        }
        for (String collection : Set.of("nodes", "edges")) {
            for (JsonElement element : array(report, collection)) {
                JsonObject item = element.getAsJsonObject();
                if (containsAny(array(item, "feature_ids"), oldIds)) item.add("feature_ids", strings(mergedId));
                if (item.has("feature_roles") && item.get("feature_roles").isJsonObject()) {
                    JsonObject roles = item.getAsJsonObject("feature_roles");
                    String role = oldIds.stream().filter(roles::has).map(id -> string(roles, id))
                            .filter(value -> !value.isBlank()).findFirst().orElse("core");
                    JsonObject mergedRoles = new JsonObject();
                    mergedRoles.addProperty(mergedId, role);
                    item.add("feature_roles", mergedRoles);
                }
            }
        }
    }

    private void remapLaneReferences(JsonObject flow, String laneId) {
        if (flow.has("lane_id")) flow.addProperty("lane_id", laneId);
        for (JsonElement child : array(flow, "children")) {
            if (child.isJsonObject()) remapLaneReferences(child.getAsJsonObject(), laneId);
        }
    }

    private void verify(JsonObject report, Intent intent) throws ModelClientException {
        if (intent.mergeFlows() && array(object(report, "flow_map"), "children").size() != 1) {
            throw new ModelClientException("合并流程图指令未应用");
        }
        if (intent.mergeDomains() && array(report, "business_domains").size() != 1) {
            throw new ModelClientException("合并业务域指令未应用");
        }
    }

    private static final Set<String> MERGED_ARRAY_FIELDS = Set.of(
            "preconditions", "data_reads", "data_writes", "failure_paths", "business_rules",
            "consumer_targets", "source_node_ids", "commit_ids", "contract_in_ids", "contract_out_ids"
    );

    private static void appendObjects(JsonArray target, JsonArray source) {
        for (JsonElement element : source) target.add(element.deepCopy());
    }

    private static void appendUnique(JsonArray target, JsonArray source) {
        Set<String> seen = new LinkedHashSet<>();
        target.forEach(element -> seen.add(element.toString()));
        for (JsonElement element : source) if (seen.add(element.toString())) target.add(element.deepCopy());
    }

    private static boolean containsAny(JsonArray values, Set<String> expected) {
        for (JsonElement value : values) if (value.isJsonPrimitive() && expected.contains(value.getAsString())) return true;
        return false;
    }

    private static JsonObject object(JsonObject source, String name) throws ModelClientException {
        if (source == null || !source.has(name) || !source.get(name).isJsonObject()) {
            throw new ModelClientException("报告缺少结构字段：" + name);
        }
        return source.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject source, String name) {
        return source != null && source.has(name) && source.get(name).isJsonArray()
                ? source.getAsJsonArray(name) : new JsonArray();
    }

    private static JsonArray arrayOf(JsonObject value) {
        JsonArray result = new JsonArray();
        result.add(value);
        return result;
    }

    private static JsonArray strings(String value) {
        JsonArray result = new JsonArray();
        result.add(value);
        return result;
    }

    private static String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    enum Intent {
        NONE(false, false), MERGE_FLOWS(true, false), MERGE_DOMAINS(false, true),
        MERGE_FLOWS_AND_DOMAINS(true, true);

        private final boolean mergeFlows;
        private final boolean mergeDomains;

        Intent(boolean mergeFlows, boolean mergeDomains) {
            this.mergeFlows = mergeFlows;
            this.mergeDomains = mergeDomains;
        }

        boolean mergeFlows() {
            return mergeFlows;
        }

        boolean mergeDomains() {
            return mergeDomains;
        }
    }
}
