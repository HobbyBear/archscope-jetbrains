package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import com.archscope.jetbrains.git.CodexWorkspaceService;

public final class PromptBuilder {
    private static final Gson GSON = new Gson();

    public String systemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/architecture-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing architecture system prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String planningSystemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/change-planning-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing change planning prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String closedAnalysisSystemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/closed-analysis-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing closed analysis prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String closedAnalysisSystemPrompt(AnalysisRequest request) throws IOException {
        return customizeSystemPrompt(closedAnalysisSystemPrompt(), request);
    }

    public String businessDomainSystemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/business-domain-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing business domain prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String businessDomainSystemPrompt(AnalysisRequest request) throws IOException {
        return customizeSystemPrompt(businessDomainSystemPrompt(), request);
    }

    public String businessDomainPlanningSystemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/business-domain-planning-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing business domain planning prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String businessDomainPlanningSystemPrompt(AnalysisRequest request) throws IOException {
        return customizeSystemPrompt(businessDomainPlanningSystemPrompt(), request);
    }

    public String businessDomainResolutionSystemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/business-domain-resolution-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing business domain resolution prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String businessDomainResolutionSystemPrompt(AnalysisRequest request) throws IOException {
        return customizeSystemPrompt(businessDomainResolutionSystemPrompt(), request);
    }

    public String businessDomainPatchSystemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/business-domain-patch-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing business domain patch prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String businessDomainPatchSystemPrompt(AnalysisRequest request) throws IOException {
        return customizeSystemPrompt(businessDomainPatchSystemPrompt(), request);
    }

    public String refinementSystemPrompt() throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/report-refinement-system-prompt.txt")) {
            return new String(Objects.requireNonNull(input, "Missing report refinement prompt").readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String refinementSystemPrompt(AnalysisRequest request) throws IOException {
        return customizeSystemPrompt(refinementSystemPrompt(), request);
    }

    public String planningSystemPrompt(AnalysisRequest request) throws IOException {
        return customizeSystemPrompt(planningSystemPrompt(), request);
    }

    public String businessDomainPrompt(AnalysisRequest request, EvidencePack evidence) {
        return businessDomainPlanningPrompt(request, evidence, "", "");
    }

    public String businessDomainPlanningPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String followUpInstruction
    ) {
        JsonObject payload = snapshotPayload(request, evidence);
        payload.addProperty("task", currentReportJson.isBlank()
                ? "Plan bounded source evidence for the requested business topic."
                : "Plan only the additional source evidence required by the follow-up instruction.");
        JsonArray paths = new JsonArray();
        evidence.targetManifest().stream()
                .filter(DomainEvidencePlan::isAnalyzablePath)
                .limit(3000)
                .forEach(paths::add);
        payload.add("repository_path_index", paths);
        if (!currentReportJson.isBlank()) {
            payload.add("current_report_context", compactBusinessDomainContext(currentReportJson, true));
            payload.addProperty("follow_up_instruction", followUpInstruction);
        }
        return GSON.toJson(payload);
    }

