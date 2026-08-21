package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.i18n.PluginLanguage;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.EvidencePack;
import com.archscope.jetbrains.render.ReportRenderer;
import com.archscope.jetbrains.git.CodexWorkspaceService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;

import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ArchitectureAnalysisService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Logger LOG = Logger.getInstance(ArchitectureAnalysisService.class);
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final ModelClient modelClient;
    private final CodexWorkspaceService workspaceService = new CodexWorkspaceService();
    private final CompactReportAssembler reportAssembler = new CompactReportAssembler();
    private final DomainReportAssembler domainReportAssembler = new DomainReportAssembler();
    private final ReportValidator validator = new ReportValidator();
    private final ReportRenderer renderer = new ReportRenderer();
    private final AnalysisCache cache = new AnalysisCache();

    public ArchitectureAnalysisService() {
        this(ModelClientRegistry.selected());
    }

    public ArchitectureAnalysisService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public AnalysisResult analyze(
            AnalysisRequest request,
            EvidencePack evidence,
            ProgressIndicator indicator
    ) throws Exception {
        return analyze(request, evidence, indicator, ignored -> {}, ignored -> {});
    }

    public AnalysisResult analyze(
            AnalysisRequest request,
            EvidencePack evidence,
            ProgressIndicator indicator,
            Consumer<String> statusListener
    ) throws Exception {
        return analyze(request, evidence, indicator, statusListener, ignored -> {});
    }

    public AnalysisResult analyze(
            AnalysisRequest request,
            EvidencePack evidence,
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            Consumer<ModelStreamEvent> streamListener
    ) throws Exception {
        PluginLanguage.use(request.outputLanguage());
        if (request.isBusinessDomain()) {
            return analyzeBusinessDomain(request, evidence, indicator, statusListener, streamListener);
        }
        indicator.setIndeterminate(false);
        indicator.setFraction(0.1);
        long startedAt = System.nanoTime();
        String analysisProfile = requestCacheProfile(modelClient.cacheIdentity(), request, "selected-changes-v5-single-sop");
        JsonObject cached = cache.load(evidence, analysisProfile);
        if (cached != null) {
            publish(indicator, statusListener, t("已复用相同提交范围的分析结果", "Reused the analysis for the same commit range"), 1.0);
            JsonObject report = cached.deepCopy();
            JsonObject diagnostics = report.has("analysis_diagnostics") && report.get("analysis_diagnostics").isJsonObject()
                    ? report.getAsJsonObject("analysis_diagnostics") : new JsonObject();
            diagnostics.addProperty("operation", "cache_hit");
            diagnostics.addProperty("execution_mode", "single_sop");
            diagnostics.addProperty("model_calls", 0);
            report.add("analysis_diagnostics", diagnostics);
            addExecutionProvenance(report, request);
            String json = GSON.toJson(report);
            return new AnalysisResult(json, renderer.render(report, !JBColor.isBright()), evidence.fingerprint(), evidence.targetCommit());
        }
        SingleModelTurn modelTurn = new SingleModelTurn();
        try (CodexWorkspaceService.Workspace workspace = workspaceService.create(evidence, indicator)) {
            indicator.setFraction(0.2);
            publish(indicator, statusListener, t("正在按单轮 SOP 分析全部业务变化", "Analyzing all business changes with one SOP run"), 0.35);
            String response = modelTurn.complete(
                    promptBuilder.closedAnalysisSystemPrompt(request),
                    promptBuilder.directAnalysisPrompt(request, evidence, workspace),
                    cliWorkingDirectory(request, evidence),
                    indicator,
                    t("执行改动分析 SOP", "Run the change-analysis SOP"),
                    statusListener,
                    ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                    streamListener
            );
            publish(indicator, statusListener, t("正在校验源码证据和流程引用", "Validating source evidence and flow references"), 0.9);
            JsonObject assembled = reportAssembler.assemble(response, request, evidence);
            workspace.materialize(reportSourcePaths(GSON.toJson(assembled), evidence), indicator);
            JsonObject report = validator.validate(GSON.toJson(assembled), evidence, workspace.root());
            ReportLanguageValidator.validate(report, request.outputLanguage());
            addAnalysisDiagnostics(report, startedAt, "completed");
            addSingleTurnDiagnostics(report, modelTurn.calls());
            addExecutionProvenance(report, request);
            String json = GSON.toJson(report);
            publish(indicator, statusListener, t("正在生成 IDE 内交互报告", "Generating the interactive IDE report"), 0.97);
            String html = renderer.render(report, !JBColor.isBright());
            cache.store(evidence, analysisProfile, report);
            indicator.setFraction(1.0);
            LOG.info("Architecture analysis completed: elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000));
            return new AnalysisResult(json, html, evidence.fingerprint(), evidence.targetCommit());
        }
    }

    public AnalysisResult refine(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction,
            ProgressIndicator indicator,
            Consumer<String> statusListener
    ) throws Exception {
        return refine(request, evidence, currentReportJson, instruction, indicator, statusListener, ignored -> {});
    }

    public AnalysisResult refine(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction,
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            Consumer<ModelStreamEvent> streamListener
    ) throws Exception {
        PluginLanguage.use(request.outputLanguage());
        String normalizedInstruction = instruction == null ? "" : instruction.strip();
        if (normalizedInstruction.isEmpty()) throw new ModelClientException(t(
                "请输入需要展开、补充或修改的内容", "Enter what should be expanded, added, or corrected"));
        long startedAt = System.nanoTime();
        Map<String, Long> phaseTimings = new LinkedHashMap<>();
        SingleModelTurn modelTurn = new SingleModelTurn();
        publish(indicator, statusListener, t("正在按单轮 SOP 处理补充要求", "Applying the follow-up with one SOP run"), 0.12);
        JsonObject report;
        DomainEvidencePlan.EditIntent editIntent = null;
        if (request.isBusinessDomain()) {
            try (CodexWorkspaceService.Workspace workspace = workspaceService.createSnapshot(evidence, indicator)) {
                workspace.materialize(reportSourcePaths(currentReportJson, evidence), indicator);
                long phaseStartedAt = System.nanoTime();
                publish(indicator, statusListener, t("正在检索源码并更新完整业务图", "Searching source and updating the complete business graph"), 0.3);
                String response = modelTurn.complete(
                        promptBuilder.businessDomainSystemPrompt(request),
                        promptBuilder.businessDomainSopRefinementPrompt(
                                request, evidence, currentReportJson, normalizedInstruction),
                        cliWorkingDirectory(request, evidence),
                        indicator,
                        t("执行报告修改 SOP", "Run the report-refinement SOP"),
                        statusListener,
                        ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                        streamListener
                );
                phaseTimings.put("single_sop_model", elapsedMs(phaseStartedAt));
                editIntent = responseEditIntent(response);
                publish(indicator, statusListener, t("正在校验修改结果和源码引用", "Validating the update and source references"), 0.88);
                phaseStartedAt = System.nanoTime();
                report = assembleAndValidateBusinessReport(response, request, evidence, workspace, indicator);
                phaseTimings.put("assembly_validation", elapsedMs(phaseStartedAt));
                addDomainDiagnostics(report, 1, startedAt,
                        unknownCount(report) == 0 ? "single_sop_confirmed" : "single_sop_with_unknowns",
                        "refinement", phaseTimings);
            }
        } else {
            try (CodexWorkspaceService.Workspace workspace = workspaceService.create(evidence, indicator)) {
                String response = modelTurn.complete(
                        promptBuilder.refinementSystemPrompt(request),
                        promptBuilder.refinementPrompt(request, evidence, currentReportJson, normalizedInstruction),
                        cliWorkingDirectory(request, evidence),
                        indicator,
                        t("补充改动报告", "Expand the change report"),
                        statusListener,
                        ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                        streamListener
                );
                publish(indicator, statusListener, t("正在校验补充后的源码证据", "Validating the expanded source evidence"), 0.9);
                workspace.materialize(reportSourcePaths(response, evidence), indicator);
                report = validator.validate(response, evidence, workspace.root());
            }
        }
        if (request.isBusinessDomain()) {
            verifyBusinessDomainEditApplied(currentReportJson, report, editIntent);
        }
        appendRevision(report, normalizedInstruction, request.outputLanguage());
        if (!request.isBusinessDomain()) addAnalysisDiagnostics(report, startedAt, "refined");
        addSingleTurnDiagnostics(report, modelTurn.calls());
        addExecutionProvenance(report, request);
        ReportLanguageValidator.validate(report, request.outputLanguage());
        publish(indicator, statusListener, t("正在更新交互报告", "Updating the interactive report"), 0.97);
        long renderStartedAt = System.nanoTime();
        String html = renderer.render(report, !JBColor.isBright());
        phaseTimings.put("render", elapsedMs(renderStartedAt));
        finishDiagnostics(report, startedAt, phaseTimings);
        String json = GSON.toJson(report);
        indicator.setFraction(1.0);
        return new AnalysisResult(json, html, evidence.fingerprint(), evidence.targetCommit());
    }

    private AnalysisResult analyzeBusinessDomain(
            AnalysisRequest request,
            EvidencePack evidence,
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            Consumer<ModelStreamEvent> streamListener
    ) throws Exception {
        indicator.setIndeterminate(false);
        long startedAt = System.nanoTime();
        Map<String, Long> phaseTimings = new LinkedHashMap<>();
        String analysisProfile = requestCacheProfile(modelClient.cacheIdentity(), request, "business-domain-v32-single-sop");
        JsonObject cached = cache.load(evidence, analysisProfile);
        if (cached != null) {
            publish(indicator, statusListener, t("已复用相同主题和工作区的业务报告", "Reused the business report for the same topic and workspace"), 1.0);
            JsonObject report = cached.deepCopy();
            JsonObject diagnostics = report.has("analysis_diagnostics") && report.get("analysis_diagnostics").isJsonObject()
                    ? report.getAsJsonObject("analysis_diagnostics") : new JsonObject();
            if (diagnostics.has("elapsed_ms")) diagnostics.add("source_analysis_elapsed_ms", diagnostics.get("elapsed_ms").deepCopy());
            diagnostics.addProperty("operation", "cache_hit");
            diagnostics.addProperty("model_calls", 0);
            report.add("analysis_diagnostics", diagnostics);
            addExecutionProvenance(report, request);
            phaseTimings.put("cache_lookup", elapsedMs(startedAt));
            finishDiagnostics(report, startedAt, phaseTimings);
            long renderStartedAt = System.nanoTime();
            String html = renderer.render(report, !JBColor.isBright());
            phaseTimings.put("render", elapsedMs(renderStartedAt));
            finishDiagnostics(report, startedAt, phaseTimings);
            return new AnalysisResult(GSON.toJson(report), html, evidence.fingerprint(), evidence.targetCommit());
        }
        SingleModelTurn modelTurn = new SingleModelTurn();
        try (CodexWorkspaceService.Workspace workspace = workspaceService.createSnapshot(evidence, indicator)) {
            publish(indicator, statusListener, t("正在按单轮 SOP 检索并生成业务图", "Searching and building the business graph with one SOP run"), 0.2);
            long phaseStartedAt = System.nanoTime();
            String response = modelTurn.complete(
                    promptBuilder.businessDomainSystemPrompt(request),
                    promptBuilder.businessDomainSopPrompt(request, evidence),
                    cliWorkingDirectory(request, evidence),
                    indicator,
                    t("执行业务分析 SOP", "Run the business-analysis SOP"),
                    statusListener,
                    ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                    streamListener
            );
            phaseTimings.put("single_sop_model", elapsedMs(phaseStartedAt));
            publish(indicator, statusListener, t("正在校验业务流程和源码引用", "Validating business flows and source references"), 0.86);
            phaseStartedAt = System.nanoTime();
            JsonObject report = assembleAndValidateBusinessReport(response, request, evidence, workspace, indicator);
            ReportLanguageValidator.validate(report, request.outputLanguage());
            phaseTimings.put("assembly_validation", elapsedMs(phaseStartedAt));
            addDomainDiagnostics(report, 1, startedAt,
                    unknownCount(report) == 0 ? "single_sop_confirmed" : "single_sop_with_unknowns",
                    "initial", phaseTimings);
            addSingleTurnDiagnostics(report, modelTurn.calls());
            addExecutionProvenance(report, request);
            ReportLanguageValidator.validate(report, request.outputLanguage());
            publish(indicator, statusListener, t("正在生成业务理解报告", "Generating the business logic report"), 0.97);
            long renderStartedAt = System.nanoTime();
            String html = renderer.render(report, !JBColor.isBright());
            phaseTimings.put("render", elapsedMs(renderStartedAt));
            finishDiagnostics(report, startedAt, phaseTimings);
            String json = GSON.toJson(report);
            cache.store(evidence, analysisProfile, report);
            indicator.setFraction(1.0);
            return new AnalysisResult(json, html, evidence.fingerprint(), evidence.targetCommit());
        }
    }

    private void appendRevision(
            JsonObject report,
            String instruction,
            AnalysisRequest.OutputLanguage outputLanguage
    ) {
        JsonArray history = report.has("revision_history") && report.get("revision_history").isJsonArray()
                ? report.getAsJsonArray("revision_history")
                : new JsonArray();
        if (!history.isEmpty()) {
            JsonObject last = history.get(history.size() - 1).isJsonObject()
                    ? history.get(history.size() - 1).getAsJsonObject()
                    : null;
            if (last != null && last.has("instruction") && instruction.equals(last.get("instruction").getAsString())) {
                report.add("revision_history", history);
                return;
            }
        }
        JsonObject revision = new JsonObject();
        revision.addProperty("instruction", outputLanguage.isEnglish() && ReportLanguageValidator.containsHan(instruction)
                ? "Follow-up instruction applied" : instruction);
        revision.addProperty("summary", outputLanguage.isEnglish()
                ? "The report was updated from the follow-up instruction."
                : "已根据补充要求更新报告");
        history.add(revision);
        report.add("revision_history", history);
    }

    static void verifyBusinessDomainEditApplied(
            String currentReportJson,
            JsonObject candidate,
            DomainEvidencePlan.EditIntent intent
    ) throws ModelClientException {
        JsonObject current = JsonParser.parseString(currentReportJson).getAsJsonObject();
        JsonObject before = meaningfulReport(current);
        JsonObject after = meaningfulReport(candidate);
        if (before.equals(after)) {
            throw unappliedEdit("报告主体没有发生变化");
        }

        int beforeDomains = arraySize(current, "business_domains", "domains");
        int afterDomains = arraySize(candidate, "business_domains", "domains");
        int beforeFlows = flowCount(current);
        int afterFlows = flowCount(candidate);
        int beforeNodes = flowNodeCount(current);
        int afterNodes = flowNodeCount(candidate);

        if (intent != null && intent.has(DomainEvidencePlan.Operation.ADD_DOMAIN) && afterDomains <= beforeDomains) {
            throw unappliedEdit("要求新增业务域，但业务域数量没有增加");
        }
        if (intent != null && intent.has(DomainEvidencePlan.Operation.MERGE_DOMAINS) && afterDomains >= beforeDomains) {
            throw unappliedEdit("要求合并业务域，但业务域数量没有减少");
        }
        if (intent != null && intent.has(DomainEvidencePlan.Operation.MERGE_FLOWS) && afterFlows >= beforeFlows) {
            throw unappliedEdit("要求合并流程，但流程数量没有减少");
        }
        if (intent != null && intent.has(DomainEvidencePlan.Operation.SPLIT_FLOW) && afterFlows <= beforeFlows) {
            throw unappliedEdit("要求拆分流程，但流程数量没有增加");
        }
        if (intent != null && intent.has(DomainEvidencePlan.Operation.ADD_NODES) && afterNodes <= beforeNodes) {
            throw unappliedEdit("要求新增流程节点，但节点数量没有增加");
        }
        if (intent != null && intent.has(DomainEvidencePlan.Operation.REMOVE_NODES) && afterNodes >= beforeNodes) {
            throw unappliedEdit("要求删除流程节点，但节点数量没有减少");
        }
        if (intent != null && (intent.has(DomainEvidencePlan.Operation.MOVE_NODES)
                || intent.has(DomainEvidencePlan.Operation.REORDER_NODES))
                && flowGraphIdentity(current).equals(flowGraphIdentity(candidate))) {
            throw unappliedEdit("要求移动或重排节点，但流程图的父子关系和顺序没有变化");
        }
    }

    private static ModelClientException unappliedEdit(String reason) {
        return new ModelClientException("报告修改未生效：" + reason + "。原报告已保留，请缩小目标后重试。");
    }

    private static DomainEvidencePlan.EditIntent responseEditIntent(String response) {
        try {
            return DomainEvidencePlan.EditIntent.parse(ModelJsonParser.parseObject(response));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JsonObject meaningfulReport(JsonObject report) {
        JsonObject copy = report.deepCopy();
        for (String field : java.util.List.of(
                "revision_history", "analysis_diagnostics", "execution_provenance", "generated_at", "updated_at"
        )) {
            copy.remove(field);
        }
        return copy;
    }

    private static int arraySize(JsonObject report, String primary, String fallback) {
        if (report.has(primary) && report.get(primary).isJsonArray()) return report.getAsJsonArray(primary).size();
        return report.has(fallback) && report.get(fallback).isJsonArray() ? report.getAsJsonArray(fallback).size() : 0;
    }

    private static int flowCount(JsonObject report) {
        if (report.has("flow_map") && report.get("flow_map").isJsonObject()) {
            JsonObject root = report.getAsJsonObject("flow_map");
            return root.has("children") && root.get("children").isJsonArray()
                    ? root.getAsJsonArray("children").size() : 0;
        }
        return report.has("flows") && report.get("flows").isJsonArray() ? report.getAsJsonArray("flows").size() : 0;
    }

    private static int flowNodeCount(JsonObject report) {
        if (report.has("flow_map") && report.get("flow_map").isJsonObject()) {
            return descendantCount(report.getAsJsonObject("flow_map"));
        }
        if (!report.has("flows") || !report.get("flows").isJsonArray()) return 0;
        int count = 0;
        for (JsonElement flow : report.getAsJsonArray("flows")) {
            if (flow.isJsonObject()) count += childArraySize(flow.getAsJsonObject(), "steps");
        }
        return count;
    }

    private static int descendantCount(JsonObject parent) {
        if (!parent.has("children") || !parent.get("children").isJsonArray()) return 0;
        int count = 0;
        for (JsonElement child : parent.getAsJsonArray("children")) {
            if (!child.isJsonObject()) continue;
            count++;
            count += descendantCount(child.getAsJsonObject());
        }
        return count;
    }

    private static int childArraySize(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonArray() ? object.getAsJsonArray(field).size() : 0;
    }

    private static String flowGraphIdentity(JsonObject report) {
        StringBuilder identity = new StringBuilder();
        if (report.has("flow_map") && report.get("flow_map").isJsonObject()) {
            appendGraphIdentity(report.getAsJsonObject("flow_map"), identity);
        } else if (report.has("flows") && report.get("flows").isJsonArray()) {
            for (JsonElement flow : report.getAsJsonArray("flows")) {
                if (flow.isJsonObject()) appendLegacyFlowIdentity(flow.getAsJsonObject(), identity);
            }
        }
        return identity.toString();
    }

    private static void appendGraphIdentity(JsonObject node, StringBuilder identity) {
        identity.append('(').append(firstString(node, "id")).append(':');
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject()) appendGraphIdentity(child.getAsJsonObject(), identity);
            }
        }
        identity.append(')');
    }

    private static void appendLegacyFlowIdentity(JsonObject flow, StringBuilder identity) {
        identity.append('(').append(firstString(flow, "id")).append(':');
        if (flow.has("steps") && flow.get("steps").isJsonArray()) {
            for (JsonElement step : flow.getAsJsonArray("steps")) {
                if (step.isJsonObject()) identity.append(firstString(step.getAsJsonObject(), "id")).append(',');
            }
        }
        identity.append(')');
    }

    static String requestCacheProfile(String modelIdentity, AnalysisRequest request, String version) {
        return modelIdentity + "/" + version
                + "/lang-" + request.outputLanguage().code()
                + "/f-" + shortHash(request.focus())
                + "/wd-" + (request.cliWorkingDirectory() == null
                        ? "auto" : shortHash(request.cliWorkingDirectory().toString()))
                + "/g-" + request.guidance().fingerprint();
    }

    static int unknownCount(JsonObject report) {
        return report.has("unknowns") && report.get("unknowns").isJsonArray()
                ? report.getAsJsonArray("unknowns").size()
                : 0;
    }

    static Set<String> reportSourcePaths(String reportJson, EvidencePack evidence) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        try {
            collectReportSourcePaths(JsonParser.parseString(reportJson), Set.copyOf(evidence.targetManifest()), paths);
        } catch (RuntimeException ignored) {
            return Set.of();
        }
        return Set.copyOf(paths);
    }

    private static void collectReportSourcePaths(JsonElement element, Set<String> manifest, Set<String> paths) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectReportSourcePaths(child, manifest, paths);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String file = string(object, "file").replace('\\', '/');
        if (manifest.contains(file)) paths.add(file);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            collectReportSourcePaths(entry.getValue(), manifest, paths);
        }
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            String value = string(object, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private JsonObject assembleAndValidateBusinessReport(
            String response,
            AnalysisRequest request,
            EvidencePack evidence,
            CodexWorkspaceService.Workspace workspace,
            ProgressIndicator indicator
    ) throws Exception {
        JsonObject assembled = domainReportAssembler.assemble(response, request, evidence);
        workspace.materialize(reportSourcePaths(GSON.toJson(assembled), evidence), indicator);
        JsonObject validated = validator.validate(GSON.toJson(assembled), evidence, workspace.root());
        ReportLanguageValidator.validate(validated, request.outputLanguage());
        return validated;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void addDomainDiagnostics(
            JsonObject report,
            int evidenceRounds,
            long startedAt,
            String stopReason,
            String operation,
            Map<String, Long> phaseTimings
    ) {
        JsonObject diagnostics = new JsonObject();
        diagnostics.addProperty("evidence_rounds", evidenceRounds);
        diagnostics.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000L);
        diagnostics.addProperty("stop_reason", stopReason);
        diagnostics.addProperty("unknown_count", unknownCount(report));
        diagnostics.addProperty("operation", operation);
        diagnostics.add("phase_timings_ms", timingJson(phaseTimings));
        report.add("analysis_diagnostics", diagnostics);
    }

    private static void addAnalysisDiagnostics(JsonObject report, long startedAt, String stopReason) {
        JsonObject diagnostics = report.has("analysis_diagnostics") && report.get("analysis_diagnostics").isJsonObject()
                ? report.getAsJsonObject("analysis_diagnostics") : new JsonObject();
        diagnostics.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000L);
        diagnostics.addProperty("stop_reason", stopReason);
        report.add("analysis_diagnostics", diagnostics);
    }

    private static void addSingleTurnDiagnostics(JsonObject report, int modelCalls) {
        JsonObject diagnostics = report.has("analysis_diagnostics") && report.get("analysis_diagnostics").isJsonObject()
                ? report.getAsJsonObject("analysis_diagnostics") : new JsonObject();
        diagnostics.addProperty("execution_mode", "single_sop");
        diagnostics.addProperty("model_calls", modelCalls);
        report.add("analysis_diagnostics", diagnostics);
    }

    private void addExecutionProvenance(JsonObject report, AnalysisRequest request) {
        JsonObject diagnostics = report.has("analysis_diagnostics") && report.get("analysis_diagnostics").isJsonObject()
                ? report.getAsJsonObject("analysis_diagnostics") : new JsonObject();
        diagnostics.addProperty("model_provider_id", modelClient.id());
        diagnostics.addProperty("model_provider_name", request.outputLanguage().isEnglish()
                ? englishProviderName(modelClient.id(), modelClient.displayName())
                : modelClient.displayName());
        diagnostics.addProperty("system_prompt_applied", !request.guidance().systemPrompt().isBlank());
        diagnostics.addProperty("guidance_fingerprint", request.guidance().fingerprint());
        diagnostics.addProperty("workspace_access", "single_repository_sop");
        diagnostics.addProperty("cli_working_directory", request.cliWorkingDirectory() == null
                ? "git_or_project_root" : "custom");
        if (request.cliWorkingDirectory() != null) {
            diagnostics.addProperty("cli_working_directory_path",
                    request.cliWorkingDirectory().toAbsolutePath().normalize().toString());
        }
        diagnostics.addProperty("output_language", request.outputLanguage().code());
        report.add("analysis_diagnostics", diagnostics);
    }

    private static String englishProviderName(String id, String displayName) {
        return switch (id) {
            case "codex-local" -> "Local Codex";
            case "claude-local" -> "Claude CLI";
            default -> ReportLanguageValidator.containsHan(displayName) ? id : displayName;
        };
    }

    private static void finishDiagnostics(JsonObject report, long startedAt, Map<String, Long> phaseTimings) {
        if (!report.has("analysis_diagnostics") || !report.get("analysis_diagnostics").isJsonObject()) return;
        JsonObject diagnostics = report.getAsJsonObject("analysis_diagnostics");
        diagnostics.addProperty("elapsed_ms", elapsedMs(startedAt));
        diagnostics.add("phase_timings_ms", timingJson(phaseTimings));
    }

    private static JsonObject timingJson(Map<String, Long> phaseTimings) {
        JsonObject timings = new JsonObject();
        phaseTimings.forEach((phase, elapsed) -> timings.addProperty(phase, Math.max(0L, elapsed)));
        return timings;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String t(String chinese, String english) {
        return PluginLanguage.text(chinese, english);
    }

    static Path cliWorkingDirectory(AnalysisRequest request, EvidencePack evidence) {
        if (request.cliWorkingDirectory() != null) return request.cliWorkingDirectory();
        if (evidence.repositoryRoot() != null) return evidence.repositoryRoot();
        return request.repositoryRoot();
    }


    private final class SingleModelTurn {
        private int calls;

        String complete(
                String systemPrompt,
                String userPrompt,
                Path workingDirectory,
                ProgressIndicator indicator,
                String stage,
                Consumer<String> statusListener,
                ModelClient.WorkspaceAccess workspaceAccess
        ) throws ModelClientException {
            return complete(systemPrompt, userPrompt, workingDirectory, indicator, stage,
                    statusListener, workspaceAccess, ignored -> {});
        }

        String complete(
                String systemPrompt,
                String userPrompt,
                Path workingDirectory,
                ProgressIndicator indicator,
                String stage,
                Consumer<String> statusListener,
                ModelClient.WorkspaceAccess workspaceAccess,
                Consumer<ModelStreamEvent> streamListener
        ) throws ModelClientException {
            if (calls != 0) {
                throw new IllegalStateException("A single button action may start only one model turn");
            }
            calls++;
            return modelClient.complete(
                    systemPrompt, userPrompt, workingDirectory, indicator, stage, statusListener,
                    workspaceAccess, streamListener);
        }

        int calls() {
            return calls;
        }
    }

    private void publish(
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            String message,
            double fraction
    ) {
        message = PluginLanguage.userMessage(message);
        indicator.setText(message);
        indicator.setFraction(fraction);
        statusListener.accept(message);
    }
}
