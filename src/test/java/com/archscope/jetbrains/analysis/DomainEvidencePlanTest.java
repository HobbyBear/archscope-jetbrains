package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainEvidencePlanTest {
    @Test
    void parsesLanguageIndependentEditIntentAndKeepsEvidenceFreePlansEmpty() throws Exception {
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1",
                 "refinement_intent":{"operations":["merge_domains","reorder_nodes"],
                   "target_domain_ids":["review","publish"],"target_step_ids":["notify"],
                   "requested_topics":[],"evidence_required":false},
                 "candidate_paths":[],"queries":[]}
                """, evidence);

        assertTrue(plan.editIntent().has(DomainEvidencePlan.Operation.MERGE_DOMAINS));
        assertTrue(plan.editIntent().has(DomainEvidencePlan.Operation.REORDER_NODES));
        assertEquals(List.of("review", "publish"), plan.editIntent().targetDomainIds());
        assertTrue(plan.candidatePaths().isEmpty());
        assertTrue(plan.queries().isEmpty());
    }

    @Test
    void parsesPlainTextPlanningSlotsWithoutRequiringModelJson() throws Exception {
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                SCHEMA\tbusiness-domain-evidence-plan/v1
                TOPIC\tfix review flow
                OPERATIONS\tcorrect_flow,add_nodes
                TARGET_FLOW_IDS\treview-flow
                TARGET_STEP_IDS\treview-result
                REQUESTED_TOPICS\trejection notice
                EVIDENCE_REQUIRED\ttrue
                CANDIDATE_PATH\tsrc/chat.go
                QUERY\tReviewResult\tstate\tlocate the actual result state
                """, evidence);

        assertTrue(plan.editIntent().has(DomainEvidencePlan.Operation.CORRECT_FLOW));
        assertTrue(plan.editIntent().has(DomainEvidencePlan.Operation.ADD_NODES));
        assertEquals(List.of("review-flow"), plan.editIntent().targetFlowIds());
        assertEquals(List.of("src/chat.go"), plan.candidatePaths());
        assertEquals("ReviewResult", plan.queries().get(0).literal());
    }
    @Test
    void filtersBroadQueriesAndAddsSymbolsFromCurrentUnknowns() throws Exception {
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/chat.go"],
                 "queries":[{"literal":"Chat","role":"entry","reason":"broad"},
                            {"literal":"Creator","role":"entry","reason":"topic name"},
                            {"literal":"charge_after_stream_complete","role":"rule","reason":"billing"}]}
                """, evidence).withUnresolvedQueries("""
                {"unknowns":["shouldChargeChat 的函数体未知", "AddChatHistory 的调用条件未知", "source_evidence 未展示"]}
                """);

        List<String> literals = plan.queries().stream().map(DomainEvidencePlan.Query::literal).toList();
        assertFalse(literals.contains("Chat"));
        assertFalse(literals.contains("Creator"));
        assertTrue(literals.contains("charge_after_stream_complete"));
        assertTrue(literals.contains("shouldChargeChat"));
        assertTrue(literals.contains("AddChatHistory"));
        assertFalse(literals.contains("source_evidence"));

        DomainEvidencePlan unresolvedOnly = plan.unresolvedOnly("""
                {"unknowns":["Finish 调用未知", "QueryLastCharacterAuditInfo 的字段映射未知"]}
                """);
        List<String> unresolvedLiterals = unresolvedOnly.queries().stream()
                .map(DomainEvidencePlan.Query::literal).toList();
        assertTrue(unresolvedLiterals.contains("Finish"));
        assertTrue(unresolvedLiterals.contains("QueryLastCharacterAuditInfo"));
        assertFalse(unresolvedLiterals.contains("charge_after_stream_complete"));
        assertTrue(unresolvedOnly.candidatePaths().isEmpty());

        DomainEvidencePlan newFrontier = unresolvedOnly.excludingQueries(Set.of("Finish"));
        List<String> newLiterals = newFrontier.queries().stream()
                .map(DomainEvidencePlan.Query::literal).toList();
        assertFalse(newLiterals.contains("Finish"));
        assertTrue(newLiterals.contains("QueryLastCharacterAuditInfo"));

        DomainEvidencePlan sourceBound = unresolvedOnly.retainingQueriesIn("only Finish is present");
        List<String> sourceBoundLiterals = sourceBound.queries().stream()
                .map(DomainEvidencePlan.Query::literal).toList();
        assertEquals(List.of("Finish"), sourceBoundLiterals);
    }

    @Test
    void excludesGeneratedGraphArtifactsFromSourceEvidence() {
        assertFalse(DomainEvidencePlan.isAnalyzablePath("graphify-out/graph.json"));
        assertFalse(DomainEvidencePlan.isAnalyzablePath("apps/chat/graphify-out/graph.html"));
        assertTrue(DomainEvidencePlan.isAnalyzablePath("apps/chat/routine/creator.go"));
    }

    @Test
    void followsOnlyAnUnresolvedQuestionsNewSourceBoundFrontier() {
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        String report = """
                {"unknowns":[{"question":"运行事件最终写到哪里？","kind":"event","flow_id":"chat-flow",
                  "symbols":["EmitRuntimeEvent"],"why_material":"无法确认持久化边界"}]}
                """;
        String sourceEvidence = """
                {"control_flow_excerpts":[{"path":"src/chat.go","excerpt":"func EmitRuntimeEvent(){ TableStoreWrite() }"}],
                 "query_results":[],"candidate_excerpts":[]}
                """;
        String resolution = """
                {"next_frontier_queries":[
                  {"question":"运行事件最终写到哪里？","literal":"TableStoreWrite","reason":"继续查看事件落库"},
                  {"question":"别的问题","literal":"UnrelatedCall","reason":"不属于当前问题"}]}
                """;

        DomainEvidencePlan frontier = DomainEvidencePlan.frontierFromResolution(
                resolution, report, sourceEvidence, evidence
        );

        assertEquals(List.of("TableStoreWrite"), frontier.queries().stream()
                .map(DomainEvidencePlan.Query::literal).toList());
        assertEquals(List.of("src/chat.go"), frontier.candidatePaths());
    }
}
