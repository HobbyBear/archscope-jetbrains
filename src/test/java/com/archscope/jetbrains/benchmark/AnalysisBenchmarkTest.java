package com.archscope.jetbrains.benchmark;

import com.archscope.jetbrains.analysis.ArchitectureAnalysisService;
import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class AnalysisBenchmarkTest {
    @Test
    @EnabledIfSystemProperty(named = "archscope.benchmarkRepo", matches = ".+")
    void runRealSelectedCommitAnalysis() throws Exception {
        String repositoryProperty = System.getProperty("archscope.benchmarkRepo", "");
        String commitsProperty = System.getProperty("archscope.benchmarkCommits", "");
        String outputProperty = System.getProperty("archscope.benchmarkOutput", "");
        if (commitsProperty.isBlank() || outputProperty.isBlank()) {
            throw new IllegalArgumentException("Benchmark commit and output properties are required");
        }

        Path repository = Path.of(repositoryProperty).toAbsolutePath().normalize();
        Path output = Path.of(outputProperty).toAbsolutePath().normalize();
        List<CommitInfo> commits = Arrays.stream(commitsProperty.split(","))
                .filter(value -> !value.isBlank())
                .map(hash -> new CommitInfo(hash, List.of(), "", "1970-01-01T00:00:00Z", hash))
                .toList();
        AnalysisRequest request = new AnalysisRequest(
                repository,
                commits,
                commits.get(0).hash(),
                "完整解释所选提交合并后改了什么、每个改动位于业务流程哪里，以及最终改变了什么行为。"
        );
        ProgressIndicator indicator = indicator();
        long startedAt = System.nanoTime();
        EvidencePack evidence = new GitEvidenceService().collect(request, indicator);
        long evidenceMs = elapsed(startedAt);
        AnalysisResult result = new ArchitectureAnalysisService().analyze(request, evidence, indicator);
        long totalMs = elapsed(startedAt);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, result.reportJson());
        System.out.println("BENCHMARK_RESULT evidence_ms=" + evidenceMs
                + " total_ms=" + totalMs
                + " report_chars=" + result.reportJson().length()
                + " base=" + evidence.baseCommit()
                + " target=" + evidence.targetCommit()
                + " changed_paths=" + evidence.aggregateChangedPaths().size()
                + " output=" + output);
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
                        if (!next.equals(lastText.getAndSet(next))) System.out.println("BENCHMARK_PROGRESS " + next);
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                }
        );
    }
}
