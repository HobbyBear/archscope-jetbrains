package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
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

public final class DomainReportAssembler {
    private static final String SCHEMA = "closed-business-domain-analysis/v1";

    public JsonObject assemble(String raw, AnalysisRequest request, EvidencePack evidence) throws ModelClientException {
        JsonObject analysis = parse(raw);
        if (!SCHEMA.equals(string(analysis, "schema"))) {
            throw new ModelClientException("业务分析 schema 无效");
        }
        List<JsonObject> domainObjects = objects(array(analysis, "domains"));
        List<JsonObject> flowObjects = objects(array(analysis, "flows"));
        if (domainObjects.isEmpty() || flowObjects.isEmpty()) {
            throw new ModelClientException("业务分析没有返回业务域或完整流程");
        }

        Set<String> manifest = Set.copyOf(evidence.targetManifest());
        Map<String, Domain> domains = new LinkedHashMap<>();
        for (int index = 0; index < domainObjects.size(); index++) {
            JsonObject source = domainObjects.get(index);
            String id = stableId(string(source, "id"), "domain-" + (index + 1));
            domains.putIfAbsent(id, new Domain(id, source, "lane-" + (domains.size() + 1)));
        }

        JsonArray nodes = new JsonArray();
        JsonArray edges = new JsonArray();
        JsonArray contracts = new JsonArray();
        JsonArray flowGroups = new JsonArray();
        Map<String, LinkedHashSet<String>> sourceIdsByDomain = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> sourceIdsBySourceStep = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> flowIdsByDomain = new LinkedHashMap<>();

        for (int flowIndex = 0; flowIndex < flowObjects.size(); flowIndex++) {
            JsonObject sourceFlow = flowObjects.get(flowIndex);
            String flowId = stableId(string(sourceFlow, "id"), "business-flow-" + (flowIndex + 1));
            List<String> flowDomainIds = strings(array(sourceFlow, "domain_ids")).stream()
                    .filter(domains::containsKey).distinct().toList();
            List<JsonObject> steps = objects(array(sourceFlow, "steps"));
            if (steps.size() < 4) {
                throw new ModelClientException("业务流程 " + flowId + " 少于 4 个步骤，无法视为完整流程");
            }
            JsonArray children = new JsonArray();
            List<String> stepNodeIds = new ArrayList<>();
            List<String> stepLaneIds = new ArrayList<>();
            Map<String, String> reportStepIds = new LinkedHashMap<>();
            List<StepBinding> stepBindings = new ArrayList<>();
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                JsonObject step = steps.get(stepIndex);
                String domainId = domains.containsKey(string(step, "domain_id"))
                        ? string(step, "domain_id")
                        : flowDomainIds.stream().findFirst().orElse(domains.keySet().iterator().next());
                Domain domain = domains.get(domainId);
                String nodeId = "domain-node-" + (flowIndex + 1) + "-" + (stepIndex + 1);
                String stepId = "domain-step-" + (flowIndex + 1) + "-" + (stepIndex + 1);
                String sourceStepId = stableId(string(step, "id"), "step-" + (stepIndex + 1));
                reportStepIds.put(sourceStepId, stepId);
                String file = manifest.contains(string(step, "file")) ? string(step, "file") : "";
                int line = positiveInteger(step, "line", 1);
                String evidenceKind = normalizeEvidence(string(step, "evidence"), file);
                stepBindings.add(new StepBinding(
                        sourceStepId, stepId, file, string(step, "symbol"), line
                ));
                String execution = string(step, "execution");
                if (!Set.of("same_execution", "async_continuation").contains(execution)) {
                    throw new ModelClientException("业务步骤 " + sourceStepId + " 没有说明与当前触发的执行关系");
                }

                JsonObject node = new JsonObject();
                node.addProperty("id", nodeId);
                node.addProperty("kind", normalizeNodeKind(string(step, "node_kind")));
                node.addProperty("label", fallback(string(step, "symbol"), string(step, "title")));
                node.addProperty("service", domain.id());
                node.addProperty("module", domain.id());
                node.addProperty("file", file);
                node.addProperty("line", line);
                node.addProperty("end_line", line);
                node.addProperty("responsibility", string(step, "summary"));
                node.add("inputs", copy(array(step, "inputs")));
                node.add("outputs", copy(array(step, "outputs")));
                node.add("state_effects", copy(array(step, "state_effects")));
                node.add("feature_ids", stringsJson(flowId));
                JsonObject roles = new JsonObject();
                roles.addProperty(flowId, "core");
                node.add("feature_roles", roles);
                node.addProperty("change", "unchanged");
                node.add("changed_in_commits", new JsonArray());
                node.addProperty("source_kind", "repository");
                node.addProperty("evidence", evidenceKind);
                node.addProperty("confidence", normalizeConfidence(string(step, "confidence")));
                nodes.add(node);
                stepNodeIds.add(nodeId);
                stepLaneIds.add(domain.laneId());
                sourceIdsByDomain.computeIfAbsent(domainId, ignored -> new LinkedHashSet<>()).add(nodeId);
                JsonArray stepSourceNodeIds = stringsJson(nodeId);

                JsonArray supportingSources = sourceBackedItems(array(step, "supporting_sources"), manifest);
                int supportingIndex = 0;
                for (JsonElement supportingElement : supportingSources) {
                    JsonObject supporting = supportingElement.getAsJsonObject();
                    String supportingId = nodeId + "-support-" + (++supportingIndex);
                    JsonObject supportingNode = new JsonObject();
                    supportingNode.addProperty("id", supportingId);
                    supportingNode.addProperty("kind", "method");
                    supportingNode.addProperty("label", fallback(string(supporting, "symbol"), "补充源码"));
                    supportingNode.addProperty("service", domain.id());
                    supportingNode.addProperty("module", domain.id());
                    supportingNode.addProperty("file", string(supporting, "file"));
                    supportingNode.addProperty("line", positiveInteger(supporting, "line", 1));
                    supportingNode.addProperty("end_line", positiveInteger(supporting, "line", 1));
                    supportingNode.addProperty("responsibility", string(supporting, "meaning"));
                    supportingNode.add("inputs", new JsonArray());
                    supportingNode.add("outputs", new JsonArray());
                    supportingNode.add("state_effects", new JsonArray());
                    supportingNode.add("feature_ids", stringsJson(flowId));
                    supportingNode.add("feature_roles", roles.deepCopy());
                    supportingNode.addProperty("change", "unchanged");
                    supportingNode.add("changed_in_commits", new JsonArray());
                    supportingNode.addProperty("source_kind", "repository");
                    supportingNode.addProperty("evidence", normalizeEvidence(
                            string(supporting, "evidence"), string(supporting, "file")));
                    supportingNode.addProperty("confidence", normalizeConfidence(string(supporting, "confidence")));
                    nodes.add(supportingNode);
                    stepSourceNodeIds.add(supportingId);
                    sourceIdsByDomain.computeIfAbsent(domainId, ignored -> new LinkedHashSet<>()).add(supportingId);
                }
                LinkedHashSet<String> sourceStepNodes = sourceIdsBySourceStep.computeIfAbsent(
                        sourceStepId, ignored -> new LinkedHashSet<>());
                strings(stepSourceNodeIds).forEach(sourceStepNodes::add);

                JsonObject flowStep = new JsonObject();
                flowStep.addProperty("id", stepId);
                flowStep.addProperty("source_step_id", sourceStepId);
                flowStep.addProperty("title", fallback(string(step, "title"), string(step, "symbol")));
                flowStep.addProperty("summary", string(step, "summary"));
                flowStep.addProperty("kind", normalizeFlowKind(string(step, "kind")));
                flowStep.addProperty("execution", execution);
                flowStep.addProperty("lane_id", domain.laneId());
                flowStep.addProperty("change_status", "context");
                flowStep.add("commit_ids", new JsonArray());
                flowStep.add("children", new JsonArray());
                flowStep.add("branches", copy(array(step, "branches")));
                flowStep.add("contract_in_ids", new JsonArray());
                flowStep.add("contract_out_ids", new JsonArray());
                flowStep.add("source_node_ids", stepSourceNodeIds);
                flowStep.add("business_rules", copy(array(step, "business_rules")));
                flowStep.add("state_effects", copy(array(step, "state_effects")));
                if (step.has("status_change") && step.get("status_change").isJsonObject()) {
                    flowStep.add("status_change", step.getAsJsonObject("status_change").deepCopy());
                }
                children.add(flowStep);

                if (stepIndex > 0) {
                    String edgeId = "domain-edge-" + (flowIndex + 1) + "-" + stepIndex;
                    JsonObject edge = new JsonObject();
                    edge.addProperty("id", edgeId);
                    edge.addProperty("source", stepNodeIds.get(stepIndex - 1));
                    edge.addProperty("target", nodeId);
                    edge.add("feature_ids", stringsJson(flowId));
                    edge.add("feature_roles", roles.deepCopy());
                    edge.add("scenario_ids", new JsonArray());
                    edge.addProperty("number", String.valueOf(stepIndex));
                    edge.addProperty("kind", normalizeRelation(string(step, "relation_kind")));
                    edge.addProperty("label", fallback(string(step, "relation_label"),
                            "async_continuation".equals(execution) ? "异步延续到下一步骤" : "进入下一业务步骤"));
                    edge.addProperty("payload", strings(array(step, "inputs")).stream().findFirst().orElse(""));
                    edge.addProperty("meaning", string(step, "summary"));
                    edge.addProperty("evidence_kind", evidenceKind);
                    edge.addProperty("file", file);
                    edge.addProperty("line", line);
                    edge.addProperty("confidence", normalizeConfidence(string(step, "confidence")));
                    edges.add(edge);

                    String previousLane = stepLaneIds.get(stepIndex - 1);
                    if (!previousLane.equals(domain.laneId())) {
                        String contractId = "domain-contract-" + (flowIndex + 1) + "-" + stepIndex;
                        JsonObject contract = new JsonObject();
                        contract.addProperty("id", contractId);
                        contract.addProperty("name", edge.get("label").getAsString());
                        contract.addProperty("source_lane_id", previousLane);
                        contract.addProperty("target_lane_id", domain.laneId());
                        contract.addProperty("kind", edge.get("kind").getAsString());
                        contract.addProperty("payload", edge.get("payload").getAsString());
                        contract.addProperty("meaning", edge.get("meaning").getAsString());
                        contract.addProperty("lifecycle", "async_continuation".equals(execution)
                                ? "由当前触发直接发起的异步延续" : "当前触发的同一次执行内");
                        contract.add("source_node_ids", stringsJson(stepNodeIds.get(stepIndex - 1), nodeId));
                        contracts.add(contract);
                        children.get(stepIndex - 1).getAsJsonObject().getAsJsonArray("contract_out_ids").add(contractId);
                        flowStep.getAsJsonArray("contract_in_ids").add(contractId);
                    }
                }
            }

            String primaryDomainId = flowDomainIds.stream().findFirst().orElse(domains.keySet().iterator().next());
            JsonObject flowRoot = new JsonObject();
            flowRoot.addProperty("id", flowId);
            flowRoot.addProperty("flow_scope", "business");
            flowRoot.addProperty("title", string(sourceFlow, "title"));
            flowRoot.addProperty("summary", string(sourceFlow, "summary"));
            flowRoot.addProperty("flow_type", string(sourceFlow, "flow_type"));
            flowRoot.addProperty("execution_scope", string(sourceFlow, "execution_scope"));
            flowRoot.addProperty("actor", string(sourceFlow, "actor"));
            flowRoot.addProperty("trigger", string(sourceFlow, "trigger"));
            flowRoot.addProperty("routing_condition", string(sourceFlow, "routing_condition"));
            flowRoot.add("preconditions", copy(array(sourceFlow, "preconditions")));
            flowRoot.addProperty("outcome", string(sourceFlow, "outcome"));
            flowRoot.addProperty("end_title", fallback(string(sourceFlow, "end_title"), string(sourceFlow, "outcome")));
            flowRoot.addProperty("data_subject", string(sourceFlow, "data_subject"));
            flowRoot.addProperty("primary_origin_id", string(sourceFlow, "primary_origin_id"));
            JsonObject entrySource = sourceBackedObject(copyObject(sourceFlow, "entry_source"), manifest);
            remapStepReference(entrySource, "step_id", reportStepIds, stepBindings);
            flowRoot.add("entry_source", entrySource);
            flowRoot.add("data_reads", copy(array(sourceFlow, "data_reads")));
            flowRoot.add("data_writes", copy(array(sourceFlow, "data_writes")));
            JsonArray origins = sourceBackedItems(array(sourceFlow, "data_origins"), manifest);
            remapStepReferences(origins, "joins_step_id", reportStepIds, stepBindings);
            flowRoot.add("data_origins", origins);
            JsonArray dataFlow = sourceBackedItems(array(sourceFlow, "data_flow"), manifest);
            remapStepReferences(dataFlow, "step_id", reportStepIds, stepBindings);
            flowRoot.add("data_flow", dataFlow);
            JsonArray consumerTargets = sourceBackedItems(array(sourceFlow, "consumer_targets"), manifest);
            remapStepReferences(consumerTargets, "after_step_id", reportStepIds, stepBindings);
            flowRoot.add("consumer_targets", consumerTargets);
            flowRoot.add("failure_paths", copy(array(sourceFlow, "failure_paths")));
            flowRoot.addProperty("lane_id", domains.get(primaryDomainId).laneId());
            flowRoot.addProperty("change_status", "context");
            flowRoot.add("commit_ids", new JsonArray());
            flowRoot.add("children", children);
            flowRoot.add("branches", new JsonArray());
            flowRoot.add("contract_in_ids", new JsonArray());
            flowRoot.add("contract_out_ids", new JsonArray());
            flowRoot.add("source_node_ids", stringsJson(stepNodeIds));
            flowRoot.add("business_rules", copy(array(sourceFlow, "business_rules")));
            flowGroups.add(flowRoot);
            for (String domainId : flowDomainIds.isEmpty() ? List.of(primaryDomainId) : flowDomainIds) {
                flowIdsByDomain.computeIfAbsent(domainId, ignored -> new LinkedHashSet<>()).add(flowId);
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", "code-architecture-report/v1");
        report.addProperty("source_format", "business-domain-walkthrough/v1");
        report.addProperty("title", string(analysis, "title"));
        report.addProperty("summary", string(analysis, "summary"));
        JsonObject focus = new JsonObject();
        focus.addProperty("request", request.focus());
        focus.addProperty("audience", "首次接触该业务的工程师");
        report.add("analysis_focus", focus);
        JsonObject guide = new JsonObject();
        guide.addProperty("title", string(analysis, "title"));
        guide.addProperty("subtitle", string(analysis, "summary"));
        guide.addProperty("start_here", "先看业务总览，再按业务流程阅读完整路径");
        guide.addProperty("how_to_read", "业务域说明职责，流程说明职责如何协作完成用户目标");
        report.add("reader_guide", guide);
        JsonObject businessOverview = copyObject(analysis, "business_overview");
        JsonArray businessObjects = sourceBackedItems(array(businessOverview, "business_objects"), manifest);
        normalizeBusinessObjectStorageKinds(businessObjects);
        businessOverview.add("business_objects", businessObjects);
        report.add("business_overview", businessOverview);

        JsonArray businessDomains = new JsonArray();
        JsonArray lanes = new JsonArray();
        for (Domain domain : domains.values()) {
            JsonObject source = domain.source();
            LinkedHashSet<String> domainSourceIds = new LinkedHashSet<>(
                    sourceIdsByDomain.getOrDefault(domain.id(), new LinkedHashSet<>()));
            for (String sourceStepId : strings(array(source, "source_step_ids"))) {
                domainSourceIds.addAll(sourceIdsBySourceStep.getOrDefault(sourceStepId, new LinkedHashSet<>()));
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", domain.id());
            item.addProperty("name", string(source, "name"));
            item.addProperty("purpose", string(source, "purpose"));
            item.addProperty("why_here", fallback(string(source, "why_here"), string(source, "purpose")));
            item.add("actors", copy(array(source, "actors")));
            item.add("owns", copy(array(source, "owns")));
            item.add("receives", copy(array(source, "receives")));
            item.add("produces", copy(array(source, "produces")));
            item.add("not_responsible", copy(array(source, "not_responsible")));
            item.add("depends_on", filteredStrings(array(source, "depends_on"), domains.keySet()));
            item.add("flow_ids", stringsJson(flowIdsByDomain.getOrDefault(domain.id(), new LinkedHashSet<>())));
            item.add("source_node_ids", stringsJson(domainSourceIds));
            businessDomains.add(item);

            JsonObject lane = new JsonObject();
            lane.addProperty("id", domain.laneId());
            lane.addProperty("name", string(source, "name"));
            lane.addProperty("code_label", domain.id());
            lane.addProperty("represents", string(source, "purpose"));
            lane.add("responsibilities", copy(array(source, "owns")));
            lane.addProperty("why_here", fallback(string(source, "why_here"), string(source, "purpose")));
            lane.add("receives", copy(array(source, "receives")));
            lane.add("produces", copy(array(source, "produces")));
            lane.add("not_responsible", copy(array(source, "not_responsible")));
            lane.add("source_node_ids", stringsJson(domainSourceIds));
            lanes.add(lane);
        }
        report.add("business_domains", businessDomains);
        JsonObject design = new JsonObject();
        JsonArray principles = new JsonArray();
        principles.add("业务域表示稳定职责，完整流程表示职责间协作。");
        principles.add("无法由源码证实的关系保留在待确认项中。");
        design.add("principles", principles);
        design.add("lanes", lanes);
        design.add("contracts", contracts);
        design.add("risks", new JsonArray());
        report.add("architecture_design", design);

        JsonObject flowRoot = new JsonObject();
        flowRoot.addProperty("id", "root");
        flowRoot.addProperty("title", string(analysis, "title"));
        flowRoot.addProperty("summary", string(analysis, "summary"));
        flowRoot.addProperty("execution", "independent");
        flowRoot.add("children", flowGroups);
        flowRoot.add("branches", new JsonArray());
        flowRoot.add("source_node_ids", new JsonArray());
        flowRoot.add("contract_in_ids", new JsonArray());
        flowRoot.add("contract_out_ids", new JsonArray());
        report.add("flow_map", flowRoot);

        JsonObject scope = new JsonObject();
        scope.addProperty("mode", "current_snapshot");
        scope.addProperty("target_commit", evidence.targetCommit());
        report.add("scope", scope);
        JsonObject comparison = new JsonObject();
        comparison.addProperty("mode", "current_snapshot");
        comparison.add("selected_commits", new JsonArray());
        comparison.addProperty("target_commit", evidence.targetCommit());
        comparison.addProperty("target_tree", evidence.targetTree());
        comparison.addProperty("fingerprint", evidence.fingerprint());
        report.add("comparison", comparison);
        report.add("features", new JsonArray());
        report.add("services", new JsonArray());
        report.add("nodes", nodes);
        report.add("edges", edges);
        report.add("scenarios", new JsonArray());
        report.add("data_structures", businessObjects.deepCopy());
        JsonArray tables = new JsonArray();
        for (JsonElement element : businessObjects) {
            if (element.isJsonObject() && "table".equals(string(element.getAsJsonObject(), "storage_kind"))) {
                tables.add(element.deepCopy());
            }
        }
        report.add("tables", tables);
        report.add("evidence", new JsonArray());
        report.add("unknowns", humanizedUnknowns(array(analysis, "unknowns")));
        report.add("commit_evolution", new JsonArray());
        report.add("review_findings", new JsonArray());
        report.add("revision_history", copy(array(analysis, "revision_history")));
        report.add("change_summary", new JsonObject());
        return report;
    }

    private JsonObject parse(String raw) throws ModelClientException {
        try {
            String stripped = raw.strip();
            if (stripped.startsWith("```")) {
                int first = stripped.indexOf('\n');
                int last = stripped.lastIndexOf("```");
                if (first >= 0 && last > first) stripped = stripped.substring(first + 1, last).strip();
            }
            return JsonParser.parseString(stripped).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new ModelClientException("模型没有返回合法的业务分析 JSON：" + exception.getMessage(), exception);
        }
    }

    private JsonArray sourceBackedItems(JsonArray values, Set<String> manifest) {
        JsonArray result = new JsonArray();
        if (values == null) return result;
        for (JsonElement element : values) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject().deepCopy();
            String file = string(item, "file").replace('\\', '/');
            if (!manifest.contains(file)) file = "";
            item.addProperty("file", file);
            item.addProperty("line", positiveInteger(item, "line", 1));
            result.add(item);
        }
        return result;
    }

    private JsonObject sourceBackedObject(JsonObject item, Set<String> manifest) {
        String file = string(item, "file").replace('\\', '/');
        if (!manifest.contains(file)) file = "";
        item.addProperty("file", file);
        item.addProperty("line", positiveInteger(item, "line", 1));
        return item;
    }

    private void remapStepReferences(
            JsonArray values,
            String field,
            Map<String, String> reportStepIds,
            List<StepBinding> stepBindings
    ) {
        for (JsonElement element : values) {
            if (element.isJsonObject()) {
                remapStepReference(element.getAsJsonObject(), field, reportStepIds, stepBindings);
            }
        }
    }

    private void remapStepReference(
            JsonObject item,
            String field,
            Map<String, String> reportStepIds,
            List<StepBinding> stepBindings
    ) {
        String sourceId = string(item, field);
        if (sourceId.isBlank()) return;
        String reportId = reportStepIds.get(sourceId);
        if (reportId == null) reportId = sourceMatchedStep(item, stepBindings);
        if (reportId != null) item.addProperty(field, reportId);
    }

    private String sourceMatchedStep(JsonObject item, List<StepBinding> stepBindings) {
        String file = string(item, "file");
        String symbol = string(item, "symbol");
        int line = positiveInteger(item, "line", 1);
        List<StepBinding> exactSymbol = stepBindings.stream()
                .filter(binding -> !symbol.isBlank() && symbol.equals(binding.symbol()))
                .filter(binding -> file.isBlank() || file.equals(binding.file()))
                .toList();
        if (!exactSymbol.isEmpty()) return closestStep(exactSymbol, line).reportId();
        List<StepBinding> exactLine = stepBindings.stream()
                .filter(binding -> !file.isBlank() && file.equals(binding.file()) && line == binding.line())
                .toList();
        return exactLine.size() == 1 ? exactLine.get(0).reportId() : null;
    }

    private StepBinding closestStep(List<StepBinding> candidates, int line) {
        return candidates.stream().min(java.util.Comparator
                .comparingInt((StepBinding binding) -> Math.abs(binding.line() - line))
                .thenComparing(StepBinding::sourceId)).orElseThrow();
    }

    private JsonArray filteredStrings(JsonArray values, Set<String> allowed) {
        JsonArray result = new JsonArray();
        strings(values).stream().filter(allowed::contains).distinct().forEach(result::add);
        return result;
    }

    private void normalizeBusinessObjectStorageKinds(JsonArray businessObjects) {
        Set<String> allowed = Set.of("payload", "struct", "table", "event", "config", "unknown");
        for (JsonElement element : businessObjects) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String storageKind = string(object, "storage_kind");
            if (allowed.contains(storageKind) && !"unknown".equals(storageKind)) continue;
            if (string(object, "file").isBlank()) {
                object.addProperty("storage_kind", "unknown");
                continue;
            }
            String semanticText = String.join(" ",
                    string(object, "name"), string(object, "plain_meaning"),
                    string(object, "lifecycle"), string(object, "symbol")
            ).toLowerCase(java.util.Locale.ROOT);
            String normalized = containsAny(semanticText, "配置", "config", "价格规则", "开关") ? "config"
                    : containsAny(semanticText, "运行事件", "领域事件", " event", "事件记录") ? "event"
                    : containsAny(semanticText, "数据库", "数据表", " table", "落库", "持久化记录") ? "table"
                    : containsAny(semanticText, "客户端传", "请求载荷", "请求参数", "入参", "payload") ? "payload"
                    : "struct";
            object.addProperty("storage_kind", normalized);
        }
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private JsonObject copyObject(JsonObject owner, String name) {
        return owner.has(name) && owner.get(name).isJsonObject() ? owner.getAsJsonObject(name).deepCopy() : new JsonObject();
    }

    private JsonArray copy(JsonArray values) {
        return values == null ? new JsonArray() : values.deepCopy();
    }

    private JsonArray stringsJson(String... values) {
        return stringsJson(List.of(values));
    }

    private JsonArray stringsJson(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private List<JsonObject> objects(JsonArray values) {
        if (values == null) return List.of();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : values) if (item.isJsonObject()) result.add(item.getAsJsonObject());
        return result;
    }

    private List<String> strings(JsonArray values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement item : values) if (item.isJsonPrimitive()) result.add(item.getAsString());
        return result;
    }

