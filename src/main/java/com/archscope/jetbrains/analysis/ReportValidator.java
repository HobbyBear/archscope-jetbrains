package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.SensitiveTextSanitizer;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReportValidator {
    public JsonObject validate(String rawResponse, EvidencePack evidence, Path workspaceRoot) throws ReportValidationException {
        return validateWithRepository(rawResponse, evidence, workspaceRoot.resolve("repository"));
    }

    public JsonObject validateRepository(String rawResponse, EvidencePack evidence, Path repositoryRoot) throws ReportValidationException {
        return validateWithRepository(rawResponse, evidence, repositoryRoot);
    }

    private JsonObject validateWithRepository(
            String rawResponse,
            EvidencePack evidence,
            Path sourceRepositoryRoot
    ) throws ReportValidationException {
        List<String> errors = new ArrayList<>();
        JsonObject report;
        try {
            report = ModelJsonParser.parseObject(rawResponse);
        } catch (RuntimeException exception) {
            throw new ReportValidationException(List.of("模型没有返回合法的单个 JSON 对象：" + exception.getMessage()));
        }

        normalizePresentationMetadata(report);
        normalizeDeterministicModelVariants(report, evidence);
        normalizeChangeMetadata(report, evidence);
        requireExact(report, "schema", "code-architecture-report/v1", errors);
        requireExact(
                report,
                "source_format",
                evidence.commits().isEmpty() ? "business-domain-walkthrough/v1" : "code-change-walkthrough/v2",
                errors
        );
        requireObject(report, "analysis_focus", errors);
        requireObject(report, "reader_guide", errors);
        JsonObject design = requireObject(report, "architecture_design", errors);
        JsonObject flow = requireObject(report, "flow_map", errors);
        requireObject(report, "scope", errors);
        JsonObject comparison = requireObject(report, "comparison", errors);
        for (String name : List.of("features", "services", "nodes", "edges", "scenarios", "data_structures", "tables", "evidence", "unknowns", "commit_evolution", "review_findings")) {
            requireArray(report, name, errors);
        }

        validateComparison(comparison, evidence, errors);
        JsonArray nodes = array(report, "nodes");
        JsonArray edges = array(report, "edges");
        Set<String> nodeIds = uniqueIds(nodes, "nodes", errors);
        Set<String> laneIds = design == null ? Set.of() : uniqueIds(array(design, "lanes"), "architecture_design.lanes", errors);
        Set<String> contractIds = design == null ? Set.of() : uniqueIds(array(design, "contracts"), "architecture_design.contracts", errors);
        Set<String> flowIds = new HashSet<>();
        Map<String, JsonObject> flowNodes = new HashMap<>();
        if (flow != null) {
            collectFlow(flow, flowIds, flowNodes, errors);
        }

        Path repositoryRoot = sourceRepositoryRoot.toAbsolutePath().normalize();
        validateSourceNodes(nodes, evidence, repositoryRoot, errors);
        validateEdges(edges, nodeIds, evidence, repositoryRoot, errors);
        if (design != null) {
            validateDesign(design, nodeIds, laneIds, errors);
        }
        validateFlow(flowNodes, flowIds, nodeIds, laneIds, contractIds, errors);
        if (evidence.commits().isEmpty()) {
            validateBusinessDomainReport(report, flow, flowIds, nodeIds, evidence, repositoryRoot, errors);
        }
        validateCommitEvolution(array(report, "commit_evolution"), evidence, nodeIds, errors);
        validateReviewFindings(array(report, "review_findings"), evidence, nodeIds, errors);

        if (!errors.isEmpty()) {
            throw new ReportValidationException(errors.stream().limit(30).toList());
        }
        return report;
    }

    private void validateBusinessDomainReport(
            JsonObject report,
            JsonObject flowRoot,
            Set<String> flowIds,
            Set<String> nodeIds,
            EvidencePack evidence,
            Path repositoryRoot,
            List<String> errors
    ) {
        JsonObject overview = requireObject(report, "business_overview", errors);
        JsonArray domains = requireArray(report, "business_domains", errors);
        JsonArray unknowns = requireArray(report, "unknowns", errors);
        requireArray(report, "revision_history", errors);
        if (overview != null) {
            if (string(overview, "purpose") == null) errors.add("business_overview 缺少 purpose");
            JsonArray actors = requireArray(overview, "actors", errors);
            JsonArray terms = requireArray(overview, "terms", errors);
            JsonArray plainStory = requireArray(overview, "plain_story", errors);
            JsonArray businessObjects = requireArray(overview, "business_objects", errors);
            requireArray(overview, "domain_relationships", errors);
            validateBusinessObjects(businessObjects, evidence, repositoryRoot, errors);
        }

        Set<String> domainIds = domains == null ? Set.of() : uniqueIds(domains, "business_domains", errors);
        if (domains != null && domains.isEmpty()) errors.add("业务报告至少需要一个业务域");
        if (domains != null) {
            for (JsonElement item : domains) {
                if (!item.isJsonObject()) continue;
                JsonObject domain = item.getAsJsonObject();
                String id = string(domain, "id");
                if (string(domain, "purpose") == null) errors.add("business domain " + id + " 缺少 purpose");
                for (String field : List.of(
                        "actors", "owns", "receives", "produces", "not_responsible",
                        "depends_on", "flow_ids", "source_node_ids"
                )) {
                    if (array(domain, field) == null) errors.add("business domain " + id + " 缺少数组 " + field);
                }
                validateReferences(array(domain, "depends_on"), domainIds, "business domain " + id + " depends_on", errors);
                validateReferences(array(domain, "flow_ids"), flowIds, "business domain " + id + " flow_ids", errors);
                validateReferences(array(domain, "source_node_ids"), nodeIds, "business domain " + id + " source_node_ids", errors);
                JsonArray sourceNodeIds = array(domain, "source_node_ids");
                if (sourceNodeIds != null && sourceNodeIds.isEmpty()) {
                    errors.add("business domain " + id + " 没有绑定证明职责的源码步骤");
                }
            }
        }
        validateAtomicUnknowns(unknowns, flowIds, errors);
        validateNoHiddenUncertainty(report, unknowns, errors);

        JsonArray flows = flowRoot == null ? null : array(flowRoot, "children");
        if (flows == null || flows.isEmpty()) {
            errors.add("业务报告至少需要一条完整业务流程");
            return;
        }
        for (JsonElement item : flows) {
            if (!item.isJsonObject()) continue;
            JsonObject flow = item.getAsJsonObject();
            String id = string(flow, "id");
            if (!"business".equals(string(flow, "flow_scope"))) {
                errors.add("业务流程 " + id + " 缺少 flow_scope=business");
            }
            if (!oneOf(string(flow, "flow_type"), "request", "job", "event", "command")) {
                errors.add("业务流程 " + id + " 的 flow_type 非法");
            }
            if (!"single_trigger".equals(string(flow, "execution_scope"))) {
                errors.add("业务流程 " + id + " 必须声明 execution_scope=single_trigger");
            }
            for (String field : List.of(
                    "preconditions", "data_reads", "data_writes", "failure_paths", "data_origins", "data_flow"
            )) {
                if (array(flow, field) == null) errors.add("业务流程 " + id + " 缺少数组 " + field);
            }
            JsonArray origins = array(flow, "data_origins");
            JsonArray dataFlow = array(flow, "data_flow");
            JsonArray consumers = requireArray(flow, "consumer_targets", errors);
            if (origins != null && origins.isEmpty()) errors.add("业务流程 " + id + " 没有说明核心数据来源");
            JsonArray steps = array(flow, "children");
            if (steps == null || steps.isEmpty()) {
                errors.add("业务流程 " + id + " 没有源码支持的职责步骤");
                continue;
            }
            validateConnectedBusinessFlow(flow, origins, dataFlow, consumers, steps, evidence, repositoryRoot, errors);
        }
    }

    private void validateAtomicUnknowns(JsonArray unknowns, Set<String> flowIds, List<String> errors) {
        if (unknowns == null) return;
        for (JsonElement element : unknowns) {
            if (!element.isJsonObject()) {
                errors.add("业务报告的 unknowns 必须是结构化的单一问题");
                continue;
            }
            JsonObject unknown = element.getAsJsonObject();
            String question = string(unknown, "question");
            if (question == null || question.isBlank()) errors.add("待确认问题不能为空");
            if (!oneOf(string(unknown, "kind"), "entry", "origin", "rule", "state", "event", "outcome")) {
                errors.add("待确认问题的 kind 非法");
            }
            String flowId = string(unknown, "flow_id");
            if (flowId != null && !flowId.isBlank()) {
                validateReference(flowId, flowIds, "待确认问题 flow_id", errors);
            }
            JsonArray symbols = requireArray(unknown, "symbols", errors);
            if (string(unknown, "why_material") == null || string(unknown, "why_material").isBlank()) {
                errors.add("待确认问题缺少 why_material");
            }
        }
    }

    private void validateBusinessObjects(
            JsonArray businessObjects,
            EvidencePack evidence,
            Path repositoryRoot,
            List<String> errors
    ) {
        if (businessObjects == null) return;
        Set<String> ids = uniqueIds(businessObjects, "business_overview.business_objects", errors);
        for (JsonElement element : businessObjects) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String id = string(object, "id");
            if (!oneOf(string(object, "storage_kind"), "payload", "struct", "table", "event", "config", "unknown")) {
                errors.add("业务对象 " + id + " 的 storage_kind 非法");
            }
            if ("unknown".equals(string(object, "storage_kind"))
                    && !"inferred".equals(string(object, "evidence"))) {
                errors.add("已有源码证据的业务对象 " + id + " 不能把 storage_kind 留为 unknown");
            }
            JsonArray groups = requireArray(object, "field_groups", errors);
            if (groups != null) {
                for (JsonElement groupElement : groups) {
                    if (!groupElement.isJsonObject()) {
                        errors.add("业务对象 " + id + " 的 field_groups 包含非对象条目");
                        continue;
                    }
                    JsonObject group = groupElement.getAsJsonObject();
                    if (!oneOf(string(group, "role"),
                            "business_metric", "identity", "control", "audit_time", "content", "other")) {
                        errors.add("业务对象 " + id + " 的字段分组 role 非法");
                    }
                    JsonArray fields = requireArray(group, "fields", errors);
                }
            }
            validateBusinessSource(object, "业务对象 " + id, evidence, repositoryRoot, true, errors);
            JsonArray supportingSources = array(object, "supporting_sources");
            if (supportingSources != null) {
                for (JsonElement supportingElement : supportingSources) {
                    if (!supportingElement.isJsonObject()) {
                        errors.add("业务对象 " + id + " 的 supporting_sources 包含非对象条目");
                        continue;
                    }
                    validateBusinessSource(supportingElement.getAsJsonObject(),
                            "业务对象 " + id + " 的辅助源码", evidence, repositoryRoot, false, errors);
                }
            }
        }
        if (ids.isEmpty() && !businessObjects.isEmpty()) errors.add("核心业务对象缺少稳定 id");
    }

    private void validateNoHiddenUncertainty(JsonObject report, JsonArray unknowns, List<String> errors) {
        if (unknowns == null || !unknowns.isEmpty()) return;
        List<String> markers = List.of("待确认", "无法确认", "不能确认", "尚未确认", "未完整展示", "未知");
        List<String> narrative = new ArrayList<>();
        addString(narrative, report, "summary");
        addString(narrative, object(report, "reader_guide"), "subtitle");
        JsonObject overview = object(report, "business_overview");
        addString(narrative, overview, "purpose");
        addStrings(narrative, array(overview, "plain_story"));
        JsonArray businessObjects = array(overview, "business_objects");
        if (businessObjects != null) {
            for (JsonElement element : businessObjects) {
                if (element.isJsonObject()) addString(narrative, element.getAsJsonObject(), "lifecycle");
            }
        }
        JsonObject flowRoot = object(report, "flow_map");
        collectNarrative(flowRoot, narrative);
        for (String text : narrative) {
            if (markers.stream().anyMatch(text::contains)) {
                errors.add("unknowns 已清零，但正文仍保留未确认表述：" + text);
                break;
            }
        }
    }

    private void collectNarrative(JsonObject flow, List<String> values) {
        if (flow == null || flow.isEmpty()) return;
        addString(values, flow, "summary");
        JsonObject entry = object(flow, "entry_source");
        addString(values, entry, "meaning");
        JsonArray children = array(flow, "children");
        if (children != null) {
            for (JsonElement child : children) {
                if (child.isJsonObject()) collectNarrative(child.getAsJsonObject(), values);
            }
        }
    }

    private void addString(List<String> values, JsonObject object, String field) {
        String value = string(object, field);
        if (value != null && !value.isBlank()) values.add(value);
    }

    private void addStrings(List<String> values, JsonArray array) {
        if (array == null) return;
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) values.add(element.getAsString());
        }
    }

    private void validateConnectedBusinessFlow(
            JsonObject flow,
            JsonArray origins,
            JsonArray dataFlow,
            JsonArray consumers,
            JsonArray steps,
            EvidencePack evidence,
            Path repositoryRoot,
            List<String> errors
    ) {
        String flowId = string(flow, "id");
        if (string(flow, "data_subject") == null || string(flow, "data_subject").isBlank()) {
            errors.add("业务流程 " + flowId + " 缺少唯一 data_subject");
        }
        Set<String> stepIds = uniqueIds(steps, "业务流程 " + flowId + " steps", errors);
        String firstStepId = steps.get(0).isJsonObject() ? string(steps.get(0).getAsJsonObject(), "id") : null;
        for (JsonElement stepElement : steps) {
            if (!stepElement.isJsonObject()) continue;
            JsonObject step = stepElement.getAsJsonObject();
            String stepId = string(step, "id");
            if (!oneOf(string(step, "execution"), "same_execution", "async_continuation")) {
                errors.add("业务步骤 " + stepId + " 没有证明属于当前触发的同一次执行或直接异步延续");
            }
            JsonArray branches = array(step, "branches");
            if (branches != null) {
                for (JsonElement branchElement : branches) {
                    if (!branchElement.isJsonObject()) {
                        errors.add("业务步骤 " + stepId + " 的 branches 必须是结构化对象");
                        continue;
                    }
                    JsonObject branch = branchElement.getAsJsonObject();
                    if (string(branch, "label") == null || string(branch, "meaning") == null) {
                        errors.add("业务步骤 " + stepId + " 的分支缺少 label 或 meaning");
                    }
                    if (!oneOf(string(branch, "outcome"),
                            "continue", "success", "failure", "cancel", "terminal")) {
                        errors.add("业务步骤 " + stepId + " 的分支 outcome 非法");
                    }
                }
            }
        }

        JsonObject entry = requireObject(flow, "entry_source", errors);
        if (entry != null) {
            if (!oneOf(string(entry, "entry_kind"),
                    "route", "job_registration", "event_consumer", "public_caller", "command", "external_boundary")) {
                errors.add("业务流程 " + flowId + " 的 entry_source.entry_kind 非法");
            }
            if (firstStepId == null || !firstStepId.equals(string(entry, "step_id"))) {
                errors.add("业务流程 " + flowId + " 的 entry_source 必须绑定第一个业务步骤");
            }
            validateBusinessSource(entry, "业务流程 " + flowId + " 的真实入口", evidence, repositoryRoot, true, errors);
        }

        Set<String> originIds = origins == null ? Set.of() : uniqueIds(origins, "业务流程 " + flowId + " data_origins", errors);
        int primaryCount = 0;
        if (origins != null) {
            for (JsonElement originElement : origins) {
                if (!originElement.isJsonObject()) continue;
                JsonObject origin = originElement.getAsJsonObject();
                String originId = string(origin, "id");
                String role = string(origin, "role");
                if (!oneOf(role, "primary", "control", "lookup", "configuration")) {
                    errors.add("数据来源 " + originId + " 的 role 非法");
                }
                if ("primary".equals(role)) primaryCount++;
                validateReference(string(origin, "joins_step_id"), stepIds,
                        "数据来源 " + originId + " joins_step_id", errors);
                if (!oneOf(string(origin, "upstream_producer_status"), "confirmed", "unknown")) {
                    errors.add("数据来源 " + originId + " 缺少 upstream_producer_status");
                }
                if (string(origin, "meaning") == null || string(origin, "meaning").isBlank()) {
                    errors.add("数据来源 " + originId + " 没有解释为何汇入");
                }
                validateBusinessSource(origin, "数据来源 " + originId, evidence, repositoryRoot,
                        "unknown".equals(string(origin, "source_kind")), errors);
            }
        }
        if (primaryCount != 1) errors.add("业务流程 " + flowId + " 必须且只能有一个 primary 数据来源");
        String primaryOriginId = string(flow, "primary_origin_id");
        if (!originIds.contains(primaryOriginId)) {
            errors.add("业务流程 " + flowId + " 的 primary_origin_id 不存在");
        } else if (origins != null) {
            JsonObject primary = null;
            for (JsonElement element : origins) {
                if (element.isJsonObject() && primaryOriginId.equals(string(element.getAsJsonObject(), "id"))) {
                    primary = element.getAsJsonObject();
                    break;
                }
            }
            if (primary != null && !"primary".equals(string(primary, "role"))) {
                errors.add("业务流程 " + flowId + " 的 primary_origin_id 没有指向 primary 来源");
            }
        }

        Map<String, Set<Integer>> ordersByLineage = new HashMap<>();
        Set<String> hopIds = dataFlow == null ? Set.of() : uniqueIds(dataFlow, "业务流程 " + flowId + " data_flow", errors);
        if (dataFlow != null) {
            for (JsonElement hopElement : dataFlow) {
                if (!hopElement.isJsonObject()) continue;
                JsonObject hop = hopElement.getAsJsonObject();
                String hopId = string(hop, "id");
                String lineageId = string(hop, "lineage_id");
                validateReference(lineageId, originIds, "数据环节 " + hopId + " lineage_id", errors);
                validateReference(string(hop, "step_id"), stepIds, "数据环节 " + hopId + " step_id", errors);
                if (!oneOf(string(hop, "timing"), "same_execution", "async_continuation")) {
                    errors.add("数据环节 " + hopId + " timing 非法，独立触发不能伪装成当前执行步骤");
                }
                if (!oneOf(string(hop, "phase"), "ingest", "validate", "transform", "persist", "deliver")) {
                    errors.add("数据环节 " + hopId + " phase 非法");
                }
                if (string(hop, "plain_action") == null || string(hop, "plain_action").isBlank()) {
                    errors.add("数据环节 " + hopId + " 缺少白话动作说明");
                }
                int order = integer(hop, "order", -1);
                if (order < 1 || !ordersByLineage.computeIfAbsent(lineageId, ignored -> new HashSet<>()).add(order)) {
                    errors.add("数据环节 " + hopId + " 的 order 无效或在同一血缘中重复");
                }
                validateBusinessSource(hop, "数据环节 " + hopId, evidence, repositoryRoot, false, errors);
            }
        }
        Set<Integer> primaryOrders = ordersByLineage.getOrDefault(primaryOriginId, Set.of());
        if (!primaryOriginId.isBlank() && !originIds.isEmpty() && primaryOrders.isEmpty()) {
            errors.add("业务流程 " + flowId + " 的 primary 数据来源没有绑定任何数据环节");
        }
        for (Map.Entry<String, Set<Integer>> entryOrder : ordersByLineage.entrySet()) {
            for (int order = 1; order <= entryOrder.getValue().size(); order++) {
                if (!entryOrder.getValue().contains(order)) {
                    errors.add("数据血缘 " + entryOrder.getKey() + " 的 order 必须从 1 连续编号");
                    break;
                }
            }
        }
        if (hopIds.isEmpty() && dataFlow != null && !dataFlow.isEmpty()) {
            errors.add("业务流程 " + flowId + " 的数据环节缺少稳定 id");
        }

        if (consumers != null) {
            for (JsonElement consumerElement : consumers) {
                if (!consumerElement.isJsonObject()) {
                    errors.add("业务流程 " + flowId + " 的 consumer_targets 包含非对象条目");
                    continue;
                }
                JsonObject consumer = consumerElement.getAsJsonObject();
                String name = string(consumer, "name");
                if (name == null || name.isBlank() || string(consumer, "meaning") == null) {
                    errors.add("业务流程 " + flowId + " 的独立消费者缺少 name 或 meaning");
                }
                validateReference(string(consumer, "after_step_id"), stepIds,
                        "独立消费者 " + name + " after_step_id", errors);
                validateBusinessSource(consumer, "独立消费者 " + name, evidence, repositoryRoot, false, errors);
            }
        }
    }

    private void validateBusinessSource(
            JsonObject item,
            String label,
            EvidencePack evidence,
            Path repositoryRoot,
            boolean allowInferredWithoutFile,
            List<String> errors
    ) {
        String file = string(item, "file");
        String evidenceKind = string(item, "evidence");
        String symbol = string(item, "symbol");
        if (file == null || file.isBlank()) {
            if (!allowInferredWithoutFile || !"inferred".equals(evidenceKind)) {
                errors.add(label + " 缺少可跳转的源码证据");
            }
            return;
        }
        if (!evidence.targetManifest().contains(file)) {
            errors.add(label + " 引用了目标快照中不存在的文件：" + file);
            return;
        }
        if (SensitiveTextSanitizer.isSensitivePath(file)) {
            errors.add(label + " 引用了禁止分析的敏感路径：" + file);
        }
        if (symbol == null || symbol.isBlank()) errors.add(label + " 缺少源码 symbol");
        if (!Set.of("direct_source", "source_backed_walkthrough", "inferred").contains(evidenceKind)) {
            errors.add(label + " 的 evidence 非法");
        }
        if (!"inferred".equals(evidenceKind)) {
            int line = integer(item, "line", -1);
            int availableLines = availableLines(repositoryRoot, file, label, errors);
            if (availableLines >= 0 && (line < 1 || line > availableLines)) {
                errors.add(label + " 的行号不在目标快照文件范围内：" + line);
            }
        }
    }

    private void normalizePresentationMetadata(JsonObject report) {
        if (!report.has("analysis_focus") || !report.get("analysis_focus").isJsonObject()) {
            report.add("analysis_focus", new JsonObject());
        }
        if (!report.has("reader_guide") || !report.get("reader_guide").isJsonObject()) {
            JsonObject guide = new JsonObject();
            String title = string(report, "title");
            String summary = string(report, "summary");
            if (title != null) guide.addProperty("title", title);
            if (summary != null) guide.addProperty("subtitle", summary);
            report.add("reader_guide", guide);
        }
        if (!report.has("scope") || !report.get("scope").isJsonObject()) {
            report.add("scope", new JsonObject());
        }
    }

    private void normalizeDeterministicModelVariants(JsonObject report, EvidencePack evidence) {
        if (report.has("flow_map") && report.get("flow_map").isJsonObject()) {
            JsonObject flow = report.getAsJsonObject("flow_map");
            if (string(flow, "id") == null || string(flow, "id").isBlank()) {
                flow.addProperty("id", "root");
            }
            normalizeBusinessDataFlowOrders(flow);
        }

        JsonArray evolution = array(report, "commit_evolution");
        if (evolution != null) {
            for (JsonElement item : evolution) {
                if (item.isJsonObject()) {
                    JsonObject entry = item.getAsJsonObject();
                    normalizeEvidencePaths(entry, evidence, string(entry, "commit"));
                }
            }
        }
        JsonArray findings = array(report, "review_findings");
        if (findings != null) {
            for (JsonElement item : findings) {
                if (item.isJsonObject()) {
                    normalizeEvidencePaths(item.getAsJsonObject(), evidence, null);
                }
            }
        }
    }

    private void normalizeBusinessDataFlowOrders(JsonObject flow) {
        JsonArray dataFlow = array(flow, "data_flow");
        if (dataFlow != null) {
            Map<String, List<JsonObject>> hopsByLineage = new LinkedHashMap<>();
            for (JsonElement element : dataFlow) {
                if (!element.isJsonObject()) continue;
                JsonObject hop = element.getAsJsonObject();
                String lineageId = string(hop, "lineage_id");
                if (lineageId == null || lineageId.isBlank()) continue;
                hopsByLineage.computeIfAbsent(lineageId, ignored -> new ArrayList<>()).add(hop);
            }
            for (List<JsonObject> lineageHops : hopsByLineage.values()) {
                Set<Integer> declaredOrders = new HashSet<>();
                boolean canNormalize = true;
                for (JsonObject hop : lineageHops) {
                    int order = integer(hop, "order", -1);
                    if (order < 1 || !declaredOrders.add(order)) {
                        canNormalize = false;
                        break;
                    }
                }
                if (!canNormalize) continue;
                lineageHops.sort(java.util.Comparator.comparingInt(hop -> integer(hop, "order", -1)));
                for (int index = 0; index < lineageHops.size(); index++) {
                    lineageHops.get(index).addProperty("order", index + 1);
                }
            }
        }
        JsonArray children = array(flow, "children");
        if (children == null) return;
        for (JsonElement child : children) {
            if (child.isJsonObject()) normalizeBusinessDataFlowOrders(child.getAsJsonObject());
        }
    }

    private void normalizeChangeMetadata(JsonObject report, EvidencePack evidence) {
        Map<String, List<String>> commitsByPath = new HashMap<>();
        LinkedHashSet<String> selectedCommitIds = new LinkedHashSet<>();
        for (EvidencePack.CommitEvidence commit : evidence.commits()) {
            String hash = commit.commit().hash();
            selectedCommitIds.add(hash);
            for (String path : commit.changedPaths()) {
                commitsByPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(hash);
            }
        }

        Map<String, JsonObject> sourceNodes = new HashMap<>();
        LinkedHashSet<String> changedSourceNodeIds = new LinkedHashSet<>();
        JsonArray nodes = array(report, "nodes");
        if (nodes != null) {
            for (JsonElement item : nodes) {
                if (!item.isJsonObject()) continue;
                JsonObject node = item.getAsJsonObject();
                String id = string(node, "id");
                if (id != null) sourceNodes.put(id, node);
                String file = string(node, "file");
                List<String> pathCommits = file == null ? List.of() : commitsByPath.getOrDefault(file, List.of());
                LinkedHashSet<String> commitIds = filteredStrings(array(node, "changed_in_commits"), selectedCommitIds);
                commitIds.retainAll(pathCommits);
                if ("changed".equals(string(node, "change"))) commitIds.addAll(pathCommits);
                boolean changed = !commitIds.isEmpty();
                node.addProperty("change", changed ? "changed" : "unchanged");
                node.add("changed_in_commits", jsonArray(commitIds));
                if (changed && id != null) changedSourceNodeIds.add(id);
            }
        }

        LinkedHashSet<String> changedFlowIds = new LinkedHashSet<>();
        LinkedHashSet<String> affectedFlowIds = new LinkedHashSet<>();
        LinkedHashSet<String> contextFlowIds = new LinkedHashSet<>();
        LinkedHashSet<String> flowMappedChangedSourceIds = new LinkedHashSet<>();
        JsonObject flow = report.has("flow_map") && report.get("flow_map").isJsonObject()
                ? report.getAsJsonObject("flow_map")
                : null;
        if (flow != null) {
            normalizeFlowChange(
                    flow,
                    sourceNodes,
                    changedSourceNodeIds,
                    selectedCommitIds,
                    changedFlowIds,
                    affectedFlowIds,
                    contextFlowIds,
                    flowMappedChangedSourceIds,
                    true
            );
        }

        LinkedHashSet<String> nonFlowSourceNodeIds = new LinkedHashSet<>(changedSourceNodeIds);
        nonFlowSourceNodeIds.removeAll(flowMappedChangedSourceIds);
        LinkedHashSet<String> explainedPaths = new LinkedHashSet<>();
        for (String id : changedSourceNodeIds) {
            String file = string(sourceNodes.get(id), "file");
            if (file != null) explainedPaths.add(file);
        }
        LinkedHashSet<String> unmappedPaths = new LinkedHashSet<>(commitsByPath.keySet());
        unmappedPaths.removeAll(explainedPaths);

        JsonObject summary = report.has("change_summary") && report.get("change_summary").isJsonObject()
                ? report.getAsJsonObject("change_summary")
                : new JsonObject();
        summary.add("selected_commits", jsonArray(selectedCommitIds));
        summary.addProperty("changed_path_count", commitsByPath.size());
        summary.addProperty("changed_flow_count", changedFlowIds.size());
        summary.addProperty("affected_flow_count", affectedFlowIds.size());
        summary.add("changed_flow_node_ids", jsonArray(changedFlowIds));
        summary.add("affected_flow_node_ids", jsonArray(affectedFlowIds));
        summary.add("context_flow_node_ids", jsonArray(contextFlowIds));
        summary.add("non_flow_changed_source_node_ids", jsonArray(nonFlowSourceNodeIds));
        summary.add("unmapped_changed_paths", jsonArray(unmappedPaths));
        report.add("change_summary", summary);
    }

    private FlowChange normalizeFlowChange(
            JsonObject flow,
            Map<String, JsonObject> sourceNodes,
            Set<String> changedSourceNodeIds,
            Set<String> selectedCommitIds,
            Set<String> changedFlowIds,
            Set<String> affectedFlowIds,
            Set<String> contextFlowIds,
            Set<String> flowMappedChangedSourceIds,
            boolean root
    ) {
        LinkedHashSet<String> commitIds = filteredStrings(array(flow, "commit_ids"), selectedCommitIds);
        boolean directlyChanged = false;
        LinkedHashSet<String> directlyChangedSourceIds = new LinkedHashSet<>();
        JsonArray sourceIds = array(flow, "source_node_ids");
        if (sourceIds != null) {
            for (JsonElement item : sourceIds) {
                if (!item.isJsonPrimitive()) continue;
                String sourceId = item.getAsString();
                if (!changedSourceNodeIds.contains(sourceId)) continue;
                directlyChanged = true;
                directlyChangedSourceIds.add(sourceId);
                JsonObject source = sourceNodes.get(sourceId);
                commitIds.addAll(filteredStrings(array(source, "changed_in_commits"), selectedCommitIds));
            }
        }

        boolean changedDescendant = false;
        JsonArray children = array(flow, "children");
        if (children != null) {
            for (JsonElement item : children) {
                if (!item.isJsonObject()) continue;
                FlowChange child = normalizeFlowChange(
                        item.getAsJsonObject(), sourceNodes, changedSourceNodeIds, selectedCommitIds,
                        changedFlowIds, affectedFlowIds, contextFlowIds, flowMappedChangedSourceIds, false
                );
                if (!"context".equals(child.status())) {
                    changedDescendant = true;
                    commitIds.addAll(child.commitIds());
                }
            }
        }

        String requested = string(flow, "change_status");
        boolean requestedValid = requested != null && Set.of("changed", "affected", "context").contains(requested);
        String status;
        if (directlyChanged && ("changed".equals(requested) || !requestedValid)) {
            status = "changed";
        } else if (changedDescendant || "affected".equals(requested)) {
            status = "affected";
            if (commitIds.isEmpty()) commitIds.addAll(selectedCommitIds);
        } else {
            status = "context";
            commitIds.clear();
        }
        flow.addProperty("change_status", status);
        flow.add("commit_ids", jsonArray(commitIds));
        if ("changed".equals(status)) flowMappedChangedSourceIds.addAll(directlyChangedSourceIds);

        if (!root && !"business".equals(string(flow, "flow_scope"))) {
            String id = string(flow, "id");
            if (id != null) {
                if ("changed".equals(status)) changedFlowIds.add(id);
                else if ("affected".equals(status)) affectedFlowIds.add(id);
                else contextFlowIds.add(id);
            }
        }
        if (!"context".equals(status)) {
            JsonObject detail = flow.has("change_detail") && flow.get("change_detail").isJsonObject()
                    ? flow.getAsJsonObject("change_detail")
                    : new JsonObject();
            detail.add("commit_ids", jsonArray(commitIds));
            flow.add("change_detail", detail);
        }
        return new FlowChange(status, List.copyOf(commitIds));
    }

    private LinkedHashSet<String> filteredStrings(JsonArray values, Set<String> allowed) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (JsonElement item : values) {
            if (item.isJsonPrimitive() && allowed.contains(item.getAsString())) result.add(item.getAsString());
        }
        return result;
    }

    private JsonArray jsonArray(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private record FlowChange(String status, List<String> commitIds) {
    }

    private void normalizeEvidencePaths(JsonObject owner, EvidencePack evidence, String commitHash) {
        JsonArray paths = array(owner, "evidence_paths");
        if (paths == null) return;

        Set<String> allowed = new HashSet<>(evidence.targetManifest());
        evidence.commits().forEach(item -> allowed.addAll(item.changedPaths()));
        JsonArray normalizedPaths = new JsonArray();
        LinkedHashSet<String> uniquePaths = new LinkedHashSet<>();
        List<JsonElement> unresolved = new ArrayList<>();
        for (JsonElement item : paths) {
            if (!item.isJsonPrimitive()) {
                unresolved.add(item.deepCopy());
                continue;
            }
            String rawPath = item.getAsString();
            List<String> resolved = resolveEvidencePath(rawPath, allowed, evidence, commitHash);
            if (resolved == null) {
                uniquePaths.add(rawPath);
            } else {
                uniquePaths.addAll(resolved);
            }
        }
        uniquePaths.forEach(normalizedPaths::add);
        unresolved.forEach(normalizedPaths::add);
        owner.add("evidence_paths", normalizedPaths);
    }

    private List<String> resolveEvidencePath(
            String rawPath,
            Set<String> allowed,
            EvidencePack evidence,
            String commitHash
    ) {
        String candidate = rawPath.strip().replace('\\', '/');
        while (candidate.startsWith("./")) candidate = candidate.substring(2);
        if (candidate.startsWith("repository/")) candidate = candidate.substring("repository/".length());
        if (allowed.contains(candidate)) return List.of(candidate);

        String withoutLocation = stripSourceLocation(candidate);
        if (allowed.contains(withoutLocation)) return List.of(withoutLocation);

        for (int index = 0; index < evidence.commits().size(); index++) {
            EvidencePack.CommitEvidence commit = evidence.commits().get(index);
            if (commitHash != null && !commit.commit().hash().equals(commitHash)) continue;
            String prefix = String.format("evidence/%02d-%s", index + 1, commit.commit().shortHash());
            if (candidate.equalsIgnoreCase(prefix + ".diff")
                    || candidate.equalsIgnoreCase(prefix + ".name-status.txt")) {
                return commit.changedPaths();
            }
        }
        return null;
    }

    private String stripSourceLocation(String path) {
        int hashLine = path.lastIndexOf("#L");
        if (hashLine > 0 && path.substring(hashLine + 2).matches("\\d+(?:-L?\\d+)?")) {
            return path.substring(0, hashLine);
        }
        int colon = path.lastIndexOf(':');
        if (colon > 0 && path.substring(colon + 1).matches("\\d+(?:-\\d+)?")) {
            return path.substring(0, colon);
        }
        return path;
    }

    private void validateComparison(JsonObject comparison, EvidencePack evidence, List<String> errors) {
        if (comparison == null) {
            return;
        }
        requireExact(comparison, "mode", evidence.commits().isEmpty() ? "current_snapshot" : "selected_commits", errors);
        requireExact(comparison, "target_commit", evidence.targetCommit(), errors);
        requireExact(comparison, "target_tree", evidence.targetTree(), errors);
        requireExact(comparison, "fingerprint", evidence.fingerprint(), errors);
        JsonArray selected = requireArray(comparison, "selected_commits", errors);
        if (selected != null) {
            Set<String> actual = new HashSet<>();
            for (JsonElement item : selected) {
                if (item.isJsonPrimitive()) {
                    actual.add(item.getAsString());
                }
            }
            Set<String> expected = evidence.commits().stream().map(item -> item.commit().hash()).collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(expected)) {
                errors.add("comparison.selected_commits 与锁定的提交集合不一致");
            }
        }
    }

    private void validateSourceNodes(
            JsonArray nodes,
            EvidencePack evidence,
            Path repositoryRoot,
            List<String> errors
    ) {
        if (nodes == null) {
            return;
        }
        Set<String> manifest = new HashSet<>(evidence.targetManifest());
        for (JsonElement item : nodes) {
            if (!item.isJsonObject()) {
                errors.add("nodes 包含非对象条目");
                continue;
            }
            JsonObject node = item.getAsJsonObject();
            String id = string(node, "id");
            String file = string(node, "file");
            String evidenceKind = string(node, "evidence");
            if (file != null && !file.isBlank() && !manifest.contains(file)) {
                errors.add("节点 " + id + " 引用了目标快照中不存在的文件：" + file);
            }
            if (file != null && !file.isBlank() && SensitiveTextSanitizer.isSensitivePath(file)) {
                errors.add("节点 " + id + " 引用了禁止分析的敏感路径：" + file);
            }
            if (file != null && !file.isBlank() && !"inferred".equals(evidenceKind) && !node.has("line")) {
                errors.add("源码节点 " + id + " 缺少 line");
            }
            if (file != null && !file.isBlank() && !"inferred".equals(evidenceKind) && node.has("line")) {
                int line = integer(node, "line", -1);
                int endLine = integer(node, "end_line", line);
                int availableLines = availableLines(repositoryRoot, file, "节点 " + id, errors);
                if (availableLines >= 0 && (line < 1 || endLine < line || endLine > availableLines)) {
                    errors.add("源码节点 " + id + " 的行号不在目标提交文件范围内：" + line + "-" + endLine);
                }
            }
        }
    }

    private void validateCommitEvolution(
            JsonArray evolution,
            EvidencePack evidence,
            Set<String> nodeIds,
            List<String> errors
    ) {
        if (evolution == null) return;
        Set<String> expected = evidence.commits().stream().map(item -> item.commit().hash()).collect(java.util.stream.Collectors.toSet());
        List<String> expectedOrder = evidence.commits().stream()
                .map(item -> item.commit().hash())
                .toList();
        Set<String> actual = new HashSet<>();
        List<String> actualOrder = new ArrayList<>();
        Set<String> allowedPaths = new HashSet<>();
        evidence.commits().forEach(item -> allowedPaths.addAll(item.changedPaths()));
        for (JsonElement item : evolution) {
            if (!item.isJsonObject()) {
                errors.add("commit_evolution 包含非对象条目");
                continue;
            }
            JsonObject entry = item.getAsJsonObject();
            String hash = string(entry, "commit");
            if (hash == null || !expected.contains(hash)) {
                errors.add("commit_evolution 引用了未选择的提交：" + hash);
            } else if (!actual.add(hash)) {
                errors.add("commit_evolution 重复提交：" + hash);
            }
            if (hash != null) actualOrder.add(hash);
            validateReferences(array(entry, "affected_node_ids"), nodeIds, "commit_evolution " + hash + " affected_node_ids", errors);
            validatePaths(array(entry, "evidence_paths"), allowedPaths, "commit_evolution " + hash, errors);
        }
        if (!actual.equals(expected)) {
            errors.add("commit_evolution 必须且只能包含全部已选择提交");
        }
        if (actual.equals(expected) && !actualOrder.equals(expectedOrder)) {
            errors.add("commit_evolution 必须按 Git 拓扑从基线到目标排列");
        }
    }

    private void validateReviewFindings(
            JsonArray findings,
            EvidencePack evidence,
            Set<String> nodeIds,
            List<String> errors
    ) {
        if (findings == null) return;
        Set<String> allowedPaths = new HashSet<>(evidence.targetManifest());
        evidence.commits().forEach(item -> allowedPaths.addAll(item.changedPaths()));
        Set<String> severities = Set.of("critical", "high", "medium", "low", "info");
        for (JsonElement item : findings) {
            if (!item.isJsonObject()) {
                errors.add("review_findings 包含非对象条目");
                continue;
            }
            JsonObject finding = item.getAsJsonObject();
            String id = string(finding, "id");
            String severity = string(finding, "severity");
            if (!severities.contains(severity)) errors.add("review finding " + id + " severity 非法：" + severity);
            validateReferences(array(finding, "affected_node_ids"), nodeIds, "review finding " + id + " affected_node_ids", errors);
            validatePaths(array(finding, "evidence_paths"), allowedPaths, "review finding " + id, errors);
        }
    }

    private void validatePaths(JsonArray paths, Set<String> allowed, String label, List<String> errors) {
        if (paths == null) {
            errors.add(label + " 缺少 evidence_paths");
            return;
        }
        for (JsonElement path : paths) {
            if (!path.isJsonPrimitive() || !allowed.contains(path.getAsString())) {
                errors.add(label + " 引用了证据包中不存在的路径：" + path);
            }
        }
    }

    private void validateEdges(
            JsonArray edges,
            Set<String> nodeIds,
            EvidencePack evidence,
            Path repositoryRoot,
            List<String> errors
    ) {
        if (edges == null) {
            return;
        }
        Set<String> manifest = new HashSet<>(evidence.targetManifest());
        for (JsonElement item : edges) {
            if (!item.isJsonObject()) {
                errors.add("edges 包含非对象条目");
                continue;
            }
            JsonObject edge = item.getAsJsonObject();
            String id = string(edge, "id");
            for (String endpoint : List.of("source", "target")) {
                String value = string(edge, endpoint);
                if (value == null || !nodeIds.contains(value)) {
                    errors.add("边 " + id + " 的 " + endpoint + " 引用了不存在的节点：" + value);
                }
            }
            String file = string(edge, "file");
            if (file != null && !file.isBlank() && !manifest.contains(file)) {
                errors.add("边 " + id + " 引用了目标快照中不存在的文件：" + file);
            }
            if (file != null && !file.isBlank() && SensitiveTextSanitizer.isSensitivePath(file)) {
                errors.add("边 " + id + " 引用了禁止分析的敏感路径：" + file);
            }
            String evidenceKind = string(edge, "evidence_kind");
            if (file != null && !file.isBlank() && !"inferred".equals(evidenceKind)) {
                if (edge.has("line")) {
                    int line = integer(edge, "line", -1);
                    int availableLines = availableLines(repositoryRoot, file, "边 " + id, errors);
                    if (availableLines >= 0 && (line < 1 || line > availableLines)) {
                        errors.add("源码边 " + id + " 的行号不在目标提交文件范围内：" + line);
                    }
                }
            }
        }
    }

    private int availableLines(Path repositoryRoot, String file, String label, List<String> errors) {
        Path source = repositoryRoot.resolve(file).normalize();
        if (!source.startsWith(repositoryRoot) || !Files.isRegularFile(source)) {
            errors.add(label + " 无法在本机目标提交快照中读取文件：" + file);
            return -1;
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source), 64 * 1024)) {
            int lines = 1;
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                for (int index = 0; index < length; index++) {
                    if (buffer[index] == '\n') lines++;
                }
            }
            return lines;
        } catch (IOException exception) {
            errors.add(label + " 无法校验源码行号：" + file);
            return -1;
        }
    }

    private void validateDesign(JsonObject design, Set<String> nodeIds, Set<String> laneIds, List<String> errors) {
        JsonArray lanes = array(design, "lanes");
        if (lanes != null) {
            for (JsonElement item : lanes) {
                if (!item.isJsonObject()) continue;
                JsonObject lane = item.getAsJsonObject();
                String id = string(lane, "id");
                for (String field : List.of("represents", "why_here")) {
                    if (string(lane, field) == null) errors.add("lane " + id + " 缺少 " + field);
                }
                for (String field : List.of("responsibilities", "receives", "produces", "not_responsible", "source_node_ids")) {
                    if (array(lane, field) == null) errors.add("lane " + id + " 缺少数组 " + field);
                }
                validateReferences(array(lane, "source_node_ids"), nodeIds, "lane " + id + " source_node_ids", errors);
            }
        }
        JsonArray contracts = array(design, "contracts");
        if (contracts != null) {
            for (JsonElement item : contracts) {
                if (!item.isJsonObject()) continue;
                JsonObject contract = item.getAsJsonObject();
                String id = string(contract, "id");
                validateReference(string(contract, "source_lane_id"), laneIds, "contract " + id + " source_lane_id", errors);
                validateReference(string(contract, "target_lane_id"), laneIds, "contract " + id + " target_lane_id", errors);
                validateReferences(array(contract, "source_node_ids"), nodeIds, "contract " + id + " source_node_ids", errors);
            }
        }
    }

    private void collectFlow(JsonObject node, Set<String> ids, Map<String, JsonObject> nodes, List<String> errors) {
        String id = string(node, "id");
        if (id == null || id.isBlank()) {
            errors.add("flow_map 节点缺少 id");
            return;
        }
        if (!ids.add(id)) {
            errors.add("flow_map 节点 id 重复：" + id);
        }
        nodes.put(id, node);
        JsonArray children = array(node, "children");
        if (children == null) {
            errors.add("flow_map 节点 " + id + " 缺少 children 数组");
            return;
        }
        for (JsonElement child : children) {
            if (child.isJsonObject()) {
                collectFlow(child.getAsJsonObject(), ids, nodes, errors);
            } else {
                errors.add("flow_map 节点 " + id + " 的 children 包含非对象条目");
            }
        }
    }

    private void validateFlow(
            Map<String, JsonObject> flowNodes,
            Set<String> flowIds,
            Set<String> nodeIds,
            Set<String> laneIds,
            Set<String> contractIds,
            List<String> errors
    ) {
        for (Map.Entry<String, JsonObject> entry : flowNodes.entrySet()) {
            String id = entry.getKey();
            JsonObject node = entry.getValue();
            if (!"root".equals(id)) {
                validateReference(string(node, "lane_id"), laneIds, "flow " + id + " lane_id", errors);
            }
            validateReferences(array(node, "source_node_ids"), nodeIds, "flow " + id + " source_node_ids", errors);
            validateReferences(array(node, "contract_in_ids"), contractIds, "flow " + id + " contract_in_ids", errors);
            validateReferences(array(node, "contract_out_ids"), contractIds, "flow " + id + " contract_out_ids", errors);
            JsonArray branches = array(node, "branches");
            if (branches == null) continue;
            for (JsonElement item : branches) {
                if (!item.isJsonObject()) continue;
                JsonObject branch = item.getAsJsonObject();
                if (branch.has("target_id")) {
                    validateReference(string(branch, "target_id"), flowIds,
                            "flow " + id + " branch target_id", errors);
                }
                String outcome = string(branch, "outcome");
                if (outcome != null && !Set.of("continue", "success", "failure", "cancel", "terminal").contains(outcome)) {
                    errors.add("flow " + id + " branch outcome 非法：" + outcome);
                }
            }
        }
    }

    private Set<String> uniqueIds(JsonArray array, String label, List<String> errors) {
        Set<String> ids = new HashSet<>();
        if (array == null) return ids;
        for (JsonElement item : array) {
            if (!item.isJsonObject()) {
                errors.add(label + " 包含非对象条目");
                continue;
            }
            String id = string(item.getAsJsonObject(), "id");
            if (id == null || id.isBlank()) {
                errors.add(label + " 包含缺少 id 的条目");
            } else if (!ids.add(id)) {
                errors.add(label + " 的 id 重复：" + id);
            }
        }
        return ids;
    }

    private void validateReferences(JsonArray references, Set<String> available, String label, List<String> errors) {
        if (references == null) return;
        for (JsonElement item : references) {
            if (item.isJsonPrimitive()) {
                validateReference(item.getAsString(), available, label, errors);
            } else {
                errors.add(label + " 包含非字符串引用");
            }
        }
    }

    private void validateReference(String value, Set<String> available, String label, List<String> errors) {
        if (value == null || !available.contains(value)) {
            errors.add(label + " 引用了不存在的 ID：" + value);
        }
    }

    private JsonObject requireObject(JsonObject parent, String name, List<String> errors) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            errors.add("缺少对象字段 " + name);
            return null;
        }
        return parent.getAsJsonObject(name);
    }

    private JsonArray requireArray(JsonObject parent, String name, List<String> errors) {
        JsonArray result = array(parent, name);
        if (result == null) errors.add("缺少数组字段 " + name);
        return result;
    }

    private JsonArray array(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray() ? parent.getAsJsonArray(name) : null;
    }

    private JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name)
                : new JsonObject();
    }

    private void requireExact(JsonObject parent, String name, String expected, List<String> errors) {
        String actual = string(parent, name);
        if (!expected.equals(actual)) {
            errors.add(name + " 必须是 " + expected + "，实际为 " + actual);
        }
    }

    private String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) return null;
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private int integer(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) return fallback;
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean oneOf(String value, String... allowed) {
        return value != null && Set.of(allowed).contains(value);
    }

    private String stripMarkdownFence(String value) {
        if (!value.startsWith("```")) return value;
        int firstNewline = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return value.substring(firstNewline + 1, lastFence).strip();
        }
        return value;
    }
}
