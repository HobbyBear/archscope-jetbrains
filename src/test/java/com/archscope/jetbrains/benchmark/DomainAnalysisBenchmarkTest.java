package com.archscope.jetbrains.benchmark;

import com.archscope.jetbrains.analysis.ArchitectureAnalysisService;
import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisGuidance;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.EvidencePack;
import com.archscope.jetbrains.render.ReportRenderer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

final class DomainAnalysisBenchmarkTest {
    @Test
    @EnabledIfSystemProperty(named = "archscope.domainBenchmarkRepo", matches = ".+")
    void analyzesAndRefinesARealBusinessTopic() throws Exception {
        Path repository = Path.of(System.getProperty("archscope.domainBenchmarkRepo")).toAbsolutePath().normalize();
        String prompt = System.getProperty("archscope.domainBenchmarkPrompt", "分析聊天逻辑");
        Path output = Path.of(System.getProperty("archscope.domainBenchmarkOutput")).toAbsolutePath().normalize();
        String refineInputProperty = System.getProperty("archscope.domainRefineInput", "").strip();
        String customPrompt = System.getProperty("archscope.domainCustomPrompt", "").strip();
        if (customPrompt.isBlank()) {
            String context = System.getProperty("archscope.domainBusinessContext", "").strip();
            String reading = System.getProperty("archscope.domainCodeReadingPrompt", "").strip();
            customPrompt = context.isBlank() ? reading : reading.isBlank() ? context : context + "\n\n" + reading;
        }
        AnalysisGuidance guidance = new AnalysisGuidance(
                customPrompt,
                System.getProperty("archscope.domainSystemPrompt", "")
        );
        AnalysisRequest.OutputLanguage outputLanguage = "en".equalsIgnoreCase(
                System.getProperty("archscope.domainBenchmarkLanguage", "zh-CN"))
                ? AnalysisRequest.OutputLanguage.ENGLISH
                : AnalysisRequest.OutputLanguage.CHINESE;
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, prompt, guidance, outputLanguage);
        ProgressIndicator indicator = indicator();
        long startedAt = System.nanoTime();
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator);
        long evidenceMs = elapsed(startedAt);
        ArchitectureAnalysisService service = new ArchitectureAnalysisService();
        AnalysisResult result;
        if (refineInputProperty.isEmpty()) {
            result = service.analyze(request, evidence, indicator);
        } else {
            String reportJson = Files.readString(Path.of(refineInputProperty).toAbsolutePath().normalize());
            String reportHtml = new ReportRenderer().render(JsonParser.parseString(reportJson).getAsJsonObject(), false);
            result = new AnalysisResult(reportJson, reportHtml, evidence.fingerprint(), evidence.targetCommit());
        }
        write(output, result.reportJson());
        String htmlOutputProperty = System.getProperty("archscope.domainBenchmarkHtmlOutput", "").strip();
        if (!htmlOutputProperty.isEmpty()) {
            write(Path.of(htmlOutputProperty).toAbsolutePath().normalize(), result.reportHtml());
        }
        long initialMs = elapsed(startedAt);
        JsonObject resultJson = JsonParser.parseString(result.reportJson()).getAsJsonObject();
        System.out.println("DOMAIN_BENCHMARK initial_ms=" + initialMs
                + " evidence_ms=" + evidenceMs
                + " report_chars=" + result.reportJson().length()
                + " quality=" + qualitySummary(resultJson)
                + " diagnostics=" + resultJson.get("analysis_diagnostics")
                + " output=" + output);

        String refinePrompt = System.getProperty("archscope.domainRefinePrompt", "").strip();
        if (!refinePrompt.isEmpty()) {
            Path refineOutput = Path.of(System.getProperty("archscope.domainRefineOutput")).toAbsolutePath().normalize();
            long refineStartedAt = System.nanoTime();
            AnalysisResult refined = service.refine(
                    request,
                    evidence,
                    result.reportJson(),
                    refinePrompt,
                    indicator,
                    ignored -> {}
            );
            write(refineOutput, refined.reportJson());
            String refineHtmlOutputProperty = System.getProperty("archscope.domainRefineHtmlOutput", "").strip();
            if (!refineHtmlOutputProperty.isEmpty()) {
                write(Path.of(refineHtmlOutputProperty).toAbsolutePath().normalize(), refined.reportHtml());
            }
            System.out.println("DOMAIN_BENCHMARK refinement_ms=" + elapsed(refineStartedAt)
                    + " report_chars=" + refined.reportJson().length()
                    + " quality=" + qualitySummary(JsonParser.parseString(refined.reportJson()).getAsJsonObject())
                    + " diagnostics=" + JsonParser.parseString(refined.reportJson()).getAsJsonObject().get("analysis_diagnostics")
                    + " output=" + refineOutput);
        }
    }

    private String qualitySummary(JsonObject report) {
        JsonObject flowMap = report.has("flow_map") && report.get("flow_map").isJsonObject()
                ? report.getAsJsonObject("flow_map") : new JsonObject();
        int flows = flowMap.has("children") && flowMap.get("children").isJsonArray()
                ? flowMap.getAsJsonArray("children").size() : 0;
        int steps = flowMap.has("children") && flowMap.get("children").isJsonArray()
                ? flowMap.getAsJsonArray("children").asList().stream()
                .filter(item -> item.isJsonObject())
                .mapToInt(item -> item.getAsJsonObject().has("children")
                        && item.getAsJsonObject().get("children").isJsonArray()
                        ? item.getAsJsonObject().getAsJsonArray("children").size() : 0)
                .sum() : 0;
        int unknowns = report.has("unknowns") && report.get("unknowns").isJsonArray()
                ? report.getAsJsonArray("unknowns").size() : 0;
        return flows + "flows/" + steps + "steps/" + unknowns + "unknowns";
    }

    private void write(Path output, String content) throws Exception {
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, content);
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private ProgressIndicator indicator() {
        AtomicReference<String> lastText = new AtomicReference<>("");
        return (ProgressIndicator) Proxy.newProxyInstance(
                ProgressIndicator.class.getClassLoader(),
                new Class<?>[]{ProgressIndicator.class},
                (proxy, method, args) -> {
                    if ("setText".equals(method.getName()) && args != null && args.length == 1) {
                        String next = String.valueOf(args[0]);
                        if (!next.equals(lastText.getAndSet(next))) System.out.println("DOMAIN_PROGRESS " + next);
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                }
        );
    }
}