    private JsonArray humanizedUnknowns(JsonArray values) {
        JsonArray result = new JsonArray();
        for (JsonElement value : values) {
            JsonObject unknown = value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
            String text = value.isJsonPrimitive() ? value.getAsString() : string(unknown, "question");
            text = text
                    .replace("source_evidence", "当前仓库源码")
                    .replace("current_report", "当前报告")
                    .replace("business_evidence_plan", "源码检索计划");
            unknown.addProperty("question", text);
            if (!unknown.has("kind")) unknown.addProperty("kind", "outcome");
            if (!unknown.has("flow_id")) unknown.addProperty("flow_id", "");
            if (!unknown.has("symbols") || !unknown.get("symbols").isJsonArray()) unknown.add("symbols", new JsonArray());
            if (!unknown.has("why_material")) unknown.addProperty("why_material", text);
            result.add(unknown);
        }
        return result;
    }

    private JsonArray array(JsonObject owner, String name) {
        return owner.has(name) && owner.get(name).isJsonArray() ? owner.getAsJsonArray(name) : null;
    }

    private String string(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonPrimitive() ? owner.get(name).getAsString() : "";
    }

    private int positiveInteger(JsonObject owner, String name, int fallback) {
        try {
            int value = owner.get(name).getAsInt();
            return value > 0 ? value : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stableId(String value, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return normalized.isBlank() ? fallback : normalized;
    }

    private String normalizeEvidence(String value, String file) {
        if (file.isBlank()) return "inferred";
        return Set.of("direct_source", "source_backed_walkthrough", "inferred").contains(value) ? value : "direct_source";
    }

    private String normalizeConfidence(String value) {
        return Set.of("high", "medium", "low").contains(value) ? value : "medium";
    }

    private String normalizeNodeKind(String value) {
        return Set.of("function", "method", "module", "service", "interface", "struct", "table").contains(value)
                ? value : "method";
    }

    private String normalizeFlowKind(String value) {
        return Set.of("stage", "decision", "success", "failure").contains(value) ? value : "stage";
    }

    private String normalizeRelation(String value) {
        return Set.of("call", "data", "http", "websocket", "event", "sql", "transaction", "reads", "writes").contains(value)
                ? value : "call";
    }

    private record Domain(String id, JsonObject source, String laneId) {
    }

    private record StepBinding(String sourceId, String reportId, String file, String symbol, int line) {
    }
}
