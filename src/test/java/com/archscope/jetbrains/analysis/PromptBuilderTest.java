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
    void omitsLocalRepositoryPathAndFullManifestFromModelPayload() {
        CommitInfo commit = new CommitInfo("abc", List.of(), "A", "2026-08-13T10:00:00+08:00", "Subject");
        EvidencePack evidence = new EvidencePack(
                Path.of("/absolute/private-root"), "head", "abc", "tree", "fingerprint",
                List.of(new EvidencePack.CommitEvidence(commit, "base", "M\tsrc/App.go", List.of("src/App.go"))),
                List.of("src/App.go", "secret/local-only.txt")
        );
        AnalysisRequest request = new AnalysisRequest(Path.of("/absolute/private-root"), List.of(commit), "abc", "focus");

        JsonObject prompt = JsonParser.parseString(new PromptBuilder().userPrompt(request, evidence)).getAsJsonObject();
        JsonObject modelEvidence = prompt.getAsJsonObject("evidence_pack");
        assertFalse(modelEvidence.has("repositoryRoot"));
        assertFalse(modelEvidence.has("targetManifest"));
        assertTrue(modelEvidence.get("target_manifest_file_count").getAsInt() == 2);
        assertFalse(modelEvidence.has("target_snapshots"));
        assertFalse(prompt.toString().contains("/absolute/private-root"));
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
        assertTrue(builder.businessDomainSystemPrompt().contains("later independent reader"));
        assertTrue(builder.businessDomainSystemPrompt().contains("primary_origin_id"));
        assertTrue(builder.businessDomainSystemPrompt().contains("field_groups"));
        assertTrue(builder.businessDomainSystemPrompt().contains("original analysis focus remains the report scope"));
        assertTrue(builder.businessDomainResolutionSystemPrompt().contains("business-domain-evidence-resolution/v1"));
        assertTrue(builder.businessDomainPatchSystemPrompt().contains("business-domain-refinement-patch/v1"));
        assertTrue(builder.businessDomainPatchSystemPrompt().contains("requires_structural_rebuild"));
        assertTrue(builder.refinementSystemPrompt().contains("follow_up_instruction"));
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
    }
}
