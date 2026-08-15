package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DomainReportPatchAssembler {
    private static final String SCHEMA = "business-domain-refinement-patch/v1";
    private static final Set<String> OVERVIEW_STRING_FIELDS = Set.of("purpose", "primary_actor");
    private static final Set<String> OVERVIEW_ARRAY_FIELDS = Set.of(
            "plain_story", "actors", "domain_relationships", "terms", "reading_order"
    );
    private static final Set<String> BUSINESS_OBJECT_STRING_FIELDS = Set.of(
            "name", "plain_meaning", "storage_kind", "lifecycle", "file", "symbol", "evidence", "confidence"
    );
    private static final Set<String> BUSINESS_OBJECT_ARRAY_FIELDS = Set.of("field_groups", "supporting_sources");
    private static final Set<String> DOMAIN_STRING_FIELDS = Set.of("name", "purpose", "why_here");
    private static final Set<String> DOMAIN_ARRAY_FIELDS = Set.of(
            "actors", "owns", "receives", "produces", "not_responsible", "depends_on"
    );
    private static final Set<String> FLOW_STRING_FIELDS = Set.of(
            "title", "summary", "flow_type", "execution_scope", "actor", "trigger", "routing_condition",
            "outcome", "end_title", "data_subject", "primary_origin_id"
    );
    private static final Set<String> FLOW_ARRAY_FIELDS = Set.of(
            "preconditions", "data_reads", "data_writes", "data_origins", "data_flow", "consumer_targets",
            "failure_paths", "business_rules"
    );
    private static final Set<String> SOURCE_BACKED_FLOW_ARRAYS = Set.of(
            "data_origins", "data_flow", "consumer_targets"
    );
    private static final Set<String> STEP_STRING_FIELDS = Set.of("title", "summary", "kind", "execution");
    private static final Set<String> STEP_ARRAY_FIELDS = Set.of(
            "business_rules", "branches", "state_effects"
    );

    ApplyResult apply(String raw, JsonObject currentReport, EvidencePack evidence) throws ModelClientException {
        if (currentReport == null) throw new ModelClientException("当前业务报告为空，无法应用增量补丁");
        JsonObject patch = parse(raw);
        if (!SCHEMA.equals(string(patch, "schema"))) {
            throw new ModelClientException("增量业务报告补丁 schema 无效");
        }
        String summary = string(patch, "revision_summary");
        JsonObject original = currentReport.deepCopy();
        if (booleanValue(patch, "requires_structural_rebuild")) {
            return new ApplyResult(original, true, summary);
        }

        Set<String> manifest = normalizedManifest(evidence);
        ReportStructure originalStructure = structure(original);
        JsonElement originalUnknowns = original.has("unknowns")
                ? original.get("unknowns").deepCopy() : new JsonArray();
        Map<String, JsonObject> originalNodes = objectsById(array(original, "nodes"), "nodes");
        Map<String, JsonObject> originalEdges = objectsById(array(original, "edges"), "edges");

        JsonObject report = original.deepCopy();
        ReportStructure structure = structure(report);
        applyReportSummary(report, patch);
        applyOverview(report, objectOrNull(patch, "overview_update"), manifest);
        applyDomainUpdates(report, array(patch, "domain_updates"));
        applyFlowUpdates(array(patch, "flow_updates"), structure, manifest);
        JsonArray stepUpdates = array(patch, "step_updates");
        applyStepUpdates(stepUpdates, structure);
        applyAdditionalSources(report, additionalSources(patch, stepUpdates), structure, manifest);

        guard(
                report,
                originalStructure,
                originalUnknowns,
                originalNodes,
                originalEdges,
                manifest
        );
        return new ApplyResult(report, false, summary);
    }

    private void applyReportSummary(JsonObject report, JsonObject patch) throws ModelClientException {
        if (!patch.has("report_summary")) return;
        requireString(patch, "report_summary", "patch");
        String summary = string(patch, "report_summary");
        report.addProperty("summary", summary);
        JsonObject readerGuide = objectOrNull(report, "reader_guide");
        if (readerGuide == null) {
            readerGuide = new JsonObject();
            report.add("reader_guide", readerGuide);
        }
        readerGuide.addProperty("subtitle", summary);
    }

    private void applyOverview(JsonObject report, JsonObject update, Set<String> manifest)
            throws ModelClientException {
        if (update == null) return;
        JsonObject overview = objectOrNull(report, "business_overview");
        if (overview == null) throw new ModelClientException("当前报告缺少 business_overview");
        copyStrings(update, overview, OVERVIEW_STRING_FIELDS, "business_overview");
        copyArrays(update, overview, OVERVIEW_ARRAY_FIELDS, "business_overview");

        JsonArray objects = array(overview, "business_objects");
        for (JsonElement element : array(update, "business_object_updates")) {
            JsonObject objectUpdate = requireObject(element, "business_object_updates");
            String id = requiredString(objectUpdate, "id", "business_object_updates");
            JsonObject existing = findById(objects, id);
            if (existing == null) throw new ModelClientException("业务对象不存在，不能增量新增：" + id);
            copyStrings(objectUpdate, existing, BUSINESS_OBJECT_STRING_FIELDS, "business_object " + id);
            copyInteger(objectUpdate, existing, "line", "business_object " + id);
            copyInteger(objectUpdate, existing, "end_line", "business_object " + id);
            copyArrays(objectUpdate, existing, BUSINESS_OBJECT_ARRAY_FIELDS, "business_object " + id);
            if (containsAny(objectUpdate, Set.of("file", "symbol", "line", "end_line"))) {
                validateSource(existing, manifest, "业务对象 " + id);
                normalizeSourcePath(existing);
            }
            if (objectUpdate.has("supporting_sources")) {
                validateSources(array(existing, "supporting_sources"), manifest,
                        "业务对象 " + id + " supporting_sources");
            }
        }
    }

    private void applyDomainUpdates(JsonObject report, JsonArray updates) throws ModelClientException {
        JsonArray domains = array(report, "business_domains");
        Set<String> existingNodeIds = objectsById(array(report, "nodes"), "nodes").keySet();
        for (JsonElement element : updates) {
            JsonObject update = requireObject(element, "domain_updates");
            String id = requiredString(update, "id", "domain_updates");
            JsonObject domain = findById(domains, id);
            if (domain == null) throw new ModelClientException("业务域不存在，不能增量新增：" + id);
            copyStrings(update, domain, DOMAIN_STRING_FIELDS, "business_domain " + id);
            copyArrays(update, domain, DOMAIN_ARRAY_FIELDS, "business_domain " + id);
            if (update.has("source_node_ids")) {
                if (!update.get("source_node_ids").isJsonArray()) {
                    throw new ModelClientException("business_domain " + id + " 的 source_node_ids 必须是数组");
                }
                for (JsonElement sourceId : update.getAsJsonArray("source_node_ids")) {
                    if (!sourceId.isJsonPrimitive() || !existingNodeIds.contains(sourceId.getAsString())) {
                        throw new ModelClientException("business_domain " + id + " 引用了不存在的源码节点");
                    }
                }
                domain.add("source_node_ids", update.getAsJsonArray("source_node_ids").deepCopy());
            }
            synchronizeLane(report, id, update);
        }
    }

    private void synchronizeLane(JsonObject report, String domainId, JsonObject update) throws ModelClientException {
        JsonObject design = objectOrNull(report, "architecture_design");
        if (design == null) return;
        JsonObject lane = null;
        for (JsonElement element : array(design, "lanes")) {
            if (!element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            if (domainId.equals(string(candidate, "code_label"))) {
                lane = candidate;
                break;
            }
        }
        if (lane == null) return;
        copyString(update, lane, "name", "business_domain " + domainId);
        if (update.has("purpose")) {
            requireString(update, "purpose", "business_domain " + domainId);
            lane.addProperty("represents", string(update, "purpose"));
        }
        copyString(update, lane, "why_here", "business_domain " + domainId);
        copyArrayAs(update, lane, "owns", "responsibilities", "business_domain " + domainId);
        for (String field : List.of("receives", "produces", "not_responsible")) {
            copyArrayAs(update, lane, field, field, "business_domain " + domainId);
        }
        copyArrayAs(update, lane, "source_node_ids", "source_node_ids", "business_domain " + domainId);
    }

    private void applyFlowUpdates(JsonArray updates, ReportStructure structure, Set<String> manifest)
            throws ModelClientException {
        for (JsonElement element : updates) {
            JsonObject update = requireObject(element, "flow_updates");
            String id = identifier(update, "flow_id", "id", "flow_updates");
            JsonObject flow = structure.flows().get(id);
            if (flow == null) throw new ModelClientException("业务流程不存在，不能增量新增：" + id);
            copyStrings(update, flow, FLOW_STRING_FIELDS, "flow " + id);
            copyArrays(update, flow, FLOW_ARRAY_FIELDS, "flow " + id);
            if (update.has("entry_source")) {
                if (!update.get("entry_source").isJsonObject()) {
                    throw new ModelClientException("flow " + id + " 的 entry_source 必须是对象");
                }
                JsonObject entry = update.getAsJsonObject("entry_source").deepCopy();
                validateSource(entry, manifest, "flow " + id + " entry_source");
                normalizeSourcePath(entry);
                flow.add("entry_source", entry);
            }
            for (String field : SOURCE_BACKED_FLOW_ARRAYS) {
                if (update.has(field)) {
                    validateSources(array(flow, field), manifest, "flow " + id + " " + field);
                }
            }
        }
    }

    private void applyStepUpdates(JsonArray updates, ReportStructure structure) throws ModelClientException {
        for (JsonElement element : updates) {
            JsonObject update = requireObject(element, "step_updates");
            String id = identifier(update, "step_id", "id", "step_updates");
            JsonObject step = structure.steps().get(id);
            if (step == null) throw new ModelClientException("业务步骤不存在，不能增量新增：" + id);
            copyStrings(update, step, STEP_STRING_FIELDS, "step " + id);
            copyArrays(update, step, STEP_ARRAY_FIELDS, "step " + id);
        }
    }

    private void applyAdditionalSources(
            JsonObject report,
            JsonArray additions,
            ReportStructure structure,
            Set<String> manifest
    ) throws ModelClientException {
        JsonArray nodes = array(report, "nodes");
        Set<String> nodeIds = new LinkedHashSet<>(objectsById(nodes, "nodes").keySet());
        int sequence = nodes.size() + 1;
        for (JsonElement element : additions) {
            JsonObject source = requireObject(element, "additional_sources").deepCopy();
            String stepId = requiredString(source, "step_id", "additional_sources");
            JsonObject step = structure.steps().get(stepId);
            if (step == null) throw new ModelClientException("补充源码绑定了不存在的业务步骤：" + stepId);
            validateSource(source, manifest, "additional source for " + stepId);
            normalizeSourcePath(source);

            String nodeId;
            do {
                nodeId = "domain-refinement-node-" + sequence++;
            } while (nodeIds.contains(nodeId));
            nodeIds.add(nodeId);
            String flowId = structure.flowByStep().get(stepId);
            JsonObject node = sourceNode(nodeId, source, flowId, primaryNode(step, nodes));
            nodes.add(node);
            appendUnique(step, "source_node_ids", nodeId);
        }
        report.add("nodes", nodes);
    }

    private JsonArray additionalSources(JsonObject patch, JsonArray stepUpdates) throws ModelClientException {
        JsonArray additions = array(patch, "additional_sources").deepCopy();
        for (JsonElement element : stepUpdates) {
            JsonObject stepUpdate = requireObject(element, "step_updates");
            if (!stepUpdate.has("additional_sources")) continue;
            if (!stepUpdate.get("additional_sources").isJsonArray()) {
                throw new ModelClientException("step_updates.additional_sources 必须是数组");
            }
            String stepId = identifier(stepUpdate, "step_id", "id", "step_updates");
            for (JsonElement sourceElement : stepUpdate.getAsJsonArray("additional_sources")) {
                JsonObject source = requireObject(sourceElement, "step_updates.additional_sources").deepCopy();
                source.addProperty("step_id", stepId);
                additions.add(source);
            }
        }
        return additions;
    }

    private JsonObject sourceNode(
            String id,
            JsonObject source,
            String flowId,
            JsonObject primaryNode
    ) {
        String service = primaryNode == null ? "business-refinement" : string(primaryNode, "service");
        String module = primaryNode == null ? service : string(primaryNode, "module");
        int line = positiveInteger(source, "line", 1);
        int endLine = Math.max(line, positiveInteger(source, "end_line", line));
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("kind", normalizeNodeKind(fallback(string(source, "node_kind"), string(source, "kind"))));
        node.addProperty("label", string(source, "symbol"));
        node.addProperty("service", service.isBlank() ? "business-refinement" : service);
        node.addProperty("module", module.isBlank() ? "business-refinement" : module);
        node.addProperty("file", string(source, "file"));
        node.addProperty("line", line);
        node.addProperty("end_line", endLine);
        node.addProperty("responsibility", fallback(string(source, "meaning"), string(source, "summary")));
        node.add("inputs", new JsonArray());
        node.add("outputs", new JsonArray());
        node.add("state_effects", new JsonArray());
        JsonArray featureIds = new JsonArray();
        if (flowId != null && !flowId.isBlank()) featureIds.add(flowId);
        node.add("feature_ids", featureIds);
        JsonObject roles = new JsonObject();
        if (flowId != null && !flowId.isBlank()) roles.addProperty(flowId, "core");
        node.add("feature_roles", roles);
        node.addProperty("change", "unchanged");
        node.add("changed_in_commits", new JsonArray());
        node.addProperty("source_kind", "repository");
        node.addProperty("evidence", normalizeEvidence(string(source, "evidence")));
        node.addProperty("confidence", normalizeConfidence(string(source, "confidence")));
        return node;
    }

    private JsonObject primaryNode(JsonObject step, JsonArray nodes) {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement element : nodes) {
            if (element.isJsonObject()) byId.put(string(element.getAsJsonObject(), "id"), element.getAsJsonObject());
        }
        for (JsonElement element : array(step, "source_node_ids")) {
            if (element.isJsonPrimitive() && byId.containsKey(element.getAsString())) return byId.get(element.getAsString());
        }
        return null;
    }

    private void guard(
            JsonObject report,
            ReportStructure originalStructure,
            JsonElement originalUnknowns,
            Map<String, JsonObject> originalNodes,
            Map<String, JsonObject> originalEdges,
            Set<String> manifest
    ) throws ModelClientException {
        ReportStructure updated = structure(report);
        if (!originalStructure.topology().equals(updated.topology())) {
            throw new ModelClientException("增量补丁不得增删、移动或重排业务流程与步骤");
        }
        JsonElement currentUnknowns = report.has("unknowns") ? report.get("unknowns") : new JsonArray();
        if (!originalUnknowns.equals(currentUnknowns)) {
            throw new ModelClientException("增量补丁不得改变待确认问题");
        }

        Map<String, JsonObject> nodes = objectsById(array(report, "nodes"), "nodes");
        Map<String, JsonObject> edges = objectsById(array(report, "edges"), "edges");
        ensureOriginalItemsPreserved(originalNodes, nodes, "node");
        ensureOriginalItemsPreserved(originalEdges, edges, "edge");
        for (Map.Entry<String, JsonObject> entry : updated.steps().entrySet()) {
            String stepId = entry.getKey();
            JsonArray sourceIds = array(entry.getValue(), "source_node_ids");
            if (sourceIds.isEmpty()) throw new ModelClientException("业务步骤没有源码节点：" + stepId);
            boolean hasTrackedSource = false;
            for (JsonElement sourceId : sourceIds) {
                if (!sourceId.isJsonPrimitive()) {
                    throw new ModelClientException("业务步骤包含无效源码节点引用：" + stepId);
                }
                JsonObject node = nodes.get(sourceId.getAsString());
                if (node == null) throw new ModelClientException("业务步骤引用了不存在的源码节点：" + stepId);
                if (manifest.contains(normalizedPath(string(node, "file")))) hasTrackedSource = true;
            }
            if (!hasTrackedSource) {
                throw new ModelClientException("业务步骤没有目标快照内的源码证据：" + stepId);
            }
        }
    }

    private void ensureOriginalItemsPreserved(
            Map<String, JsonObject> originals,
            Map<String, JsonObject> current,
            String kind
    ) throws ModelClientException {
        for (Map.Entry<String, JsonObject> entry : originals.entrySet()) {
            JsonObject retained = current.get(entry.getKey());
            if (retained == null || !entry.getValue().equals(retained)) {
                throw new ModelClientException("增量补丁不得删除或修改原有 " + kind + "：" + entry.getKey());
            }
        }
    }

    private ReportStructure structure(JsonObject report) throws ModelClientException {
        JsonObject root = objectOrNull(report, "flow_map");
        if (root == null) throw new ModelClientException("当前报告缺少 flow_map");
        Map<String, JsonObject> flows = new LinkedHashMap<>();
        Map<String, JsonObject> steps = new LinkedHashMap<>();
        Map<String, String> flowByStep = new LinkedHashMap<>();
        List<String> flowOrder = new ArrayList<>();
        List<StepPosition> stepOrder = new ArrayList<>();
        for (JsonElement element : array(root, "children")) {
            JsonObject flow = requireObject(element, "flow_map.children");
            String flowId = requiredString(flow, "id", "flow_map.children");
            if (flows.putIfAbsent(flowId, flow) != null) {
                throw new ModelClientException("业务流程 id 重复：" + flowId);
            }
            flowOrder.add(flowId);
            collectSteps(flow, flowId, flowId, steps, flowByStep, stepOrder);
        }
        return new ReportStructure(
                flows,
                steps,
                flowByStep,
                new Topology(List.copyOf(flowOrder), List.copyOf(stepOrder))
        );
    }

    private void collectSteps(
            JsonObject parent,
            String flowId,
            String parentId,
            Map<String, JsonObject> steps,
            Map<String, String> flowByStep,
            List<StepPosition> order
    ) throws ModelClientException {
        for (JsonElement element : array(parent, "children")) {
            JsonObject step = requireObject(element, "flow children");
            String stepId = requiredString(step, "id", "flow children");
            if (steps.putIfAbsent(stepId, step) != null) {
                throw new ModelClientException("业务步骤 id 重复：" + stepId);
            }
            flowByStep.put(stepId, flowId);
            order.add(new StepPosition(flowId, parentId, stepId));
            collectSteps(step, flowId, stepId, steps, flowByStep, order);
        }
    }

    private Map<String, JsonObject> objectsById(JsonArray values, String context) throws ModelClientException {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement element : values) {
            JsonObject object = requireObject(element, context);
            String id = requiredString(object, "id", context);
            if (result.putIfAbsent(id, object.deepCopy()) != null) {
                throw new ModelClientException(context + " 包含重复 id：" + id);
            }
        }
        return result;
    }

    private void validateSources(JsonArray values, Set<String> manifest, String context)
            throws ModelClientException {
        for (JsonElement element : values) {
            JsonObject source = requireObject(element, context);
            validateSource(source, manifest, context);
            normalizeSourcePath(source);
        }
    }

    private void validateSource(JsonObject source, Set<String> manifest, String context)
            throws ModelClientException {
        String path = normalizedPath(string(source, "file"));
        String symbol = string(source, "symbol");
        if (!manifest.contains(path)) {
            throw new ModelClientException(context + " 引用了目标快照中不存在的源码：" + path);
        }
        if (symbol.isBlank()) throw new ModelClientException(context + " 缺少可跳转的 symbol");
    }

    private Set<String> normalizedManifest(EvidencePack evidence) {
        LinkedHashSet<String> manifest = new LinkedHashSet<>();
        if (evidence != null && evidence.targetManifest() != null) {
            evidence.targetManifest().stream().map(this::normalizedPath).forEach(manifest::add);
        }
        return Set.copyOf(manifest);
    }

    private String normalizedPath(String path) {
        return path == null ? "" : path.replace('\\', '/').strip();
    }

    private void normalizeSourcePath(JsonObject source) {
        source.addProperty("file", normalizedPath(string(source, "file")));
    }

    private void copyStrings(JsonObject source, JsonObject target, Set<String> fields, String context)
            throws ModelClientException {
        for (String field : fields) copyString(source, target, field, context);
    }

    private void copyString(JsonObject source, JsonObject target, String field, String context)
            throws ModelClientException {
        if (!source.has(field)) return;
        requireString(source, field, context);
        target.addProperty(field, string(source, field));
    }

    private void copyArrays(JsonObject source, JsonObject target, Set<String> fields, String context)
            throws ModelClientException {
        for (String field : fields) copyArrayAs(source, target, field, field, context);
    }

    private void copyArrayAs(JsonObject source, JsonObject target, String sourceField, String targetField, String context)
            throws ModelClientException {
        if (!source.has(sourceField)) return;
        if (!source.get(sourceField).isJsonArray()) {
            throw new ModelClientException(context + " 的 " + sourceField + " 必须是数组");
        }
        target.add(targetField, source.getAsJsonArray(sourceField).deepCopy());
    }

    private void copyInteger(JsonObject source, JsonObject target, String field, String context)
            throws ModelClientException {
        if (!source.has(field)) return;
        try {
            target.addProperty(field, source.get(field).getAsInt());
        } catch (RuntimeException exception) {
            throw new ModelClientException(context + " 的 " + field + " 必须是整数", exception);
        }
    }

    private void requireString(JsonObject object, String field, String context) throws ModelClientException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()) {
            throw new ModelClientException(context + " 的 " + field + " 必须是字符串");
        }
    }

    private String requiredString(JsonObject object, String field, String context) throws ModelClientException {
        requireString(object, field, context);
        String value = string(object, field);
        if (value.isBlank()) throw new ModelClientException(context + " 的 " + field + " 不能为空");
        return value;
    }

    private String identifier(JsonObject object, String preferred, String fallback, String context)
            throws ModelClientException {
        if (object.has(preferred)) return requiredString(object, preferred, context);
        return requiredString(object, fallback, context);
    }

    private boolean containsAny(JsonObject object, Set<String> fields) {
        return fields.stream().anyMatch(object::has);
    }

    private JsonObject requireObject(JsonElement element, String context) throws ModelClientException {
        if (element == null || !element.isJsonObject()) {
            throw new ModelClientException(context + " 必须只包含对象");
        }
        return element.getAsJsonObject();
    }

    private JsonObject findById(JsonArray values, String id) {
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

    private boolean booleanValue(JsonObject object, String field) throws ModelClientException {
        if (!object.has(field)) return false;
        try {
            return object.get(field).getAsBoolean();
        } catch (RuntimeException exception) {
            throw new ModelClientException(field + " 必须是布尔值", exception);
        }
    }

    private int positiveInteger(JsonObject object, String field, int fallback) {
        try {
            return object.has(field) ? Math.max(1, object.get(field).getAsInt()) : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String normalizeNodeKind(String value) {
        return Set.of("function", "method", "module", "service", "interface", "struct", "table")
                .contains(value) ? value : "method";
    }

    private String normalizeEvidence(String value) {
        return Set.of("direct_source", "source_backed_walkthrough").contains(value)
                ? value : "direct_source";
    }

    private String normalizeConfidence(String value) {
        return Set.of("high", "medium", "low").contains(value) ? value : "high";
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private JsonArray array(JsonObject object, String field) {
        return object != null && object.has(field) && object.get(field).isJsonArray()
                ? object.getAsJsonArray(field) : new JsonArray();
    }

    private JsonObject objectOrNull(JsonObject object, String field) {
        return object != null && object.has(field) && object.get(field).isJsonObject()
                ? object.getAsJsonObject(field) : null;
    }

    private String string(JsonObject object, String field) {
        return object != null && object.has(field) && object.get(field).isJsonPrimitive()
                ? object.get(field).getAsString() : "";
    }

    private JsonObject parse(String raw) throws ModelClientException {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) value = value.substring(firstLine + 1, lastFence).strip();
        }
        try {
            return JsonParser.parseString(value).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new ModelClientException("模型没有返回合法的增量业务报告补丁：" + exception.getMessage(), exception);
        }
    }

    record ApplyResult(JsonObject report, boolean requiresStructuralRebuild, String summary) {
    }

    private record ReportStructure(
            Map<String, JsonObject> flows,
            Map<String, JsonObject> steps,
            Map<String, String> flowByStep,
            Topology topology
    ) {
    }

    private record Topology(List<String> flowOrder, List<StepPosition> stepOrder) {
    }

    private record StepPosition(String flowId, String parentId, String stepId) {
    }
}
