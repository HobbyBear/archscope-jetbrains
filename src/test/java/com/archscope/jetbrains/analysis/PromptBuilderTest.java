package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisGuidance;
import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PromptBuilderTest {
    @Test
    void businessDomainSynthesisRequestsTextSlotsInsteadOfReportJson() throws Exception {
        AnalysisRequest request = AnalysisRequest.businessDomain(Path.of("/repo"), "分析聊天逻辑");
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/chat.go"],"queries":[]}
                """, evidence);
        String sourceEvidence = """
                {"schema":"business-domain-source-evidence/v1","query_results":[],
                 "candidate_excerpts":[{"path":"src/chat.go","excerpt":"1: package chat"}],
                 "control_flow_excerpts":[]}
                """;

        PromptBuilder builder = new PromptBuilder();
        String system = builder.businessDomainTextSystemPrompt(request);
        String prompt = builder.businessDomainTextPrompt(
                request, evidence, plan, sourceEvidence, List.of("REPORT_TITLE", "STEP_1_SUMMARY"),
                new DomainTextReportAssembler().textContract(sourceEvidence, plan, evidence).bindings()
        );

        assertTrue(system.contains("not JSON"));
        assertTrue(system.contains("SLOT_NAME<TAB>text"));
        assertTrue(prompt.contains("REPORT_TITLE"));
        assertTrue(prompt.contains("No JSON"));
        assertTrue(system.contains("STEP_n_DOMAIN_ID"));
        assertTrue(system.contains("STEP_n_FLOW_KEY"));
        assertTrue(prompt.contains("text_slot_source_bindings"));
        assertTrue(prompt.contains("src/chat.go"));
    }
    @Test
    void omitsLocalRepositoryPathAndFullManifestFromModelPayload() {
        CommitInfo commit = new CommitInfo("abc", List.of(), "A", "2026-08-13T10:00:00+08:00", "Subject");
        EvidencePack evidence = new EvidencePack(
                Path.of("/absolute/private-root"), "head", "abc", "tree", "fingerprint",
                List.of(new EvidencePack.CommitEvidence(
                        commit,
                        "base",
                        "M\tsrc/App.go\nA\tdocs/design.md\nA\topenspec/change/walkthrough.html\nM\tgo.sum",
                        List.of("src/App.go", "docs/design.md", "openspec/change/walkthrough.html", "go.sum")
                )),
                List.of("src/App.go", "secret/local-only.txt")
        );
        AnalysisRequest request = new AnalysisRequest(Path.of("/absolute/private-root"), List.of(commit), "abc", "focus");

        JsonObject prompt = JsonParser.parseString(new PromptBuilder().userPrompt(request, evidence)).getAsJsonObject();
        JsonObject modelEvidence = prompt.getAsJsonObject("evidence_pack");
        assertFalse(modelEvidence.has("repositoryRoot"));
        assertFalse(modelEvidence.has("targetManifest"));
        assertTrue(modelEvidence.get("target_manifest_file_count").getAsInt() == 2);
        assertFalse(modelEvidence.has("target_snapshots"));
        assertTrue(modelEvidence.get("aggregate_name_status").getAsString().equals("M\tsrc/App.go"));
        assertTrue(modelEvidence.getAsJsonArray("commits").get(0).getAsJsonObject()
                .getAsJsonArray("changed_paths").size() == 1);
        assertFalse(prompt.toString().contains("docs/design.md"));
        assertFalse(prompt.toString().contains("walkthrough.html"));
        assertFalse(prompt.toString().contains("go.sum"));
        assertFalse(prompt.toString().contains("/absolute/private-root"));
        assertTrue(prompt.get("evidence_scope").getAsString().contains("only executable source"));
        assertTrue(prompt.get("evidence_scope").getAsString().contains("excluded locally"));
        assertTrue(prompt.get("workspace_instruction").getAsString().contains("closed evidence bundle"));
        assertTrue(prompt.get("workspace_instruction").getAsString().contains("does not permit autonomous repository exploration"));
        assertTrue(prompt.get("workspace_instruction").getAsString().contains("Stop once every changed behavior"));
        assertTrue(prompt.get("final_instruction").getAsString().contains("JSON"));
        assertTrue(prompt.get("final_instruction").getAsString().contains("changed, affected, and context"));
    }

    @Test
    void buildsProviderNeutralBusinessDomainAndRefinementPrompts() throws Exception {
        CommitInfo commit = new CommitInfo("abc", List.of(), "A", "2026-08-13T10:00:00+08:00", "Subject");
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "abc", "abc", "abc", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        AnalysisRequest request = AnalysisRequest.businessDomain(Path.of("/repo"), "分析聊天逻辑");
        PromptBuilder builder = new PromptBuilder();

        JsonObject prompt = JsonParser.parseString(builder.businessDomainPrompt(request, evidence)).getAsJsonObject();
        assertTrue(prompt.get("analysis_focus").getAsString().contains("聊天"));
        assertTrue(prompt.getAsJsonObject("required_comparison").get("mode").getAsString().equals("current_snapshot"));
        assertFalse(prompt.toString().contains("Codex"));
        assertTrue(builder.businessDomainSystemPrompt().contains("closed-business-domain-analysis/v1"));
        assertTrue(builder.businessDomainPlanningSystemPrompt().contains("complete enclosing function bodies"));
        assertTrue(builder.businessDomainPlanningSystemPrompt().contains("producer-to-"));
        assertTrue(builder.businessDomainPlanningSystemPrompt().contains("actual route/job registration"));
        assertTrue(builder.businessDomainPlanningSystemPrompt().contains("OPERATIONS<TAB>"));
        assertTrue(builder.businessDomainPlanningSystemPrompt().contains("not JSON"));
        assertTrue(builder.businessDomainPlanningSystemPrompt().contains("supplement_domain"));
        assertTrue(builder.businessDomainPlanningSystemPrompt().contains("add_nodes"));
        assertTrue(builder.businessDomainSystemPrompt().contains("later independent reader"));
        assertTrue(builder.businessDomainSystemPrompt().contains("graph-edit command"));
        assertTrue(builder.businessDomainSystemPrompt().contains("add, remove, move, or reorder nodes"));
        assertTrue(builder.businessDomainSystemPrompt().contains("primary_origin_id"));
        assertTrue(builder.businessDomainSystemPrompt().contains("field_groups"));
        assertTrue(builder.businessDomainSystemPrompt().contains("original analysis focus remains the report scope"));
        assertTrue(builder.businessDomainSystemPrompt().contains("Never promote a getter, finder, loader, router lookup"));
        assertTrue(builder.businessDomainSystemPrompt().contains("legitimate atomic actor goal may have one"));
        assertTrue(builder.businessDomainSystemPrompt().contains("Never invent, duplicate, or mechanically"));
        assertTrue(builder.businessDomainSystemPrompt().contains("return [] when the bounded evidence proves no specific exclusion"));
        assertTrue(builder.businessDomainResolutionSystemPrompt().contains("business-domain-evidence-resolution/v1"));
        assertTrue(builder.businessDomainPatchSystemPrompt().contains("business-domain-refinement-patch/v1"));
        assertTrue(builder.businessDomainPatchSystemPrompt().contains("requires_structural_rebuild"));
        assertTrue(builder.refinementSystemPrompt().contains("follow_up_instruction"));

        DomainEvidencePlan emptyPlan = DomainEvidencePlan.parse(
                "{\"schema\":\"business-domain-evidence-plan/v1\",\"candidate_paths\":[],\"queries\":[]}", evidence);
        String refinement = builder.businessDomainRefinementPrompt(
                request, evidence, "{}", "新增一个通知节点", emptyPlan,
                "{\"candidate_excerpts\":[],\"query_results\":[]}");
        assertTrue(refinement.contains("acceptance_rule"));
        assertTrue(refinement.contains("explicit graph edit"));
    }

    @Test
    void compactsAnExistingBusinessReportBeforePlanningOrPatching() {
        String report = """
                {"title":"聊天","summary":"完整聊天流程","analysis_focus":{"request":"分析聊天"},
                 "business_overview":{"purpose":"解释聊天","primary_actor":"用户","plain_story":["进入","返回"],
                   "business_objects":[{"id":"ctx","name":"连接状态","lifecycle":"创建后复用"}]},
                 "business_domains":[{"id":"chat","name":"聊天"}],
                 "flow_map":{"id":"root","title":"聊天","children":[{"id":"flow-1","title":"角色流",
                   "summary":"生成并返回","routing_condition":"scene=character","source_node_ids":["node-1"],
                   "children":[{"id":"step-1","title":"进入","summary":"处理请求","source_node_ids":["node-1"]}]}]},
                 "nodes":[{"id":"node-1","label":"Chat","file":"src/chat.go","line":12,
                   "responsibility":"处理聊天","evidence":"direct_source","confidence":"high","inputs":["large duplicate"]}],
                 "edges":[{"id":"derived-edge"}],"architecture_design":{"overview":"derived duplicate"},
                 "unknowns":[],"revision_history":[]}
                """;

        JsonObject compact = PromptBuilder.compactBusinessDomainContext(report, false);

        assertTrue(compact.has("flow_map"));
        assertTrue(compact.has("source_index"));
        assertTrue(compact.getAsJsonArray("source_index").get(0).getAsJsonObject().has("file"));
        assertFalse(compact.getAsJsonArray("source_index").get(0).getAsJsonObject().has("inputs"));
        assertFalse(compact.has("nodes"));
        assertFalse(compact.has("edges"));
        assertFalse(compact.has("architecture_design"));

        JsonObject planning = PromptBuilder.compactBusinessDomainContext(report, true);
        JsonObject indexedFlow = planning.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject();
        assertTrue(indexedFlow.has("routing_condition"));
        assertFalse(indexedFlow.has("data_origins"));
    }

    @Test
    void includesCustomGuidanceAndProtectsTheCoreSystemContract() throws Exception {
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "abc", "abc", "abc", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/creator.go")
        );
        AnalysisGuidance guidance = new AnalysisGuidance(
                "创作者来自运营导入。必须追到首次落库和最终消费者。", "先展示数据流"
        );
        AnalysisRequest request = AnalysisRequest.businessDomain(Path.of("/repo"), "分析创作者", guidance);
        PromptBuilder builder = new PromptBuilder();

        JsonObject prompt = JsonParser.parseString(builder.businessDomainPrompt(request, evidence)).getAsJsonObject();
        assertTrue(prompt.getAsJsonObject("project_guidance").get("custom_instructions").getAsString()
                .contains("运营导入"));
        assertTrue(prompt.getAsJsonObject("project_guidance").get("custom_instructions").getAsString()
                .contains("最终消费者"));
        String system = builder.businessDomainSystemPrompt(request);
        assertTrue(system.contains("先展示数据流"));
        assertTrue(system.contains("override the required JSON schema"));

        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/creator.go"],
                 "queries":[{"literal":"CreatorLevel","role":"state","reason":"定位等级"}]}
                """, evidence);
        String sourceEvidence = "{\"candidate_excerpts\":[],\"query_results\":[]}";
        assertTrue(builder.businessDomainFinalPrompt(request, evidence, plan, sourceEvidence)
                .contains("必须追到首次落库和最终消费者"));
        assertTrue(builder.businessDomainRepairPrompt(
                request, evidence, plan, sourceEvidence, "broken", "invalid")
                .contains("必须追到首次落库和最终消费者"));
        assertTrue(builder.businessDomainRefinementPrompt(
                request, evidence, "{}", "补充等级变更", plan, sourceEvidence)
                .contains("必须追到首次落库和最终消费者"));
    }

    @Test
    void appliesEnglishOutputToEveryPromptLayer() throws Exception {
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "abc", "abc", "abc", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        AnalysisRequest request = AnalysisRequest.businessDomain(
                Path.of("/repo"), "Analyze chat logic", AnalysisGuidance.EMPTY,
                AnalysisRequest.OutputLanguage.ENGLISH
        );
        PromptBuilder builder = new PromptBuilder();

        JsonObject payload = JsonParser.parseString(builder.businessDomainPrompt(request, evidence)).getAsJsonObject();
        assertTrue(payload.get("output_language").getAsString().equals("en"));
        assertTrue(payload.get("report_language").getAsString().contains("English only"));
        String system = builder.businessDomainSystemPrompt(request);
        assertTrue(system.contains("Write every human-readable JSON value in English"));
        assertTrue(system.contains("Do not output Chinese characters"));
        assertFalse(system.contains("Use concise Simplified Chinese"));
    }
}
