package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.CodexWorkspaceService;
import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainEvidenceExpansionServiceTest {
    @TempDir
    Path repository;

    @Test
    void reservesCandidateEvidenceForRelatedFunctionBodies() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("src/chat.go");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package chat

                func Entry(qs map[string]string) bool {
                    chargeAfterStreamComplete := qs["charge_after_stream_complete"] == "1"
                    return shouldChargeChat(chargeAfterStreamComplete, "completed", true)
                }

                func shouldChargeChat(waitForStreamComplete bool, streamResult string, hasAnswer bool) bool {
                    return !waitForStreamComplete || (streamResult == "completed" && hasAnswer)
                }
                """);
        git("add", "src/chat.go");
        git("commit", "-m", "chat billing");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析聊天扣费");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/chat.go"],
                 "queries":[{"literal":"charge_after_stream_complete","role":"rule","reason":"billing"}]}
                """, evidence);

        try (CodexWorkspaceService.Workspace workspace = new CodexWorkspaceService().createSnapshot(evidence, indicator())) {
            String expanded = new DomainEvidenceExpansionService().expand(plan, evidence, workspace, indicator());
            assertTrue(expanded.contains("func shouldChargeChat"));
            assertTrue(expanded.contains("completed"));
            assertTrue(expanded.contains("hasAnswer"));
        }
    }

    @Test
    void groupsDistantStateEffectsFromOneLargeFunction() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("controllers/ws.go");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package controllers

                func Chat() bool {
                    streamingKey := "streaming"
                    pendingStreamingKey := "pending_" + streamingKey
                    inprockey := "inproc/chat"
                    addTimesKey := "add_times"
                    pendingStreamingSeq[pendingStreamingKey] = false
                    defer delete(pendingStreamingSeq, pendingStreamingKey)
                """ + "    // unrelated setup\n".repeat(90) + """
                    if !CheckChatContent() {
                        inproc[inprockey] = inproc[inprockey] - 1
                        return false
                    }
                """ + "    // model preparation\n".repeat(90) + """
                    streamingSeq[streamingKey] = true
                """ + "    // stream delivery\n".repeat(90) + """
                    if shouldChargeChat(true, "completed", true) {
                        addTimes[addTimesKey] = addTimes[addTimesKey] - 1
                    }
                    return true
                }

                func shouldChargeChat(wait bool, result string, hasAnswer bool) bool {
                    return !wait || (result == "completed" && hasAnswer)
                }
                """);
        git("add", "controllers/ws.go");
        git("commit", "-m", "chat state flow");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析敏感问题拒绝后的状态清理和扣费");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["controllers/ws.go"],
                 "queries":[
                   {"literal":"CheckChatContent","role":"failure","reason":"rejection"},
                   {"literal":"pendingStreamingKey","role":"state","reason":"cleanup"},
                   {"literal":"streamingSeq","role":"state","reason":"registration"},
                   {"literal":"addTimesKey","role":"state","reason":"billing"},
                   {"literal":"shouldChargeChat","role":"rule","reason":"billing rule"}]}
                """, evidence);

        try (CodexWorkspaceService.Workspace workspace = new CodexWorkspaceService().createSnapshot(evidence, indicator())) {
            String expanded = new DomainEvidenceExpansionService().expand(plan, evidence, workspace, indicator());
            var json = com.google.gson.JsonParser.parseString(expanded).getAsJsonObject();
            String grouped = json.getAsJsonArray("control_flow_excerpts").toString();
            assertTrue(grouped.contains("function_scope"));
            assertTrue(grouped.contains("defer delete"));
            assertTrue(grouped.contains("inproc[inprockey]"));
            assertTrue(grouped.contains("streamingSeq[streamingKey]"));
            assertTrue(grouped.contains("addTimes[addTimesKey]"));
            assertTrue(grouped.contains("func shouldChargeChat"));
        }
    }

    @Test
    void oneMiddleHitIncludesTheCompleteEnclosingFunction() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("controllers/complete.go");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package controllers

                func Chat() bool {
                    defer delete(pendingStreamingSeq, "pending")
                """ + "    // setup\n".repeat(120) + """
                    if !CheckChatContent() {
                        inproc["chat"]--
                        return false
                    }
                """ + "    // streaming\n".repeat(120) + """
                    addTimes["chat"]--
                    return true
                }
                """);
        git("add", "controllers/complete.go");
        git("commit", "-m", "complete function evidence");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析敏感问题拒绝后的完整状态变化");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["controllers/complete.go"],
                 "queries":[{"literal":"CheckChatContent","role":"failure","reason":"rejection"}]}
                """, evidence);

        try (CodexWorkspaceService.Workspace workspace = new CodexWorkspaceService().createSnapshot(evidence, indicator())) {
            String expanded = new DomainEvidenceExpansionService().expand(plan, evidence, workspace, indicator());
            var json = com.google.gson.JsonParser.parseString(expanded).getAsJsonObject();
            String grouped = json.getAsJsonArray("control_flow_excerpts").toString();
            assertTrue(grouped.contains("complete_function_scope"));
            assertTrue(grouped.contains("defer delete(pendingStreamingSeq"));
            assertTrue(grouped.contains("inproc[\\\"chat\\\"]--"));
            assertTrue(grouped.contains("addTimes[\\\"chat\\\"]--"));
        }
    }

    @Test
    void deduplicatesCandidateAndQueryLinesAlreadyCoveredByCompleteScopes() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("controllers/deduplicated.go");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package controllers

                func Chat() bool {
                    return shouldChargeChat(true)
                }

                func shouldChargeChat(hasAnswer bool) bool {
                    return hasAnswer
                }
                """);
        git("add", "controllers/deduplicated.go");
        git("commit", "-m", "deduplicated evidence");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析聊天扣费");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["controllers/deduplicated.go"],
                 "queries":[{"literal":"shouldChargeChat","role":"rule","reason":"billing"}]}
                """, evidence);

        try (CodexWorkspaceService.Workspace workspace = new CodexWorkspaceService().createSnapshot(evidence, indicator())) {
            String expanded = new DomainEvidenceExpansionService().expand(plan, evidence, workspace, indicator());
            var json = com.google.gson.JsonParser.parseString(expanded).getAsJsonObject();
            String control = json.getAsJsonArray("control_flow_excerpts").toString();
            var candidateEvidence = json.getAsJsonArray("candidate_excerpts").get(0).getAsJsonObject();
            String candidate = candidateEvidence.get("excerpt").getAsString();
            var query = json.getAsJsonArray("query_results").get(0).getAsJsonObject();

            assertEquals("shouldChargeChat", query.get("literal").getAsString());
            assertEquals("rule", query.get("role").getAsString());
            assertEquals("billing", query.get("reason").getAsString());
            assertTrue(control.contains("return shouldChargeChat(true)"));
            assertTrue(control.contains("return hasAnswer"));
            assertTrue(candidate.contains("package controllers"));
            assertFalse(candidate.contains("return shouldChargeChat(true)"));
            assertFalse(candidateEvidence.getAsJsonArray("source_refs").isEmpty());
            for (var matchElement : query.getAsJsonArray("matches")) {
                var match = matchElement.getAsJsonObject();
                assertEquals("controllers/deduplicated.go", match.get("path").getAsString());
                assertTrue(match.get("matched_line").getAsInt() > 0);
                assertFalse(match.get("snippet").getAsString().contains("shouldChargeChat"));
                assertFalse(match.getAsJsonArray("source_refs").isEmpty());
            }
            assertTrue(json.get("deduplicated_chars").getAsInt() > 0);
            assertEquals(
                    json.get("original_evidence_chars").getAsInt() - json.get("deduplicated_chars").getAsInt(),
                    json.get("evidence_chars").getAsInt()
            );
            assertTrue(json.get("unique_source_chars").getAsInt() > 0);
            assertTrue(json.get("unique_source_chars").getAsInt() <= json.get("evidence_chars").getAsInt());
        }
    }

    @Test
    void keepsQuerySnippetWhenCompleteScopeIsOmittedByBudget() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("controllers/oversized.go");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package controllers

                func HugeChat() bool {
                    started := true
                """ + "    // enough unrelated source to exceed the complete scope evidence budget\n".repeat(1800) + """
                    needleValue := true
                    persistAtFunctionTail()
                    return needleValue
                }
                """);
        git("add", "controllers/oversized.go");
        git("commit", "-m", "oversized function evidence");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析超大函数");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["controllers/oversized.go"],
                 "queries":[{"literal":"needleValue","role":"state","reason":"oversized scope"}]}
                """, evidence);

        try (CodexWorkspaceService.Workspace workspace = new CodexWorkspaceService().createSnapshot(evidence, indicator())) {
            String expanded = new DomainEvidenceExpansionService().expand(plan, evidence, workspace, indicator());
            var json = com.google.gson.JsonParser.parseString(expanded).getAsJsonObject();
            String control = json.getAsJsonArray("control_flow_excerpts").toString();
            var match = json.getAsJsonArray("query_results").get(0).getAsJsonObject()
                    .getAsJsonArray("matches").get(0).getAsJsonObject();

            assertTrue(control.contains("partial_function_scope"));
            assertTrue(control.contains("started := true"));
            assertTrue(control.contains("persistAtFunctionTail()"));
            assertTrue(match.get("snippet").getAsString().contains("needleValue := true"));
            assertFalse(match.has("source_refs"));
        }
    }

    private String git(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repository.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
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