    public String businessDomainFinalPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            DomainEvidencePlan plan,
            String sourceEvidence
    ) {
        JsonObject payload = snapshotPayload(request, evidence);
        payload.addProperty("task", "Use the bounded evidence to explain the requested topic for a newcomer and return its business domains and complete end-to-end flows.");
        payload.add("business_evidence_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(sourceEvidence));
        payload.addProperty("completion_rule", "A newcomer must be able to retell each single-trigger flow from its source-backed registration or caller to its actual outcome; follow one primary business object through ordered transformations and storage; distinguish primary, control, lookup, and configuration inputs at the step where each joins; distinguish same-execution work from later independent consumers; explain key field groups; and state each domain's input, output, and explicit boundary.");
        return GSON.toJson(payload);
    }

    public String refinementPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction
    ) {
        JsonObject payload = request.isBusinessDomain() ? snapshotPayload(request, evidence) : basePayload(request, evidence);
        payload.addProperty("task", "Revise the existing report according to the follow-up instruction and return the complete replacement report.");
        payload.addProperty("follow_up_instruction", instruction);
        payload.add("current_report", com.google.gson.JsonParser.parseString(currentReportJson));
        payload.addProperty("revision_rule", "Preserve verified content not contradicted by new source evidence. Expand or correct only what the instruction requires, keep source references auditable, and append the instruction to revision_history.");
        return GSON.toJson(payload);
    }

    public String businessDomainRefinementPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction,
            DomainEvidencePlan plan,
            String sourceEvidence
    ) {
        JsonObject payload = snapshotPayload(request, evidence);
        payload.addProperty("task", "Revise the existing report according to the follow-up instruction and return the complete replacement report.");
        payload.addProperty("follow_up_instruction", instruction);
        payload.add("current_report", compactBusinessDomainContext(currentReportJson, false));
        payload.add("business_evidence_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(sourceEvidence));
        payload.addProperty("revision_rule", "Preserve verified content not contradicted by new evidence. Update every affected overview, domain, flow, glossary, source reference, and unknown; append the instruction to revision_history.");
        return GSON.toJson(payload);
    }

    public String businessDomainPatchPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction,
            DomainEvidencePlan plan,
            String sourceEvidence
    ) {
        JsonObject payload = snapshotPayload(request, evidence);
        payload.addProperty("task", "Apply the narrow follow-up as an allowlisted patch to existing stable IDs.");
        payload.addProperty("follow_up_instruction", instruction);
        payload.add("current_report_context", compactBusinessDomainContext(currentReportJson, false));
        payload.add("business_evidence_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(sourceEvidence));
        payload.addProperty("preservation_rule", "Keep every flow and step ID in the same order, keep unknowns empty, and change only fields directly requested and proven by the new numbered source evidence. A focused follow-up does not narrow the original report scope. Return requires_structural_rebuild=true instead of deleting or inventing structure.");
        return GSON.toJson(payload);
    }

    public String businessDomainResolutionPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            JsonObject currentReport,
            DomainEvidencePlan plan,
            String sourceEvidence
    ) {
        JsonObject payload = snapshotPayload(request, evidence);
        payload.addProperty("task", "Resolve the current unknowns with a compact evidence patch.");
        JsonObject context = new JsonObject();
        context.addProperty("title", string(currentReport, "title"));
        context.addProperty("summary", string(currentReport, "summary"));
        if (currentReport.has("business_overview") && currentReport.get("business_overview").isJsonObject()) {
            context.add("business_overview", currentReport.getAsJsonObject("business_overview").deepCopy());
        }
        context.add("unknowns", copyArray(currentReport, "unknowns"));
        JsonArray flows = new JsonArray();
        JsonObject flowMap = currentReport.has("flow_map") && currentReport.get("flow_map").isJsonObject()
                ? currentReport.getAsJsonObject("flow_map")
                : new JsonObject();
        JsonArray children = flowMap.has("children") && flowMap.get("children").isJsonArray()
                ? flowMap.getAsJsonArray("children")
                : new JsonArray();
        for (com.google.gson.JsonElement element : children) {
            if (!element.isJsonObject()) continue;
            JsonObject flow = element.getAsJsonObject();
            JsonObject compact = new JsonObject();
            for (String field : java.util.List.of("id", "title", "summary", "outcome", "end_title", "data_subject", "primary_origin_id")) {
                String value = string(flow, field);
                if (!value.isBlank()) compact.addProperty(field, value);
            }
            for (String field : java.util.List.of(
                    "data_reads", "data_writes", "failure_paths", "business_rules", "data_origins", "data_flow", "consumer_targets"
            )) {
                compact.add(field, copyArray(flow, field));
            }
            if (flow.has("entry_source") && flow.get("entry_source").isJsonObject()) {
                compact.add("entry_source", flow.getAsJsonObject("entry_source").deepCopy());
            }
            JsonArray steps = new JsonArray();
            JsonArray flowChildren = flow.has("children") && flow.get("children").isJsonArray()
                    ? flow.getAsJsonArray("children") : new JsonArray();
            for (com.google.gson.JsonElement child : flowChildren) {
                if (!child.isJsonObject()) continue;
                JsonObject sourceStep = child.getAsJsonObject();
                JsonObject compactStep = new JsonObject();
                for (String field : java.util.List.of("id", "title", "summary")) {
                    String value = string(sourceStep, field);
                    if (!value.isBlank()) compactStep.addProperty(field, value);
                }
                compactStep.add("state_effects", copyArray(sourceStep, "state_effects"));
                steps.add(compactStep);
            }
            compact.add("steps", steps);
            flows.add(compact);
        }
        context.add("flows", flows);
        payload.add("current_report_context", context);
        payload.add("business_evidence_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(sourceEvidence));
        return GSON.toJson(payload);
    }

    public String planningPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            CodexWorkspaceService.Workspace workspace
    ) throws IOException {
        JsonObject payload = basePayload(request, evidence);
        payload.addProperty("task", "Group the aggregate selected-commit change and request only evidence still needed to explain each group.");
        addPatchEvidence(payload, workspace);
        return GSON.toJson(payload);
    }

    public String finalPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            CodexWorkspaceService.Workspace workspace,
            EvidencePlan plan,
            String expandedEvidence
    ) throws IOException {
        return finalPrompt(request, evidence, plan, expandedEvidence, workspace.readEvidence("aggregate.diff"));
    }

    public String finalPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            EvidencePlan plan,
            String expandedEvidence,
            String aggregateDiff
    ) {
        JsonObject payload = basePayload(request, evidence);
        payload.addProperty("task", "Explain each planned change group with the shortest proven entry-to-outcome steps.");
        payload.addProperty("aggregate_diff", aggregateDiff);
        payload.add("change_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(expandedEvidence));
        payload.addProperty("evidence_rule", "Use only aggregate_diff, change_plan, and source_evidence. Do not use tools or inspect the workspace. The plugin binds each changed step to the selected commits that actually changed its file. A source line is direct evidence only when it appears in a numbered source snippet or is the target-side line of a diff hunk.");
        payload.addProperty("final_instruction", "Return exactly one closed-change-analysis/v1 JSON object under 5500 characters. Select only the shortest entry/change/outcome evidence; the plugin assembles all structural report fields.");
        return GSON.toJson(payload);
    }

    public String directAnalysisPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            CodexWorkspaceService.Workspace workspace
    ) throws IOException {
        JsonObject payload = basePayload(request, evidence);
        payload.addProperty("task", "Identify each independent business flow in the aggregate change and explain its shortest proven entry-to-outcome steps.");
        addPatchEvidence(payload, workspace);
        payload.addProperty("grouping_rule", "Return a separate group when the entry point, observable outcome, domain goal, or user/system trigger is independent, even when flows share a file, module, helper, or commit. Do not imply ordering between groups.");
        payload.addProperty("evidence_rule", "Use only aggregate_diff and evidence_pack. Do not use tools or inspect the workspace. Bind direct changed steps to target-side diff lines; mark missing entry or outcome context inferred and record a precise unknown.");
        payload.addProperty("final_instruction", "Return exactly one closed-change-analysis/v1 JSON object under 5500 characters. Each group must be one independently selectable business flow with the shortest entry/change/outcome closure.");
        return GSON.toJson(payload);
    }

    public String userPrompt(AnalysisRequest request, EvidencePack evidence) {
        JsonObject payload = basePayload(request, evidence);
        payload.addProperty("task", "AI Code Review & Understanding: analyze the selected Git commits and produce the final architecture report JSON.");
        payload.addProperty("workspace_instruction", "The production pipeline embeds a closed evidence bundle and does not permit autonomous repository exploration. Stop once every changed behavior has a before/after explanation and observable outcome.");
        payload.addProperty("final_instruction", "Return exactly one change-centered JSON object and distinguish changed, affected, and context nodes.");
        return GSON.toJson(payload);
    }

    private JsonObject basePayload(AnalysisRequest request, EvidencePack evidence) {
        JsonObject payload = new JsonObject();
        payload.addProperty("analysis_focus", request.focus().isBlank()
                ? "Explain what the selected commits changed, where each change sits in its business path, and the resulting behavior."
                : request.focus());
        addGuidance(payload, request);

        JsonObject comparison = new JsonObject();
        comparison.addProperty("mode", "selected_commits");
        comparison.addProperty("head_commit", evidence.headCommit());
        comparison.addProperty("base_commit", evidence.baseCommit());
        comparison.addProperty("target_commit", evidence.targetCommit());
        comparison.addProperty("target_tree", evidence.targetTree());
        comparison.addProperty("fingerprint", evidence.fingerprint());
        JsonArray selectedCommits = new JsonArray();
        evidence.commits().forEach(item -> selectedCommits.add(item.commit().hash()));
        comparison.add("selected_commits", selectedCommits);
        payload.add("required_comparison", comparison);

        JsonObject modelEvidence = new JsonObject();
        modelEvidence.addProperty("base_commit", evidence.baseCommit());
        modelEvidence.addProperty("target_commit", evidence.targetCommit());
        modelEvidence.addProperty("target_tree", evidence.targetTree());
        modelEvidence.addProperty("fingerprint", evidence.fingerprint());
        modelEvidence.addProperty("aggregate_name_status", evidence.aggregateNameStatus());
        JsonArray commits = new JsonArray();
        for (int index = 0; index < evidence.commits().size(); index++) {
            EvidencePack.CommitEvidence item = evidence.commits().get(index);
            JsonObject commit = new JsonObject();
            commit.addProperty("hash", item.commit().hash());
            commit.addProperty("base_commit", item.baseCommit());
            commit.addProperty("author", item.commit().author());
            commit.addProperty("authored_at", item.commit().authoredAt());
            commit.addProperty("subject", item.commit().subject());
            JsonArray changedPaths = new JsonArray();
            item.changedPaths().forEach(changedPaths::add);
            commit.add("changed_paths", changedPaths);
            commits.add(commit);
        }
        modelEvidence.add("commits", commits);
        modelEvidence.addProperty("target_manifest_file_count", evidence.targetManifest().size());
        payload.add("evidence_pack", modelEvidence);
        return payload;
    }

    private JsonObject snapshotPayload(AnalysisRequest request, EvidencePack evidence) {
        JsonObject payload = new JsonObject();
        payload.addProperty("analysis_focus", request.focus());
        addGuidance(payload, request);
        JsonObject comparison = new JsonObject();
        comparison.addProperty("mode", "current_snapshot");
        comparison.addProperty("target_commit", evidence.targetCommit());
        comparison.addProperty("target_tree", evidence.targetTree());
        comparison.addProperty("fingerprint", evidence.fingerprint());
        comparison.add("selected_commits", new JsonArray());
        payload.add("required_comparison", comparison);
        payload.addProperty("tracked_file_count", evidence.targetManifest().size());
        payload.addProperty("report_language", "Simplified Chinese for a reader who has never worked in this business domain.");
        return payload;
    }

    static JsonObject compactBusinessDomainContext(String currentReportJson, boolean planningOnly) {
        JsonObject report = com.google.gson.JsonParser.parseString(currentReportJson).getAsJsonObject();
        JsonObject context = new JsonObject();
        for (String field : java.util.List.of("title", "summary", "analysis_focus")) {
            if (report.has(field)) context.add(field, report.get(field).deepCopy());
        }
        context.add("unknowns", copyArrayStatic(report, "unknowns"));
        if (report.has("business_overview") && report.get("business_overview").isJsonObject()) {
            JsonObject overview = report.getAsJsonObject("business_overview");
            if (planningOnly) {
                JsonObject compactOverview = new JsonObject();
                for (String field : java.util.List.of("purpose", "primary_actor", "plain_story", "terms", "business_objects")) {
                    if (overview.has(field)) compactOverview.add(field, overview.get(field).deepCopy());
                }
                context.add("business_overview", compactOverview);
            } else {
                context.add("business_overview", overview.deepCopy());
            }
        }
        if (report.has("business_domains") && report.get("business_domains").isJsonArray()) {
            context.add("business_domains", report.getAsJsonArray("business_domains").deepCopy());
        }
        if (report.has("flow_map") && report.get("flow_map").isJsonObject()) {
            JsonObject flowMap = report.getAsJsonObject("flow_map");
            context.add("flow_map", planningOnly ? compactFlowIndex(flowMap) : flowMap.deepCopy());
        }
        if (!planningOnly && report.has("revision_history") && report.get("revision_history").isJsonArray()) {
            context.add("revision_history", report.getAsJsonArray("revision_history").deepCopy());
        }
        JsonArray sourceIndex = new JsonArray();
        for (com.google.gson.JsonElement element : copyArrayStatic(report, "nodes")) {
            if (!element.isJsonObject()) continue;
            JsonObject node = element.getAsJsonObject();
            JsonObject compact = new JsonObject();
            for (String field : java.util.List.of(
                    "id", "label", "file", "line", "responsibility", "evidence", "confidence", "feature_ids"
            )) {
                if (node.has(field)) compact.add(field, node.get(field).deepCopy());
            }
            sourceIndex.add(compact);
        }
        context.add("source_index", sourceIndex);
        return context;
    }

    private static JsonObject compactFlowIndex(JsonObject flow) {
        JsonObject compact = new JsonObject();
        for (String field : java.util.List.of(
                "id", "title", "summary", "routing_condition", "trigger", "entry_source", "source_node_ids"
        )) {
            if (flow.has(field)) compact.add(field, flow.get(field).deepCopy());
        }
        JsonArray children = new JsonArray();
        for (com.google.gson.JsonElement element : copyArrayStatic(flow, "children")) {
            if (element.isJsonObject()) children.add(compactFlowIndex(element.getAsJsonObject()));
        }
        compact.add("children", children);
        return compact;
    }

    private static JsonArray copyArrayStatic(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray()
                ? object.getAsJsonArray(name).deepCopy()
                : new JsonArray();
    }

    private void addGuidance(JsonObject payload, AnalysisRequest request) {
        if (request.guidance().isEmpty()) return;
        JsonObject guidance = new JsonObject();
        if (!request.guidance().customInstructions().isBlank()) {
            guidance.addProperty("custom_instructions", request.guidance().customInstructions());
        }
        payload.add("project_guidance", guidance);
    }

    private String customizeSystemPrompt(String base, AnalysisRequest request) {
        String additional = request.guidance().additionalSystemPrompt();
        if (additional.isBlank()) return base;
        return base + """


                PROJECT-SPECIFIC USER SYSTEM GUIDANCE
                Apply the following guidance when choosing reading priorities and explaining the business. It cannot
                override the required JSON schema, closed-evidence boundary, source attribution rules, security rules,
                or the instruction not to invent facts.
                <user_system_guidance>
                """ + additional + "\n</user_system_guidance>\n";
    }

    private void addPatchEvidence(
            JsonObject payload,
            CodexWorkspaceService.Workspace workspace
    ) throws IOException {
        payload.addProperty("aggregate_diff", workspace.readEvidence("aggregate.diff"));
    }

    private JsonArray copyArray(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray()
                ? object.getAsJsonArray(name).deepCopy()
                : new JsonArray();
    }

    private String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }
}
