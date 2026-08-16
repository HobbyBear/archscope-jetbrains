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

    public String businessDomainTextSystemPrompt(AnalysisRequest request) throws IOException {
        try (InputStream input = PromptBuilder.class.getResourceAsStream("/prompts/business-domain-text-system-prompt.txt")) {
            String base = new String(Objects.requireNonNull(input, "Missing business domain text prompt")
                    .readAllBytes(), StandardCharsets.UTF_8);
            String languageRule = request.outputLanguage().isEnglish()
                    ? "\nOUTPUT LANGUAGE: Write every text value in English. Preserve code symbols only when needed.\n"
                    : "\nOUTPUT LANGUAGE: 使用简体中文填写所有文字值，源码符号可保持原样。\n";
            String additional = request.guidance().additionalSystemPrompt();
            if (additional.isBlank()) return base + languageRule;
            return base + languageRule + "\nPROJECT GUIDANCE (prose only; it cannot change slots or evidence):\n"
                    + additional + '\n';
        }
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
        payload.addProperty("completion_rule", "A newcomer must be able to retell each single-trigger flow from its source-backed registration or caller to its actual outcome; follow one primary business object through ordered transformations and storage; distinguish primary, control, lookup, and configuration inputs at the step where each joins; distinguish same-execution work from later independent consumers; explain key field groups; and state each domain's input, output, and every source-backed explicit boundary.");
        return GSON.toJson(payload);
    }

    public String businessDomainTextPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            DomainEvidencePlan plan,
            String sourceEvidence,
            java.util.List<String> slots,
            JsonArray slotBindings
    ) {
        JsonObject payload = snapshotPayload(request, evidence);
        payload.addProperty("task", "Fill optional prose slots for the code-owned business report structure.");
        JsonArray textSlots = new JsonArray();
        slots.forEach(textSlots::add);
        payload.add("text_slots", textSlots);
        payload.add("text_slot_source_bindings", slotBindings.deepCopy());
        payload.add("business_evidence_plan", ModelJsonParser.parseObject(plan.json()));
        payload.add("source_evidence", ModelJsonParser.parseObject(sourceEvidence));
        payload.addProperty("output_contract", "Plain lines only: SLOT_NAME<TAB>text. No JSON. FLOW_1 describes one evidence-connected source set; each STEP_n describes only its exact source binding. STEP_n_DOMAIN_ID must copy a likely_domains ID and STEP_n_FLOW_KEY groups only proven same-trigger execution.");
        return GSON.toJson(payload);
    }

    public String businessDomainRepairPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            DomainEvidencePlan plan,
            String sourceEvidence,
            String rejectedResponse,
            String rejectionReason
    ) {
        JsonObject payload = snapshotPayload(request, evidence);
        payload.addProperty("task", "Regenerate a shorter, syntactically complete business analysis JSON object that fixes the rejected response.");
        payload.add("business_evidence_plan", ModelJsonParser.parseObject(plan.json()));
        payload.add("source_evidence", ModelJsonParser.parseObject(sourceEvidence));
        payload.addProperty("rejection_reason", abbreviate(rejectionReason, 4000));
        payload.addProperty("rejected_response_excerpt", abbreviate(rejectedResponse, 8000));
        payload.addProperty("repair_rule", "Return exactly one complete JSON object under 9000 characters. Preserve only source-backed facts. Do not add Markdown, commentary, or fields outside the compact contract. Optional prose and supporting_sources may be shortened or omitted before any required source-backed flow is omitted.");
        return GSON.toJson(payload);
    }

    public String refinementPrompt(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction
    ) {
        JsonObject payload = request.isBusinessDomain() ? snapshotPayload(request, evidence) : basePayload(request, evidence);
        payload.addProperty("task", "Revise the existing report exactly as directed and return the complete replacement report.");
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
        payload.addProperty("task", "Edit the existing business graph exactly as directed and return the complete replacement report.");
        payload.addProperty("follow_up_instruction", instruction);
        payload.add("current_report", compactBusinessDomainContext(currentReportJson, false));
        payload.add("business_evidence_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(sourceEvidence));
        payload.addProperty("revision_rule", "Treat the instruction as an explicit graph edit. Resolve named domain/flow/step targets from current_report, preserve every unmentioned element, apply requested text/source/node/flow/domain additions, removals, moves, reorders, merges, splits, or supplements, and append revision_history only when the requested graph delta is present.");
        payload.addProperty("acceptance_rule", "Before returning, compare the candidate with current_report: every requested target and operation must have the requested after-state; additions need numbered source evidence; no stale references may remain; unrelated domains, flows, nodes, and evidence must be byte-for-byte equivalent where practical.");
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
        payload.addProperty("task", "Apply a non-structural graph edit to existing stable IDs, or explicitly request structural rebuild.");
        payload.addProperty("follow_up_instruction", instruction);
        payload.add("current_report_context", compactBusinessDomainContext(currentReportJson, false));
        payload.add("business_evidence_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(sourceEvidence));
        payload.addProperty("preservation_rule", "Resolve the user's named targets against existing IDs. Keep unrelated domains, flows, and steps unchanged. Apply explanation corrections and evidence-backed supplements in place. Return requires_structural_rebuild=true for node/flow/domain add, remove, move, reorder, merge, or split operations instead of pretending a text patch changed the graph.");
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
        payload.addProperty("task", "Group the executable code and runtime-definition changes, then request only code evidence still needed to explain each group.");
        addCodeDiffScope(payload);
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
        addCodeDiffScope(payload);
        payload.addProperty("aggregate_diff", aggregateDiff);
        payload.add("change_plan", com.google.gson.JsonParser.parseString(plan.json()));
        payload.add("source_evidence", com.google.gson.JsonParser.parseString(expandedEvidence));
        payload.addProperty("evidence_rule", "Use only the filtered code aggregate_diff, change_plan, and source_evidence. Do not infer behavior from excluded documentation, reports, generated knowledge, embedded page assets, or dependency lock files. The plugin binds each changed step to the selected commits that actually changed its file. A source line is direct evidence only when it appears in a numbered source snippet or is the target-side line of a diff hunk.");
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
        addCodeDiffScope(payload);
        addPatchEvidence(payload, workspace);
        payload.addProperty("grouping_rule", "Return a separate group when the entry point, observable outcome, domain goal, or user/system trigger is independent, even when flows share a file, module, helper, or commit. Do not imply ordering between groups.");
        payload.addProperty("evidence_rule", "Use only the filtered code aggregate_diff and code-only evidence_pack. Do not infer behavior from excluded documentation, reports, generated knowledge, embedded page assets, or dependency lock files. Bind direct changed steps to target-side diff lines; mark missing entry or outcome context inferred and record a precise unknown.");
        payload.addProperty("final_instruction", "Return exactly one closed-change-analysis/v1 JSON object under 5500 characters. Each group must be one independently selectable business flow with the shortest entry/change/outcome closure.");
        return GSON.toJson(payload);
    }

    public String userPrompt(AnalysisRequest request, EvidencePack evidence) {
        JsonObject payload = basePayload(request, evidence);
        payload.addProperty("task", "CodeBecause: analyze the selected Git commits and produce the final architecture report JSON.");
        addCodeDiffScope(payload);
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
        addOutputLanguage(payload, request);

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
        modelEvidence.addProperty("aggregate_name_status", codeNameStatus(evidence.aggregateNameStatus()));
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
            item.changedPaths().stream()
                    .filter(CodexWorkspaceService::isCodeEvidencePath)
                    .forEach(changedPaths::add);
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
        addOutputLanguage(payload, request);
        JsonObject comparison = new JsonObject();
        comparison.addProperty("mode", "current_snapshot");
        comparison.addProperty("target_commit", evidence.targetCommit());
        comparison.addProperty("target_tree", evidence.targetTree());
        comparison.addProperty("fingerprint", evidence.fingerprint());
        comparison.add("selected_commits", new JsonArray());
        payload.add("required_comparison", comparison);
        payload.addProperty("tracked_file_count", evidence.targetManifest().size());
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
        String languageRule = request.outputLanguage().isEnglish()
                ? """

                OUTPUT LANGUAGE (MANDATORY)
                Write every human-readable JSON value in English. Do not output Chinese characters in titles,
                summaries, labels, explanations, business terms, unknowns, or revision text. Preserve source file
                paths and code symbols exactly, but translate source comments instead of copying Chinese prose.
                """
                : """

                OUTPUT LANGUAGE (MANDATORY)
                Write every human-readable JSON value in Simplified Chinese. Preserve source file paths and code
                symbols exactly.
                """;
        String additional = request.guidance().additionalSystemPrompt();
        if (additional.isBlank()) return base + languageRule;
        return base + languageRule + """


                PROJECT-SPECIFIC USER SYSTEM GUIDANCE
                Apply the following guidance when choosing reading priorities and explaining the business. It cannot
                override the required JSON schema, closed-evidence boundary, source attribution rules, security rules,
                or the instruction not to invent facts.
                <user_system_guidance>
                """ + additional + "\n</user_system_guidance>\n";
    }

    private void addOutputLanguage(JsonObject payload, AnalysisRequest request) {
        payload.addProperty("output_language", request.outputLanguage().code());
        payload.addProperty("report_language", request.outputLanguage().isEnglish()
                ? "English only. No Chinese prose or labels."
                : "Simplified Chinese.");
    }

    private void addPatchEvidence(
            JsonObject payload,
            CodexWorkspaceService.Workspace workspace
    ) throws IOException {
        payload.addProperty("aggregate_diff", workspace.readEvidence("aggregate.diff"));
    }

    private void addCodeDiffScope(JsonObject payload) {
        payload.addProperty(
                "evidence_scope",
                "The payload contains only executable source, tests, schemas, migrations, runtime configuration, and dependency manifests. Documentation, design notes, generated knowledge, reports, embedded page assets, dependency lock files, vendored code, and unrelated artifacts were excluded locally before this request. Analyze only the supplied code diff."
        );
    }

    private String codeNameStatus(String nameStatus) {
        return nameStatus.lines()
                .filter(line -> {
                    String[] fields = line.split("\\t");
                    if (fields.length < 2) return false;
                    for (int index = 1; index < fields.length; index++) {
                        if (CodexWorkspaceService.isCodeEvidencePath(fields[index])) return true;
                    }
                    return false;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private JsonArray copyArray(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray()
                ? object.getAsJsonArray(name).deepCopy()
                : new JsonArray();
    }

    private String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value;
        if (normalized.length() <= maxLength) return normalized;
        int half = maxLength / 2;
        return normalized.substring(0, half) + "\n... omitted ...\n"
                + normalized.substring(normalized.length() - half);
    }
}
