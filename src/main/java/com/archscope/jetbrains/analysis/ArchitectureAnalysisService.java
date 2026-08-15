package com.archscope.jetbrains.analysis;

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
    private static final String AUTO_EVIDENCE_INSTRUCTION =
            "继续检索源码，逐项确认或证伪当前待确认内容；只删除已经由新证据解决的待确认项。";
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final ModelClient modelClient;
    private final CodexWorkspaceService workspaceService = new CodexWorkspaceService();
    private final EvidenceExpansionService expansionService = new EvidenceExpansionService();
    private final DomainEvidenceExpansionService domainExpansionService = new DomainEvidenceExpansionService();
    private final CompactReportAssembler reportAssembler = new CompactReportAssembler();
    private final DomainReportAssembler domainReportAssembler = new DomainReportAssembler();
    private final DomainEvidenceResolutionAssembler domainResolutionAssembler = new DomainEvidenceResolutionAssembler();
    private final DomainReportPatchAssembler domainPatchAssembler = new DomainReportPatchAssembler();
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
        return analyze(request, evidence, indicator, ignored -> {});
    }

    public AnalysisResult analyze(
            AnalysisRequest request,
            EvidencePack evidence,
            ProgressIndicator indicator,
            Consumer<String> statusListener
    ) throws Exception {
        if (request.isBusinessDomain()) {
            return analyzeBusinessDomain(request, evidence, indicator, statusListener);
        }
        indicator.setIndeterminate(false);
        indicator.setFraction(0.1);
        long startedAt = System.nanoTime();
        String analysisProfile = requestCacheProfile(modelClient.cacheIdentity(), request, "selected-changes-v3-elapsed");
        JsonObject cached = cache.load(evidence, analysisProfile);
        if (cached != null) {
            publish(indicator, statusListener, "已复用相同提交范围的分析结果", 1.0);
            String json = GSON.toJson(cached);
            return new AnalysisResult(json, renderer.render(cached, !JBColor.isBright()), evidence.fingerprint(), evidence.targetCommit());
        }
        try (CodexWorkspaceService.Workspace workspace = workspaceService.create(evidence, indicator)) {
            indicator.setFraction(0.2);
            String response;
            String aggregateDiff = workspace.readEvidence("aggregate.diff");
            if (shouldUseDirectAnalysis(countPatchFiles(aggregateDiff), aggregateDiff.length())) {
                publish(indicator, statusListener, "正在单轮分析独立业务流程", 0.35);
                response = modelClient.complete(
                        promptBuilder.closedAnalysisSystemPrompt(request),
                        promptBuilder.directAnalysisPrompt(request, evidence, workspace),
                        workspace.root(),
                        indicator,
                        "分析独立业务流程",
                        statusListener,
                        ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                );
            } else {
                publish(indicator, statusListener, "正在识别独立业务流程", 0.3);
                String planningResponse = modelClient.complete(
                        promptBuilder.planningSystemPrompt(request),
                        promptBuilder.planningPrompt(request, evidence, workspace),
                        workspace.root(),
                        indicator,
                        "识别独立业务流程",
                        statusListener,
                        ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                );
                EvidencePlan plan = EvidencePlan.parse(planningResponse, evidence);
                LOG.info("Change plan completed: groups=" + plan.groups().size()
                        + ", queries=" + plan.groups().stream().mapToInt(group -> group.evidenceQueries().size()).sum());

                publish(indicator, statusListener, "正在补全流程入口与结果证据", 0.52);
                String expandedEvidence = expansionService.expand(plan, evidence, workspace, indicator);
                publish(indicator, statusListener, "正在生成多流程报告", 0.65);
                response = modelClient.complete(
                        promptBuilder.closedAnalysisSystemPrompt(request),
                        promptBuilder.finalPrompt(request, evidence, workspace, plan, expandedEvidence),
                        workspace.root(),
                        indicator,
                        "生成多流程报告",
                        statusListener,
                        ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                );
            }
            publish(indicator, statusListener, "正在校验源码证据和流程引用", 0.9);
            JsonObject assembled = reportAssembler.assemble(response, request, evidence);
            JsonObject report = validator.validate(GSON.toJson(assembled), evidence, workspace.root());
            addAnalysisDiagnostics(report, startedAt, "completed");
            String json = GSON.toJson(report);
            publish(indicator, statusListener, "正在生成 IDE 内交互报告", 0.97);
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
        String normalizedInstruction = instruction == null ? "" : instruction.strip();
        if (normalizedInstruction.isEmpty()) throw new ModelClientException("请输入需要展开、补充或修改的内容");
        long startedAt = System.nanoTime();
        Map<String, Long> phaseTimings = new LinkedHashMap<>();
        publish(indicator, statusListener, "正在理解补充要求", 0.12);
        String response;
        JsonObject report;
        if (request.isBusinessDomain()) {
            try (CodexWorkspaceService.Workspace workspace = workspaceService.createSnapshot(evidence, indicator)) {
                workspace.materialize(reportSourcePaths(currentReportJson, evidence), indicator);
                publish(indicator, statusListener, "正在识别补充内容所需证据", 0.2);
                long phaseStartedAt = System.nanoTime();
                String planningResponse = modelClient.complete(
                        promptBuilder.businessDomainPlanningSystemPrompt(request),
                        promptBuilder.businessDomainPlanningPrompt(
                                request, evidence, currentReportJson, normalizedInstruction
                        ),
                        workspace.root(),
                        indicator,
                        "识别补充证据范围",
                        statusListener,
                        ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                );
                phaseTimings.put("planning_model", elapsedMs(phaseStartedAt));
                DomainEvidencePlan plan = DomainEvidencePlan.parse(planningResponse, evidence)
                        .withUnresolvedQueries(currentReportJson);
                publish(indicator, statusListener, "正在读取补充流程证据", 0.42);
                phaseStartedAt = System.nanoTime();
                String sourceEvidence = domainExpansionService.expand(plan, evidence, workspace, indicator);
                phaseTimings.put("source_expansion", elapsedMs(phaseStartedAt));
                JsonObject currentReport = JsonParser.parseString(currentReportJson).getAsJsonObject();
                report = null;
                if (shouldUseIncrementalDomainPatch(currentReport, normalizedInstruction)) {
                    try {
                        publish(indicator, statusListener, "正在增量更新已确认的业务报告", 0.68);
                        phaseStartedAt = System.nanoTime();
                        String patchResponse = modelClient.complete(
                                promptBuilder.businessDomainPatchSystemPrompt(request),
                                promptBuilder.businessDomainPatchPrompt(
                                        request, evidence, currentReportJson, normalizedInstruction, plan, sourceEvidence
                                ),
                                workspace.root(),
                                indicator,
                                "增量更新业务报告",
                                statusListener,
                                ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                        );
                        phaseTimings.put("patch_model", elapsedMs(phaseStartedAt));
                        DomainReportPatchAssembler.ApplyResult patch = domainPatchAssembler.apply(
                                patchResponse, currentReport, evidence
                        );
                        if (!patch.requiresStructuralRebuild()) {
                            publish(indicator, statusListener, "正在校验增量补丁与源码引用", 0.9);
                            phaseStartedAt = System.nanoTime();
                            report = validator.validate(GSON.toJson(patch.report()), evidence, workspace.root());
                            phaseTimings.put("validation", elapsedMs(phaseStartedAt));
                            addDomainDiagnostics(report, 1, startedAt, "incremental_patch", "refinement", phaseTimings);
                        }
                    } catch (Exception exception) {
                        if (indicator.isCanceled()) throw exception;
                        LOG.warn("Incremental business report patch was rejected; falling back to a full refinement", exception);
                    }
                }
                if (report == null) {
                    phaseStartedAt = System.nanoTime();
                    response = modelClient.complete(
                            promptBuilder.businessDomainSystemPrompt(request),
                            promptBuilder.businessDomainRefinementPrompt(
                                    request, evidence, currentReportJson, normalizedInstruction, plan, sourceEvidence
                            ),
                            workspace.root(),
                            indicator,
                            "补充业务报告",
                            statusListener,
                            ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                    );
                    phaseTimings.put("full_refinement_model", elapsedMs(phaseStartedAt));
                    publish(indicator, statusListener, "正在校验补充后的源码证据", 0.9);
                    phaseStartedAt = System.nanoTime();
                    report = assembleAndValidateBusinessReport(
                            response, request, evidence, workspace, indicator, statusListener
                    );
                    phaseTimings.put("assembly_validation", elapsedMs(phaseStartedAt));
                    phaseStartedAt = System.nanoTime();
                    DomainConvergence convergence = convergeDomainEvidence(
                            request, evidence, report, plan, sourceEvidence, workspace, indicator, statusListener, startedAt
                    );
                    phaseTimings.put("convergence", elapsedMs(phaseStartedAt));
                    report = convergence.report();
                    addDomainDiagnostics(report, convergence.evidenceRounds(), startedAt,
                            convergence.stopReason(), "refinement", phaseTimings);
                }
            }
        } else {
            try (CodexWorkspaceService.Workspace workspace = workspaceService.create(evidence, indicator)) {
                response = modelClient.complete(
                        promptBuilder.refinementSystemPrompt(request),
                        promptBuilder.refinementPrompt(request, evidence, currentReportJson, normalizedInstruction),
                        workspace.root(),
                        indicator,
                        "补充改动报告",
                        statusListener,
                        ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY
                );
                publish(indicator, statusListener, "正在校验补充后的源码证据", 0.9);
                report = validator.validate(response, evidence, workspace.root());
            }
        }
        appendRevision(report, normalizedInstruction);
        if (!request.isBusinessDomain()) addAnalysisDiagnostics(report, startedAt, "refined");
        publish(indicator, statusListener, "正在更新交互报告", 0.97);
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
            Consumer<String> statusListener
    ) throws Exception {
        indicator.setIndeterminate(false);
        long startedAt = System.nanoTime();
        Map<String, Long> phaseTimings = new LinkedHashMap<>();
        String analysisProfile = requestCacheProfile(modelClient.cacheIdentity(), request, "business-domain-v16-fast-stable");
        JsonObject cached = cache.load(evidence, analysisProfile);
        if (cached != null) {
            publish(indicator, statusListener, "已复用相同主题和工作区的业务报告", 1.0);
            JsonObject report = cached.deepCopy();
            JsonObject diagnostics = report.has("analysis_diagnostics") && report.get("analysis_diagnostics").isJsonObject()
                    ? report.getAsJsonObject("analysis_diagnostics") : new JsonObject();
            if (diagnostics.has("elapsed_ms")) diagnostics.add("source_analysis_elapsed_ms", diagnostics.get("elapsed_ms").deepCopy());
            diagnostics.addProperty("operation", "cache_hit");
            report.add("analysis_diagnostics", diagnostics);
            phaseTimings.put("cache_lookup", elapsedMs(startedAt));
            finishDiagnostics(report, startedAt, phaseTimings);
            long renderStartedAt = System.nanoTime();
            String html = renderer.render(report, !JBColor.isBright());
            phaseTimings.put("render", elapsedMs(renderStartedAt));
            finishDiagnostics(report, startedAt, phaseTimings);
            return new AnalysisResult(GSON.toJson(report), html, evidence.fingerprint(), evidence.targetCommit());
        }
        try (CodexWorkspaceService.Workspace workspace = workspaceService.createSnapshot(evidence, indicator)) {
            publish(indicator, statusListener, "正在识别业务边界和关键入口", 0.18);
            long phaseStartedAt = System.nanoTime();
            String planningResponse = modelClient.complete(
                    promptBuilder.businessDomainPlanningSystemPrompt(request),
                    promptBuilder.businessDomainPlanningPrompt(request, evidence, "", ""),
                    workspace.root(),
                    indicator,
                    "识别业务分析范围",
                    statusListener,
                    ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
            );
            phaseTimings.put("planning_model", elapsedMs(phaseStartedAt));
            DomainEvidencePlan plan = DomainEvidencePlan.parse(planningResponse, evidence);
            publish(indicator, statusListener, "正在读取入口、规则、状态和结果证据", 0.4);
            phaseStartedAt = System.nanoTime();
            String sourceEvidence = domainExpansionService.expand(plan, evidence, workspace, indicator);
            phaseTimings.put("source_expansion", elapsedMs(phaseStartedAt));
            LOG.info("Business source evidence expanded: payloadChars=" + sourceEvidence.length());
            publish(indicator, statusListener, "正在生成业务域与完整流程", 0.62);
            phaseStartedAt = System.nanoTime();
            String response = modelClient.complete(
                    promptBuilder.businessDomainSystemPrompt(request),
                    promptBuilder.businessDomainFinalPrompt(request, evidence, plan, sourceEvidence),
                    workspace.root(),
                    indicator,
                    "生成业务理解报告",
                    statusListener,
                    ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
            );
            phaseTimings.put("synthesis_model", elapsedMs(phaseStartedAt));
            publish(indicator, statusListener, "正在校验业务流程和源码引用", 0.72);
            phaseStartedAt = System.nanoTime();
            JsonObject report = assembleAndValidateBusinessReport(
                    response, request, evidence, workspace, indicator, statusListener
            );
            phaseTimings.put("assembly_validation", elapsedMs(phaseStartedAt));
            phaseStartedAt = System.nanoTime();
            DomainConvergence convergence = convergeDomainEvidence(
                    request, evidence, report, plan, sourceEvidence, workspace, indicator, statusListener, startedAt
            );
            phaseTimings.put("convergence", elapsedMs(phaseStartedAt));
            report = convergence.report();
            addDomainDiagnostics(report, convergence.evidenceRounds(), startedAt,
                    convergence.stopReason(), "initial", phaseTimings);
            publish(indicator, statusListener, "正在生成业务理解报告", 0.97);
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

    private DomainConvergence convergeDomainEvidence(
            AnalysisRequest request,
            EvidencePack evidence,
            JsonObject initialReport,
            DomainEvidencePlan initialPlan,
            String initialEvidence,
            CodexWorkspaceService.Workspace workspace,
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            long startedAt
    ) throws Exception {
        JsonObject report = initialReport;
        int evidenceRounds = 1;
        String stopReason = unknownCount(report) == 0 ? "confirmed" : "continuing";
        Set<String> planIdentities = new LinkedHashSet<>();
        planIdentities.add(planIdentity(initialPlan));
        Set<String> attemptedQueries = new LinkedHashSet<>();
        initialPlan.queries().forEach(query -> attemptedQueries.add(query.literal()));
        Set<String> evidenceIdentities = new LinkedHashSet<>();
        evidenceIdentities.add(sourceEvidenceIdentity(initialEvidence));
        DomainEvidencePlan activePlan = initialPlan;
        DomainEvidencePlan pendingFrontier = null;
        Set<String> globallyPlannedUnknowns = new LinkedHashSet<>();

        while (unknownCount(report) > 0) {
            indicator.checkCanceled();
            String currentJson = GSON.toJson(report);
            String beforeUnknowns = unknownIdentity(report);
            int beforeResolutionCount = evidenceResolutionCount(report);
            int nextRound = evidenceRounds + 1;
            double planningFraction = Math.min(0.88, 0.72 + (nextRound - 1) * 0.07);
            try {
                publish(indicator, statusListener,
                        "正在补齐待确认项 · 第 " + nextRound + " 轮",
                        planningFraction);
                DomainEvidencePlan followUpPlan;
                if (pendingFrontier != null && !pendingFrontier.queries().isEmpty()) {
                    followUpPlan = pendingFrontier.excludingQueries(attemptedQueries);
                    pendingFrontier = null;
                } else {
                    followUpPlan = activePlan.unresolvedOnly(currentJson).excludingQueries(attemptedQueries);
                }
                boolean plannedLocally = !followUpPlan.queries().isEmpty()
                        && !planIdentities.contains(planIdentity(followUpPlan));
                if (!plannedLocally) {
                    if (!globallyPlannedUnknowns.add(beforeUnknowns)) {
                        stopReason = "unknowns_stable";
                        break;
                    }
                    String followUpPlanResponse = modelClient.complete(
                            promptBuilder.businessDomainPlanningSystemPrompt(request),
                            promptBuilder.businessDomainPlanningPrompt(
                                    request, evidence, currentJson,
                                    followUpEvidenceInstruction(attemptedQueries)
                            ),
                            workspace.root(),
                            indicator,
                            "定位待确认项的补充证据",
                            statusListener,
                            ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                    );
                    followUpPlan = DomainEvidencePlan.parse(followUpPlanResponse, evidence)
                            .withUnresolvedQueries(currentJson)
                            .retainingQueriesIn(currentJson)
                            .excludingQueries(attemptedQueries);
                }
                if (followUpPlan.queries().isEmpty()) {
                    stopReason = "no_new_query";
                    break;
                }
                followUpPlan.queries().forEach(query -> attemptedQueries.add(query.literal()));
                if (!planIdentities.add(planIdentity(followUpPlan))) {
                    stopReason = "no_new_plan";
                    break;
                }
                activePlan = followUpPlan;

                String followUpEvidence = domainExpansionService.expand(
                        followUpPlan, evidence, workspace, indicator
                );
                if (!evidenceIdentities.add(sourceEvidenceIdentity(followUpEvidence))) {
                    stopReason = "no_new_evidence";
                    break;
                }
                publish(indicator, statusListener, "正在用补充证据收敛待确认项",
                        Math.min(0.93, planningFraction + 0.04));
                String resolutionResponse = modelClient.complete(
                        promptBuilder.businessDomainResolutionSystemPrompt(request),
                        promptBuilder.businessDomainResolutionPrompt(
                                request, evidence, report, followUpPlan, followUpEvidence
                        ),
                        workspace.root(),
                        indicator,
                        "收敛待确认项",
                        statusListener,
                        ModelClient.WorkspaceAccess.CLOSED_EVIDENCE
                );
                DomainEvidencePlan nextFrontier = DomainEvidencePlan.frontierFromResolution(
                        resolutionResponse, currentJson, followUpEvidence, evidence
                ).excludingQueries(attemptedQueries);
                JsonObject refined = domainResolutionAssembler.apply(resolutionResponse, report, evidence);
                report = validator.validate(GSON.toJson(refined), evidence, workspace.root());
                evidenceRounds = nextRound;
                if (unknownCount(report) == 0) {
                    stopReason = "confirmed";
                    break;
                }
                boolean unknownsAdvanced = !beforeUnknowns.equals(unknownIdentity(report));
                boolean resolutionsAdvanced = evidenceResolutionCount(report) > beforeResolutionCount;
                if (!unknownsAdvanced && !resolutionsAdvanced) {
                    if (nextFrontier.queries().isEmpty()) {
                        stopReason = "unknowns_stable";
                        break;
                    }
                    pendingFrontier = nextFrontier;
                    activePlan = nextFrontier;
                }
                stopReason = "continuing";
            } catch (Exception exception) {
                if (indicator.isCanceled()) throw exception;
                LOG.warn("Automatic business evidence refinement stopped; keeping the last validated report", exception);
                stopReason = "refinement_failed";
                break;
            }
        }
        return new DomainConvergence(report, evidenceRounds, stopReason);
    }

    private void appendRevision(JsonObject report, String instruction) {
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
        revision.addProperty("instruction", instruction);
        revision.addProperty("summary", "已根据补充要求更新报告");
        history.add(revision);
        report.add("revision_history", history);
    }

    static boolean shouldUseDirectAnalysis(int changedPathCount, int aggregateDiffChars) {
        return changedPathCount <= 30 && aggregateDiffChars <= 90_000;
    }

    static boolean shouldUseIncrementalDomainPatch(JsonObject report, String instruction) {
        if (unknownCount(report) != 0 || !report.has("flow_map") || !report.get("flow_map").isJsonObject()) {
            return false;
        }
        String normalized = instruction == null ? "" : instruction.strip().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) return false;
        boolean chineseStructural = java.util.List.of("新增", "增加", "删除", "移除", "合并", "拆分", "重排", "重做", "重构")
                .stream().anyMatch(normalized::contains)
                && java.util.List.of("流程", "步骤", "业务域", "整份报告", "整个报告")
                .stream().anyMatch(normalized::contains);
        if (chineseStructural) return false;
        return java.util.List.of(
                "重新生成报告", "完全重写", "整体重构",
                "add flow", "new flow", "remove flow", "delete flow", "split flow", "merge flow", "new domain",
                "structural rebuild", "rewrite the entire report"
        ).stream().noneMatch(normalized::contains);
    }

    static String requestCacheProfile(String modelIdentity, AnalysisRequest request, String version) {
        return modelIdentity + "/" + version
                + "/f-" + shortHash(request.focus())
                + "/g-" + request.guidance().fingerprint();
    }

    static int unknownCount(JsonObject report) {
        return report.has("unknowns") && report.get("unknowns").isJsonArray()
                ? report.getAsJsonArray("unknowns").size()
                : 0;
    }

    static String unknownIdentity(JsonObject report) {
        if (!report.has("unknowns") || !report.get("unknowns").isJsonArray()) return "";
        return report.getAsJsonArray("unknowns").asList().stream()
                .map(item -> item.isJsonPrimitive() ? item.getAsString()
                        : item.isJsonObject() ? firstString(item.getAsJsonObject(), "question", "meaning", "title") : "")
                .filter(value -> !value.isBlank())
                .sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    static int evidenceResolutionCount(JsonObject report) {
        return report.has("evidence_resolutions") && report.get("evidence_resolutions").isJsonArray()
                ? report.getAsJsonArray("evidence_resolutions").size() : 0;
    }

    static String planIdentity(DomainEvidencePlan plan) {
        StringBuilder identity = new StringBuilder();
        plan.candidatePaths().forEach(path -> identity.append("P:").append(path).append('\n'));
        plan.queries().forEach(query -> identity.append("Q:").append(query.literal()).append('\n'));
        return identity.toString();
    }

    static String sourceEvidenceIdentity(String sourceEvidence) {
        try {
            JsonObject root = JsonParser.parseString(sourceEvidence).getAsJsonObject();
            StringBuilder identity = new StringBuilder();
            JsonArray controlFlow = root.has("control_flow_excerpts") && root.get("control_flow_excerpts").isJsonArray()
                    ? root.getAsJsonArray("control_flow_excerpts")
                    : new JsonArray();
            if (!controlFlow.isEmpty()) {
                appendEvidenceSources(identity, controlFlow);
                return identity.toString();
            }
            JsonArray queryResults = root.has("query_results") && root.get("query_results").isJsonArray()
                    ? root.getAsJsonArray("query_results")
                    : new JsonArray();
            for (JsonElement item : queryResults) {
                if (item.isJsonObject()) appendEvidenceSources(identity, item.getAsJsonObject().get("matches"));
            }
            if (identity.isEmpty()) appendEvidenceSources(identity, root.get("candidate_excerpts"));
            return identity.toString();
        } catch (RuntimeException ignored) {
            return sourceEvidence;
        }
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

    private static void appendEvidenceSources(StringBuilder identity, JsonElement sources) {
        if (sources == null || !sources.isJsonArray()) return;
        for (JsonElement item : sources.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject source = item.getAsJsonObject();
            identity.append(string(source, "path")).append(':')
                    .append(string(source, "matched_line")).append(':')
                    .append(string(source, "excerpt")).append(string(source, "snippet")).append('\n');
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

    private static String followUpEvidenceInstruction(Set<String> attemptedQueries) {
        String tried = attemptedQueries.stream().limit(40).collect(java.util.stream.Collectors.joining(", "));
        return AUTO_EVIDENCE_INSTRUCTION
                + "\n已经检索过这些符号或字面量，不要原样重复：" + tried
                + "。从当前报告新暴露的精确调用、表、状态或事件符号选择下一层证据。";
    }

    private JsonObject assembleAndValidateBusinessReport(
            String response,
            AnalysisRequest request,
            EvidencePack evidence,
            CodexWorkspaceService.Workspace workspace,
            ProgressIndicator indicator,
            Consumer<String> statusListener
    ) throws Exception {
        JsonObject assembled = domainReportAssembler.assemble(response, request, evidence);
        return validator.validate(GSON.toJson(assembled), evidence, workspace.root());
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

    private static int countPatchFiles(String patch) {
        return (int) patch.lines().filter(line -> line.startsWith("diff --git ")).count();
    }

    private record DomainConvergence(JsonObject report, int evidenceRounds, String stopReason) {
    }

    private void publish(
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            String message,
            double fraction
    ) {
        indicator.setText(message);
        indicator.setFraction(fraction);
        statusListener.accept(message);
    }
}
