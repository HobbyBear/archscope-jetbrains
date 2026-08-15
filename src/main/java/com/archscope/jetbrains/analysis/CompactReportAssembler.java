package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompactReportAssembler {
    public JsonObject assemble(String raw, AnalysisRequest request, EvidencePack evidence) throws ModelClientException {
        JsonObject analysis = parse(raw);
        if (!"closed-change-analysis/v1".equals(string(analysis, "schema"))) {
            throw new ModelClientException("Codex 闭合分析 schema 无效");
        }

        Set<String> selectedCommits = evidence.commits().stream()
                .map(item -> item.commit().hash())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Set<String>> changedCommitsByPath = changedCommitsByPath(evidence);
        JsonObject report = new JsonObject();
        report.addProperty("schema", "code-architecture-report/v1");
        report.addProperty("source_format", "code-change-walkthrough/v2");
        report.addProperty("title", fallback(string(analysis, "title"), "所选提交组合变更"));
        report.addProperty("summary", fallback(string(analysis, "summary"), "所选提交的聚合行为变化。"));

        JsonObject focus = new JsonObject();
        focus.addProperty("title", "所选提交组合变更");
        focus.addProperty("description", request.focus());
        report.add("analysis_focus", focus);
        JsonObject guide = new JsonObject();
        guide.addProperty("title", string(report, "title"));
        guide.addProperty("subtitle", string(report, "summary"));
        report.add("reader_guide", guide);

        JsonArray features = new JsonArray();
        JsonArray nodes = new JsonArray();
        JsonArray edges = new JsonArray();
        JsonArray flowGroups = new JsonArray();
        JsonArray contracts = new JsonArray();
        Map<String, Lane> lanes = new LinkedHashMap<>();
        Map<String, List<String>> nodeIdsByGroup = new HashMap<>();
        Map<String, List<String>> changedNodeIdsByCommit = new HashMap<>();
        Map<String, LinkedHashSet<String>> changedPathsByCommit = new HashMap<>();
        List<JsonObject> groupObjects = objects(array(analysis, "groups"));

        for (int groupIndex = 0; groupIndex < groupObjects.size(); groupIndex++) {
            JsonObject group = groupObjects.get(groupIndex);
            String groupId = "feature-" + (groupIndex + 1);
            String sourceGroupId = string(group, "id");
            JsonObject feature = new JsonObject();
            feature.addProperty("id", groupId);
            feature.addProperty("name", fallback(string(group, "title"), "改动主题 " + (groupIndex + 1)));
            feature.addProperty("summary", fallback(string(group, "summary"), string(group, "after")));
            features.add(feature);

            List<JsonObject> stepObjects = objects(array(group, "steps"));
            JsonArray flowSteps = new JsonArray();
            List<String> groupNodeIds = new ArrayList<>();
            List<JsonObject> stepFlows = new ArrayList<>();
            List<String> stepLaneIds = new ArrayList<>();
            List<String> groupCommitIds = filteredStrings(array(group, "commit_ids"), selectedCommits);

            for (int stepIndex = 0; stepIndex < stepObjects.size(); stepIndex++) {
                JsonObject step = stepObjects.get(stepIndex);
                String nodeId = "node-" + (groupIndex + 1) + "-" + (stepIndex + 1);
                String flowId = "flow-" + (groupIndex + 1) + "-" + (stepIndex + 1);
                String module = fallback(string(step, "module"), moduleFromPath(string(step, "file")));
                Lane lane = lanes.computeIfAbsent(module, key -> new Lane("lane-" + (lanes.size() + 1), module));
                lane.addNode(nodeId, fallback(string(step, "responsibility"), string(step, "summary")),
                        string(step, "module_role"), strings(array(step, "inputs")), strings(array(step, "outputs")));

                String file = string(step, "file");
                int line = integer(step, "line", 1);
                int endLine = Math.max(line, integer(step, "end_line", line));
                Set<String> pathCommits = changedCommitsByPath.getOrDefault(file, Set.of());
                Set<String> groupCommitScope = groupCommitIds.isEmpty()
                        ? selectedCommits
                        : new LinkedHashSet<>(groupCommitIds);
                List<String> stepCommits = pathCommits.stream().filter(groupCommitScope::contains).toList();
                boolean changed = "changed".equals(string(step, "change_status")) && !stepCommits.isEmpty();

                JsonObject node = new JsonObject();
                node.addProperty("id", nodeId);
                node.addProperty("kind", "method");
                node.addProperty("label", fallback(string(step, "symbol"), string(step, "title")));
                node.addProperty("service", module);
                node.addProperty("module", module);
                if (file != null && !file.isBlank()) {
                    node.addProperty("file", file);
                    node.addProperty("line", line);
                    node.addProperty("end_line", endLine);
                }
                node.addProperty("responsibility", fallback(string(step, "responsibility"), string(step, "summary")));
                node.add("inputs", copy(array(step, "inputs")));
                node.add("outputs", copy(array(step, "outputs")));
                node.add("feature_ids", strings(groupId));
                JsonObject roles = new JsonObject();
                roles.addProperty(groupId, changed ? "core" : "context");
                node.add("feature_roles", roles);
                node.addProperty("change", changed ? "changed" : "unchanged");
                node.add("changed_in_commits", strings(stepCommits));
                node.addProperty("source_kind", "repository");
                node.addProperty("evidence", normalizeEvidence(string(step, "evidence")));
                node.addProperty("confidence", normalizeConfidence(string(step, "confidence")));
                nodes.add(node);
                groupNodeIds.add(nodeId);
                if (changed) for (String commit : stepCommits) {
                    changedNodeIdsByCommit.computeIfAbsent(commit, ignored -> new ArrayList<>()).add(nodeId);
                    changedPathsByCommit.computeIfAbsent(commit, ignored -> new LinkedHashSet<>()).add(file);
                }

                JsonObject flow = new JsonObject();
                flow.addProperty("id", flowId);
                flow.addProperty("title", fallback(string(step, "title"), string(step, "symbol")));
                flow.addProperty("summary", fallback(string(step, "summary"), string(step, "responsibility")));
                flow.addProperty("kind", normalizeKind(string(step, "kind")));
                flow.addProperty("lane_id", lane.id);
                flow.addProperty("change_status", changed ? "changed" : normalizeChangeStatus(string(step, "change_status")));
                flow.add("commit_ids", strings(changed ? stepCommits : groupCommitIds));
                JsonObject detail = new JsonObject();
                detail.addProperty("before", fallback(string(group, "before"), "未在证据中确认"));
                detail.addProperty("after", fallback(string(group, "after"), string(step, "summary")));
                detail.addProperty("reason", fallback(string(group, "reason"), "所选提交引入"));
                detail.addProperty("impact", fallback(string(group, "impact"), string(group, "summary")));
                detail.add("commit_ids", strings(changed ? stepCommits : groupCommitIds));
                flow.add("change_detail", detail);
                flow.add("children", new JsonArray());
                flow.add("branches", new JsonArray());
                flow.add("contract_in_ids", new JsonArray());
                flow.add("contract_out_ids", new JsonArray());
                flow.add("source_node_ids", strings(nodeId));
                flow.add("business_rules", new JsonArray());
                flowSteps.add(flow);
                stepFlows.add(flow);
                stepLaneIds.add(lane.id);

                if (stepIndex > 0) {
                    JsonObject previousStep = stepObjects.get(stepIndex - 1);
                    String edgeId = "edge-" + (groupIndex + 1) + "-" + stepIndex;
                    JsonObject edge = new JsonObject();
                    edge.addProperty("id", edgeId);
                    edge.addProperty("source", "node-" + (groupIndex + 1) + "-" + stepIndex);
                    edge.addProperty("target", nodeId);
                    edge.add("feature_ids", strings(groupId));
                    JsonObject edgeRoles = new JsonObject();
                    edgeRoles.addProperty(groupId, "core");
                    edge.add("feature_roles", edgeRoles);
                    edge.add("scenario_ids", new JsonArray());
                    edge.addProperty("number", String.valueOf(stepIndex));
                    edge.addProperty("kind", normalizeRelation(string(step, "relation_kind")));
                    edge.addProperty("label", fallback(string(step, "relation_label"), "进入下一步骤"));
                    edge.addProperty("payload", strings(array(previousStep, "outputs")).stream().findFirst().orElse(""));
                    edge.addProperty("meaning", fallback(string(step, "relation_label"), string(step, "summary")));
                    edge.addProperty("evidence_kind", "inferred");
                    edge.addProperty("confidence", normalizeConfidence(string(step, "confidence")));
                    edges.add(edge);

                    String previousLane = stepLaneIds.get(stepIndex - 1);
                    if (!previousLane.equals(lane.id)) {
                        String contractId = "contract-" + (groupIndex + 1) + "-" + stepIndex;
                        JsonObject contract = new JsonObject();
                        contract.addProperty("id", contractId);
                        contract.addProperty("name", fallback(string(step, "relation_label"), "跨模块调用"));
                        contract.addProperty("source_lane_id", previousLane);
                        contract.addProperty("target_lane_id", lane.id);
                        contract.addProperty("kind", normalizeRelation(string(step, "relation_kind")));
                        contract.addProperty("payload", edge.get("payload").getAsString());
                        contract.addProperty("meaning", edge.get("meaning").getAsString());
                        contract.addProperty("lifecycle", "本次业务步骤内");
                        contract.add("source_node_ids", strings("node-" + (groupIndex + 1) + "-" + stepIndex, nodeId));
                        contracts.add(contract);
                        stepFlows.get(stepIndex - 1).getAsJsonArray("contract_out_ids").add(contractId);
                        flow.getAsJsonArray("contract_in_ids").add(contractId);
                    }
                }
            }
            nodeIdsByGroup.put(sourceGroupId, List.copyOf(groupNodeIds));
            if (!stepFlows.isEmpty()) {
                JsonObject groupFlow = new JsonObject();
                groupFlow.addProperty("id", "flow-group-" + (groupIndex + 1));
                groupFlow.addProperty("title", fallback(string(group, "title"), "改动主题"));
                groupFlow.addProperty("summary", fallback(string(group, "summary"), string(group, "impact")));
                groupFlow.addProperty("kind", "stage");
                groupFlow.addProperty("lane_id", stepLaneIds.get(0));
                groupFlow.addProperty("change_status", "affected");
                groupFlow.addProperty("flow_scope", "business");
                groupFlow.add("commit_ids", strings(groupCommitIds));
                JsonObject groupDetail = new JsonObject();
                groupDetail.addProperty("before", fallback(string(group, "before"), "未在证据中确认"));
                groupDetail.addProperty("after", fallback(string(group, "after"), string(group, "summary")));
                groupDetail.addProperty("reason", fallback(string(group, "reason"), "所选提交引入"));
                groupDetail.addProperty("impact", fallback(string(group, "impact"), string(group, "summary")));
                groupDetail.add("commit_ids", strings(groupCommitIds));
                groupFlow.add("change_detail", groupDetail);
                groupFlow.add("children", flowSteps);
                groupFlow.add("branches", new JsonArray());
                groupFlow.add("contract_in_ids", new JsonArray());
                groupFlow.add("contract_out_ids", new JsonArray());
                groupFlow.add("source_node_ids", new JsonArray());
                groupFlow.add("business_rules", new JsonArray());
                flowGroups.add(groupFlow);
            }
        }

        JsonObject design = new JsonObject();
        JsonArray principles = new JsonArray();
        principles.add("报告以所选提交的聚合净差异为中心，独立改动主题并列展示。");
        principles.add("只保留解释修改前后行为所需的最短证据路径。");
        design.add("principles", principles);
        JsonArray laneArray = new JsonArray();
        lanes.values().forEach(lane -> laneArray.add(lane.toJson()));
        design.add("lanes", laneArray);
        design.add("contracts", contracts);
        design.add("risks", new JsonArray());
        report.add("architecture_design", design);

        JsonObject flowRoot = new JsonObject();
        flowRoot.addProperty("id", "root");
        flowRoot.addProperty("title", string(report, "title"));
        flowRoot.addProperty("summary", string(report, "summary"));
        flowRoot.addProperty("execution", "independent");
        flowRoot.add("children", flowGroups);
        flowRoot.add("source_node_ids", new JsonArray());
        flowRoot.add("contract_in_ids", new JsonArray());
        flowRoot.add("contract_out_ids", new JsonArray());
        flowRoot.add("branches", new JsonArray());
        report.add("flow_map", flowRoot);

        JsonObject scope = new JsonObject();
        scope.addProperty("mode", "selected_commits");
        scope.addProperty("base_commit", evidence.baseCommit());
        scope.addProperty("target_commit", evidence.targetCommit());
        scope.addProperty("changed_path_count", evidence.aggregateChangedPaths().size());
        report.add("scope", scope);
        report.add("comparison", comparison(evidence));
        report.add("features", features);
        report.add("services", new JsonArray());
        report.add("nodes", nodes);
        report.add("edges", edges);
        report.add("scenarios", new JsonArray());
        report.add("data_structures", new JsonArray());
        report.add("tables", new JsonArray());
        report.add("evidence", new JsonArray());
        report.add("unknowns", copy(array(analysis, "unknowns")));
        report.add("commit_evolution", commitEvolution(analysis, evidence, changedNodeIdsByCommit, changedPathsByCommit));
        report.add("review_findings", findings(analysis, nodeIdsByGroup, evidence));

        JsonObject summary = new JsonObject();
        summary.addProperty("headline", string(report, "title"));
        summary.addProperty("before", join(groupObjects, "before"));
        summary.addProperty("after", join(groupObjects, "after"));
        summary.addProperty("business_impact", join(groupObjects, "impact"));
        report.add("change_summary", summary);
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
            throw new ModelClientException("Codex 没有返回合法的闭合分析 JSON：" + exception.getMessage(), exception);
        }
    }

    private Map<String, Set<String>> changedCommitsByPath(EvidencePack evidence) {
        Map<String, Set<String>> result = new HashMap<>();
        for (EvidencePack.CommitEvidence commit : evidence.commits()) {
            for (String path : commit.changedPaths()) {
                result.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(commit.commit().hash());
            }
        }
        return result;
    }

    private JsonObject comparison(EvidencePack evidence) {
        JsonObject comparison = new JsonObject();
        comparison.addProperty("mode", "selected_commits");
        comparison.add("selected_commits", strings(evidence.commits().stream().map(item -> item.commit().hash()).toList()));
        comparison.addProperty("target_commit", evidence.targetCommit());
        comparison.addProperty("target_tree", evidence.targetTree());
        comparison.addProperty("fingerprint", evidence.fingerprint());
        return comparison;
    }

    private JsonArray commitEvolution(
            JsonObject analysis,
            EvidencePack evidence,
            Map<String, List<String>> nodesByCommit,
            Map<String, LinkedHashSet<String>> pathsByCommit
    ) {
        Map<String, JsonObject> notes = new HashMap<>();
        for (JsonObject note : objects(array(analysis, "commit_notes"))) notes.put(string(note, "commit"), note);
        JsonArray result = new JsonArray();
        for (EvidencePack.CommitEvidence commit : evidence.commits()) {
            String hash = commit.commit().hash();
            JsonObject note = notes.getOrDefault(hash, new JsonObject());
            JsonObject item = new JsonObject();
            item.addProperty("commit", hash);
            item.addProperty("subject", commit.commit().subject());
            item.addProperty("business_purpose", fallback(string(note, "business_purpose"), commit.commit().subject()));
            item.addProperty("architecture_effect", fallback(string(note, "architecture_effect"), "修改现有业务行为"));
            item.add("affected_node_ids", strings(nodesByCommit.getOrDefault(hash, List.of())));
            item.add("evidence_paths", strings(pathsByCommit.getOrDefault(hash, new LinkedHashSet<>())));
            result.add(item);
        }
        return result;
    }

    private JsonArray findings(JsonObject analysis, Map<String, List<String>> nodeIdsByGroup, EvidencePack evidence) {
        Set<String> allowedPaths = new LinkedHashSet<>(evidence.targetManifest());
        JsonArray result = new JsonArray();
        List<JsonObject> findings = objects(array(analysis, "findings"));
        for (int index = 0; index < findings.size(); index++) {
            JsonObject source = findings.get(index);
            JsonObject finding = new JsonObject();
            finding.addProperty("id", "finding-" + (index + 1));
            finding.addProperty("severity", normalizeSeverity(string(source, "severity")));
            finding.addProperty("title", fallback(string(source, "title"), "审核发现"));
            finding.addProperty("meaning", fallback(string(source, "meaning"), string(source, "title")));
            LinkedHashSet<String> affected = new LinkedHashSet<>();
            for (String group : strings(array(source, "group_ids"))) affected.addAll(nodeIdsByGroup.getOrDefault(group, List.of()));
            finding.add("affected_node_ids", strings(affected));
            finding.add("evidence_paths", strings(strings(array(source, "evidence_paths")).stream().filter(allowedPaths::contains).toList()));
            finding.addProperty("confidence", normalizeConfidence(string(source, "confidence")));
            result.add(finding);
        }
        return result;
    }

    private List<String> filteredStrings(JsonArray values, Set<String> allowed) {
        return strings(values).stream().filter(allowed::contains).distinct().toList();
    }

    private String join(List<JsonObject> groups, String field) {
        return groups.stream().map(group -> string(group, field)).filter(value -> value != null && !value.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining("；"));
    }

    private String normalizeEvidence(String value) {
        return value != null && Set.of("direct_source", "source_backed_walkthrough", "inferred").contains(value) ? value : "inferred";
    }

    private String normalizeConfidence(String value) {
        return value != null && Set.of("high", "medium", "low").contains(value) ? value : "medium";
    }

    private String normalizeKind(String value) {
        return value != null && Set.of("stage", "decision", "success", "failure").contains(value) ? value : "stage";
    }

    private String normalizeChangeStatus(String value) {
        return value != null && Set.of("changed", "affected", "context").contains(value) ? value : "context";
    }

    private String normalizeRelation(String value) {
        return value != null && Set.of("call", "data", "http", "websocket", "event", "sql", "transaction").contains(value) ? value : "call";
    }

    private String normalizeSeverity(String value) {
        return value != null && Set.of("critical", "high", "medium", "low", "info").contains(value) ? value : "info";
    }

    private String moduleFromPath(String path) {
        if (path == null || path.isBlank()) return "变更实现";
        String normalized = path.replace('\\', '/');
        String[] parts = normalized.split("/");
        if (parts.length >= 3) return parts[parts.length - 3] + "/" + parts[parts.length - 2];
        if (parts.length >= 2) return parts[parts.length - 2];
        return "变更实现";
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }

    private int integer(JsonObject object, String name, int fallback) {
        try {
            return object != null && object.has(name) ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private JsonArray array(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : null;
    }

    private JsonArray copy(JsonArray array) {
        return array == null ? new JsonArray() : array.deepCopy();
    }

    private List<JsonObject> objects(JsonArray array) {
        if (array == null) return List.of();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : array) if (item.isJsonObject()) result.add(item.getAsJsonObject());
        return result;
    }

    private List<String> strings(JsonArray array) {
        if (array == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement item : array) if (item.isJsonPrimitive()) result.add(item.getAsString());
        return result;
    }

    private JsonArray strings(String... values) {
        return strings(List.of(values));
    }

    private JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static final class Lane {
        private final String id;
        private final String name;
        private String role = "承载本次改动相关的业务职责";
        private final LinkedHashSet<String> responsibilities = new LinkedHashSet<>();
        private final LinkedHashSet<String> receives = new LinkedHashSet<>();
        private final LinkedHashSet<String> produces = new LinkedHashSet<>();
        private final LinkedHashSet<String> nodeIds = new LinkedHashSet<>();

        private Lane(String id, String name) {
            this.id = id;
            this.name = name;
        }

        private void addNode(String nodeId, String responsibility, String moduleRole, List<String> inputs, List<String> outputs) {
            nodeIds.add(nodeId);
            if (responsibility != null && !responsibility.isBlank()) responsibilities.add(responsibility);
            if (moduleRole != null && !moduleRole.isBlank()) role = moduleRole;
            receives.addAll(inputs);
            produces.addAll(outputs);
        }

        private JsonObject toJson() {
            JsonObject lane = new JsonObject();
            lane.addProperty("id", id);
            lane.addProperty("name", name);
            lane.addProperty("code_label", name);
            lane.addProperty("represents", role);
            lane.add("responsibilities", jsonStrings(responsibilities));
            lane.addProperty("why_here", "这些源码节点直接承担本次聚合变更中的该项职责。");
            lane.add("receives", jsonStrings(receives));
            lane.add("produces", jsonStrings(produces));
            lane.add("not_responsible", new JsonArray());
            lane.add("source_node_ids", jsonStrings(nodeIds));
            return lane;
        }

        private JsonArray jsonStrings(Iterable<String> values) {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            return array;
        }
    }
}
