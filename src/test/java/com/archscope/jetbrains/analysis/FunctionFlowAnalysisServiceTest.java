package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.AnalysisGuidance;
import com.archscope.jetbrains.model.EvidencePack;
import com.archscope.jetbrains.model.FunctionTarget;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FunctionFlowAnalysisServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsDetailedFunctionReportAndNormalizesCliRelativePaths() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Path working = repository.resolve("apps/chat");
        Files.createDirectories(working.resolve("service"));
        FunctionTarget target = new FunctionTarget(repository, "apps/chat/service/score.go",
                "ScoreService.Calculate", "func (s *ScoreService) Calculate()", 10, 80);
        AnalysisRequest request = AnalysisRequest.functionFlow(
                repository, target, new AnalysisGuidance("使用 RepoMind skill 查询业务知识"),
                AnalysisRequest.OutputLanguage.CHINESE).withCliWorkingDirectory(working);
        QueueModelClient model = new QueueModelClient(validResponse("service/score.go"));

        AnalysisResult result = new FunctionFlowAnalysisService(model).analyze(
                request, evidence(repository), target, "重点分析拒绝分支",
                indicator(), ignored -> {});
        JsonObject report = JsonParser.parseString(result.reportJson()).getAsJsonObject();

        assertEquals("function_flow", report.get("report_type").getAsString());
        assertEquals(target.stableId(), report.getAsJsonObject("function_target").get("stable_id").getAsString());
        assertEquals("apps/chat/service/score.go",
                report.getAsJsonArray("nodes").get(0).getAsJsonObject().get("file").getAsString());
        JsonObject edge = report.getAsJsonArray("edges").get(0).getAsJsonObject();
        assertEquals("alternative", edge.get("execution").getAsString());
        assertEquals(10, edge.get("line").getAsInt());
        assertTrue(result.reportHtml().contains("class=\"diagram flow-diagram\""));
        assertTrue(result.reportHtml().contains("archscopeRefineReport"));
        assertTrue(result.reportHtml().contains("data-source"));
        assertTrue(model.lastUserPrompt.contains("no fixed numeric depth"));
        assertTrue(model.lastUserPrompt.contains("implementation-body budget"));
        assertTrue(model.lastSystemPrompt.contains("Use at most 12 tool calls total"));
        assertTrue(model.lastUserPrompt.contains("file relative to Git repository root: apps/chat/service/score.go"));
        assertTrue(model.lastUserPrompt.contains("Never guess a source range"));
        assertTrue(model.lastUserPrompt.contains("A node may have at most one next edge"));
        assertTrue(model.lastUserPrompt.contains("Use parallel only when source proves concurrent execution"));
        assertTrue(model.lastSystemPrompt.contains("Never invoke repomind-summary"));
        assertTrue(model.lastSystemPrompt.contains("Invoke the repomind-query Skill exactly once"));
        assertTrue(model.lastUserPrompt.contains("重点分析拒绝分支"));
        assertEquals(1, model.callCount);
        assertEquals(ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY, model.lastWorkspaceAccess);
        assertEquals(1, report.getAsJsonObject("analysis_diagnostics").get("model_calls").getAsInt());
        assertEquals(1, report.getAsJsonObject("analysis_diagnostics").get("sop_sessions").getAsInt());
    }

    @Test
    void rejectsSourcePathsOutsideTheTargetManifest() {
        Path repository = temporaryDirectory.resolve("repo");
        FunctionTarget target = new FunctionTarget(repository, "apps/chat/service/score.go",
                "Calculate", "func Calculate()", 1, 10);
        AnalysisRequest request = AnalysisRequest.functionFlow(
                repository, target, null, AnalysisRequest.OutputLanguage.CHINESE).withCliWorkingDirectory(repository);

        assertThrows(ModelClientException.class, () -> new FunctionFlowAnalysisService(
                new QueueModelClient(validResponse("invented/missing.go"))
        ).analyze(request, evidence(repository), target, "", indicator(), ignored -> {}));
    }

    @Test
    void refinementExpandsOnTopOfTheVerifiedGraphAndKeepsRevisionHistory() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository.resolve("apps/chat/service"));
        FunctionTarget target = new FunctionTarget(repository, "apps/chat/service/score.go",
                "Calculate", "func Calculate()", 1, 10);
        AnalysisRequest request = AnalysisRequest.functionFlow(
                repository, target, null, AnalysisRequest.OutputLanguage.CHINESE).withCliWorkingDirectory(repository);
        QueueModelClient model = new QueueModelClient(validResponse("apps/chat/service/score.go"));
        FunctionFlowAnalysisService service = new FunctionFlowAnalysisService(model);
        AnalysisResult initial = service.analyze(
                request, evidence(repository), target, "", indicator(), ignored -> {});
        model.responses.add(expandedResponse("apps/chat/service/score.go"));

        AnalysisResult refined = service.refine(
                request, evidence(repository), initial.reportJson(), "继续展开评分规则函数并补充错误分支",
                indicator(), ignored -> {});
        JsonObject report = JsonParser.parseString(refined.reportJson()).getAsJsonObject();

        assertEquals("novice_complete", report.getAsJsonObject("function_target").get("expansion_policy").getAsString());
        assertEquals(3, report.getAsJsonArray("nodes").size());
        assertEquals(1, report.getAsJsonArray("revision_history").size());
        assertEquals(2, model.callCount);
        assertTrue(model.lastUserPrompt.contains("keep every existing node and edge"));
        assertTrue(model.lastUserPrompt.contains("within this action's evidence budget"));
    }

    @Test
    void preservesModelReportedDepthBeyondThree() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Path file = repository.resolve("apps/chat/service/score.go");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "\n".repeat(80));
        FunctionTarget target = new FunctionTarget(repository, "apps/chat/service/score.go",
                "Calculate", "func Calculate()", 1, 10);
        AnalysisRequest request = AnalysisRequest.functionFlow(
                repository, target, null, AnalysisRequest.OutputLanguage.CHINESE).withCliWorkingDirectory(repository);

        AnalysisResult result = new FunctionFlowAnalysisService(new QueueModelClient(
                validResponse("apps/chat/service/score.go").replace(
                        "\"kind\":\"decision\",\"depth\":0", "\"kind\":\"decision\",\"depth\":7")
        )).analyze(request, evidence(repository), target, "", indicator(), ignored -> {});

        assertEquals(7, JsonParser.parseString(result.reportJson()).getAsJsonObject()
                .getAsJsonArray("nodes").get(1).getAsJsonObject().get("depth").getAsInt());
    }

    @Test
    void rejectsExpansionThatDropsExistingGraphStructure() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        FunctionTarget target = new FunctionTarget(repository, "apps/chat/service/score.go",
                "Calculate", "func Calculate()", 1, 10);
        AnalysisRequest request = AnalysisRequest.functionFlow(
                repository, target, null, AnalysisRequest.OutputLanguage.CHINESE).withCliWorkingDirectory(repository);
        QueueModelClient model = new QueueModelClient(validResponse("apps/chat/service/score.go"));
        FunctionFlowAnalysisService service = new FunctionFlowAnalysisService(model);
        AnalysisResult initial = service.analyze(
                request, evidence(repository), target, "", indicator(), ignored -> {});
        model.responses.add(rootOnlyResponse("apps/chat/service/score.go"));

        assertThrows(ModelClientException.class, () -> service.refine(
                request, evidence(repository), initial.reportJson(), "继续展开 Calculate",
                indicator(), ignored -> {}));
    }

    private EvidencePack evidence(Path repository) {
        return new EvidencePack(
                repository, "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("apps/chat/service/score.go"));
    }

    private ProgressIndicator indicator() {
        return (ProgressIndicator) Proxy.newProxyInstance(
                ProgressIndicator.class.getClassLoader(),
                new Class<?>[]{ProgressIndicator.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == double.class ? 0.0 : null
        );
    }

    private String validResponse(String file) {
        return """
                {
                  "title":"评分函数流程","summary":"计算并保存评分",
                  "nodes":[
                    {"id":"root","symbol":"Calculate","label":"计算评分","kind":"entry","depth":0,
                     "file":"%s","line":10,"end_line":40,"business_role":"接收人设并计算评分","logic":"组合规则",
                     "inputs":["character"],"outputs":["score"],"conditions":[],"side_effects":[]},
                    {"id":"reject","symbol":"Calculate","label":"拒绝无效输入","kind":"decision","depth":0,
                     "file":"%s","line":14,"end_line":18,"business_role":"校验","logic":"输入为空时返回",
                     "inputs":[],"outputs":[],"conditions":["character为空"],"side_effects":[]}
                  ],
                  "edges":[{"id":"e1","from":"root","to":"reject","kind":"branch","label":"无效输入"}],
                  "unknowns":[]
                }
                """.formatted(file, file);
    }

    private String expandedResponse(String file) {
        return """
                {
                  "title":"评分函数流程","summary":"计算并保存评分",
                  "nodes":[
                    {"id":"root","symbol":"Calculate","label":"计算评分","kind":"entry","depth":0,
                     "file":"%s","line":10,"end_line":40,"business_role":"接收人设并计算评分","logic":"组合规则",
                     "inputs":["character"],"outputs":["score"],"conditions":[],"side_effects":[]},
                    {"id":"reject","symbol":"Calculate","label":"拒绝无效输入","kind":"decision","depth":0,
                     "file":"%s","line":14,"end_line":18,"business_role":"校验","logic":"输入为空时返回",
                     "inputs":[],"outputs":[],"conditions":["character为空"],"side_effects":[]},
                    {"id":"rules","symbol":"calculateRuleScores","label":"逐条计算规则分","kind":"function","depth":1,
                     "file":"%s","line":41,"end_line":60,"business_role":"执行评分规则","logic":"汇总每条规则结果",
                     "inputs":["character"],"outputs":["ruleScores"],"conditions":[],"side_effects":[],"expandable":false}
                  ],
                  "edges":[
                    {"id":"e1","from":"root","to":"reject","kind":"branch","label":"无效输入"},
                    {"id":"e2","from":"root","to":"rules","kind":"call","label":"有效输入"}
                  ],
                  "unknowns":[]
                }
                """.formatted(file, file, file);
    }

    private String rootOnlyResponse(String file) {
        return """
                {
                  "title":"评分函数流程","summary":"计算评分",
                  "nodes":[
                    {"id":"root","symbol":"Calculate","label":"计算评分","kind":"entry","depth":0,
                     "file":"%s","line":10,"end_line":40,"business_role":"计算评分","logic":"组合规则",
                     "inputs":[],"outputs":[],"conditions":[],"side_effects":[]}
                  ],
                  "edges":[],"unknowns":[]
                }
                """.formatted(file);
    }

    private static final class QueueModelClient implements ModelClient {
        private final Queue<String> responses = new ArrayDeque<>();
        private String lastSystemPrompt = "";
        private String lastUserPrompt = "";
        private int callCount;
        private WorkspaceAccess lastWorkspaceAccess;

        private QueueModelClient(String response) {
            responses.add(response);
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String displayName() {
            return "Test model";
        }

        @Override
        public String complete(
                String systemPrompt,
                String userPrompt,
                Path workingDirectory,
                ProgressIndicator indicator,
                String stage,
                Consumer<String> statusListener,
                WorkspaceAccess workspaceAccess
        ) {
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = userPrompt;
            callCount++;
            lastWorkspaceAccess = workspaceAccess;
            return responses.remove();
        }
    }
}
