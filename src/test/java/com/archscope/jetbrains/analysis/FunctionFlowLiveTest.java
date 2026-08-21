package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.AnalysisGuidance;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.EvidencePack;
import com.archscope.jetbrains.model.FunctionTarget;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FunctionFlowLiveTest {
    @Test
    @EnabledIfSystemProperty(named = "archscope.liveFunctionRepository", matches = ".+")
    void runsOneReadOnlyClaudeSopWithinTheToolBudget() throws Exception {
        Path repository = Path.of(System.getProperty("archscope.liveFunctionRepository"))
                .toAbsolutePath().normalize();
        Path workingDirectory = repository.resolve("apps/chat");
        Path findings = workingDirectory.resolve(".repomind/.query-findings.json");
        assertFalse(Files.exists(findings), "live test requires no pre-existing RepoMind findings file");

        FunctionTarget target = new FunctionTarget(
                workingDirectory,
                "service/video_sd.go",
                "videoSD.genVideo",
                "func (slf *videoSD) genVideo(item map[string]interface{}) (string, error)",
                347,
                458
        );
        AnalysisRequest request = AnalysisRequest.functionFlow(
                workingDirectory,
                target,
                new AnalysisGuidance("分析代码时可以参考repomind知识库，并且使用相关skill进行查询"),
                AnalysisRequest.OutputLanguage.CHINESE
        ).withCliWorkingDirectory(workingDirectory);
        ProgressIndicator indicator = indicator();
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator);

        AnalysisResult result = assertTimeoutPreemptively(Duration.ofMinutes(6), () ->
                new FunctionFlowAnalysisService(new ClaudeCliModelClient()).analyze(
                        request,
                        evidence,
                        target,
                        "分析下生成逻辑，尽可能详细点",
                        indicator,
                        ignored -> {}
                ));

        JsonObject report = JsonParser.parseString(result.reportJson()).getAsJsonObject();
        JsonObject diagnostics = report.getAsJsonObject("analysis_diagnostics");
        assertEquals(1, diagnostics.get("model_calls").getAsInt());
        assertEquals(1, diagnostics.get("sop_sessions").getAsInt());
        assertEquals("read_only_repository", diagnostics.get("workspace_access").getAsString());
        assertTrue(report.getAsJsonArray("nodes").size() > 1);
        assertFalse(Files.exists(findings), "function analysis must not write RepoMind findings");

        Path auditRoot = Path.of(System.getProperty("archscope.modelAuditDir"));
        Path latest;
        try (var entries = Files.list(auditRoot)) {
            latest = entries.filter(Files::isDirectory).max(Comparator.naturalOrder()).orElseThrow();
        }
        JsonObject summary = JsonParser.parseString(Files.readString(latest.resolve("summary.json"))).getAsJsonObject();
        String events = Files.readString(latest.resolve("events.jsonl"));
        assertEquals("completed", summary.get("status").getAsString());
        assertTrue(summary.get("tool_call_count").getAsInt() <= 16);
        assertTrue(summary.get("repomind_runtime_evidence").getAsBoolean());
        assertTrue(summary.get("repomind_skill_loaded").getAsBoolean());
        assertFalse(summary.get("repomind_summary_used").getAsBoolean());
        assertFalse(events.contains("\"tool_name\":\"Write\""));
        assertFalse(events.contains("repomind-summary"));
    }

    private ProgressIndicator indicator() {
        return (ProgressIndicator) Proxy.newProxyInstance(
                ProgressIndicator.class.getClassLoader(),
                new Class<?>[]{ProgressIndicator.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == double.class ? 0.0 : null
        );
    }
}
