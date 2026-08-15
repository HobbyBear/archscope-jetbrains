package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainReportPatchAssemblerTest {
    @Test
    void appliesOnlySemanticUpdatesAndAppendsTrackedStepSource() throws Exception {
        JsonObject original = report();
        JsonArray originalUnknowns = original.getAsJsonArray("unknowns").deepCopy();
        JsonObject originalNode = original.getAsJsonArray("nodes").get(0).getAsJsonObject().deepCopy();
        JsonObject originalEdge = original.getAsJsonArray("edges").get(0).getAsJsonObject().deepCopy();
        String patch = """
                {"schema":"business-domain-refinement-patch/v1","requires_structural_rebuild":false,
                 "report_summary":"聊天请求完成校验、保存与推送",
                 "revision_summary":"补齐聊天数据流",
                 "overview_update":{"purpose":"让用户安全完成聊天","primary_actor":"聊天用户",
                   "plain_story":["接收消息","检查权限","保存并推送"],
                   "actors":[{"name":"用户","goal":"聊天"}],
                   "domain_relationships":[{"source":"chat","target":"storage","meaning":"持久化"}],
                   "terms":[{"term":"会话","plain_meaning":"一组消息"}],"reading_order":["chat"],
                   "business_object_updates":[{"id":"message","plain_meaning":"一条待发送的消息",
                     "lifecycle":"接收、校验、保存、推送","supporting_sources":[
                       {"meaning":"保存消息","file":"src\\\\store.go","line":18,"symbol":"SaveMessage",
                        "evidence":"direct_source","confidence":"high"}]}]},
                 "domain_updates":[{"id":"chat","name":"聊天编排","purpose":"校验并投递消息",
                   "why_here":"负责单次聊天请求","actors":["用户"],"owns":["聊天流程"],
                   "receives":["消息"],"produces":["投递结果"],"not_responsible":["账号注册"],"depends_on":[],
                   "source_node_ids":["node-receive","node-save"]}],
                 "flow_updates":[{"flow_id":"flow-chat","title":"发送一条消息","summary":"完成校验、保存与推送",
                   "flow_type":"request","execution_scope":"single_trigger","actor":"用户","trigger":"发送消息",
                   "routing_condition":"普通聊天请求","preconditions":["用户已登录"],"outcome":"消息已投递",
                   "end_title":"聊天完成","data_subject":"消息","primary_origin_id":"origin-message",
                   "entry_source":{"step_id":"step-receive","entry_kind":"route","meaning":"HTTP 入口",
                     "file":"src/chat.go","line":10,"symbol":"SendMessage","evidence":"direct_source","confidence":"high"},
                   "data_reads":["会话"],"data_writes":["消息"],
                   "data_origins":[{"id":"origin-message","data":"消息","file":"src/chat.go","line":10,
                     "symbol":"SendMessage","evidence":"direct_source","confidence":"high"}],
                   "data_flow":[{"id":"hop-save","order":1,"step_id":"step-save","data":"消息",
                     "file":"src/store.go","line":18,"symbol":"SaveMessage","evidence":"direct_source","confidence":"high"}],
                   "consumer_targets":[{"name":"推送器","after_step_id":"step-save","file":"src/push.go","line":20,
                     "symbol":"PushMessage","evidence":"direct_source","confidence":"high"}],
                   "failure_paths":["权限失败时拒绝"],"business_rules":["只保存合法消息"]}],
                 "step_updates":[{"step_id":"step-save","title":"保存并推送","summary":"先保存，再交给推送器",
                   "kind":"success","execution":"same_execution","business_rules":["保存成功后才能推送"],
                   "branches":[{"label":"保存失败","outcome":"failure","meaning":"停止推送"}],
                   "state_effects":[{"state":"message","effect":"created","when":"保存成功","meaning":"消息可读取"}],
                   "additional_sources":[{"file":"src/helper.go","line":30,"end_line":36,
                     "symbol":"PrepareMessage","node_kind":"function","meaning":"整理待保存字段",
                     "evidence":"source_backed_walkthrough","confidence":"medium"}]}],
                 "revision_history":[]}
                """;

        DomainReportPatchAssembler.ApplyResult result = new DomainReportPatchAssembler()
                .apply(patch, original, evidence());
        JsonObject updated = result.report();

        assertFalse(result.requiresStructuralRebuild());
        assertEquals("补齐聊天数据流", result.summary());
        assertEquals("聊天请求完成校验、保存与推送", updated.get("summary").getAsString());
        assertEquals("聊天请求完成校验、保存与推送",
                updated.getAsJsonObject("reader_guide").get("subtitle").getAsString());
        assertEquals("让用户安全完成聊天",
                updated.getAsJsonObject("business_overview").get("purpose").getAsString());
        JsonObject businessObject = updated.getAsJsonObject("business_overview")
                .getAsJsonArray("business_objects").get(0).getAsJsonObject();
        assertEquals("src/store.go", businessObject.getAsJsonArray("supporting_sources")
                .get(0).getAsJsonObject().get("file").getAsString());
        assertEquals("校验并投递消息", updated.getAsJsonArray("business_domains")
                .get(0).getAsJsonObject().get("purpose").getAsString());
        assertEquals("校验并投递消息", updated.getAsJsonObject("architecture_design")
                .getAsJsonArray("lanes").get(0).getAsJsonObject().get("represents").getAsString());

        JsonArray flows = updated.getAsJsonObject("flow_map").getAsJsonArray("children");
        assertEquals(List.of("flow-chat", "flow-history"), flows.asList().stream()
                .map(item -> item.getAsJsonObject().get("id").getAsString()).toList());
        JsonObject chatFlow = flows.get(0).getAsJsonObject();
        assertEquals(List.of("step-receive", "step-save"), chatFlow.getAsJsonArray("children").asList().stream()
                .map(item -> item.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals("src/chat.go", chatFlow.getAsJsonObject("entry_source").get("file").getAsString());
        JsonObject saveStep = chatFlow.getAsJsonArray("children").get(1).getAsJsonObject();
        assertEquals("保存并推送", saveStep.get("title").getAsString());
        assertEquals(2, saveStep.getAsJsonArray("source_node_ids").size());

        assertEquals(4, updated.getAsJsonArray("nodes").size());
        JsonObject additional = updated.getAsJsonArray("nodes").get(3).getAsJsonObject();
        assertEquals("PrepareMessage", additional.get("label").getAsString());
        assertEquals("src/helper.go", additional.get("file").getAsString());
        assertEquals(originalUnknowns, updated.getAsJsonArray("unknowns"));
        assertEquals(originalNode, updated.getAsJsonArray("nodes").get(0).getAsJsonObject());
        assertEquals(originalEdge, updated.getAsJsonArray("edges").get(0).getAsJsonObject());
    }

    @Test
    void returnsOriginalReportWhenStructuralRebuildIsRequired() throws Exception {
        JsonObject original = report();
        String patch = """
                {"schema":"business-domain-refinement-patch/v1","requires_structural_rebuild":true,
                 "revision_summary":"需要新增撤回流程",
                 "flow_updates":[{"flow_id":"flow-chat","title":"不应应用"}]}
                """;

        DomainReportPatchAssembler.ApplyResult result = new DomainReportPatchAssembler()
                .apply(patch, original, evidence());

        assertTrue(result.requiresStructuralRebuild());
        assertEquals("需要新增撤回流程", result.summary());
        assertEquals(original, result.report());
    }

    @Test
    void ignoresAttemptedStructuralAndUnknownChanges() throws Exception {
        JsonObject original = report();
        String patch = """
                {"schema":"business-domain-refinement-patch/v1","requires_structural_rebuild":false,
                 "summary":"只改说明","unknowns":[],
                 "flow_updates":[{"flow_id":"flow-chat","summary":"新说明","children":[{"id":"new-step"}]}],
                 "step_updates":[{"step_id":"step-save","title":"保存消息","id":"renamed",
                   "children":[{"id":"nested"}],"source_node_ids":[]}]}
                """;

        JsonObject updated = new DomainReportPatchAssembler().apply(patch, original, evidence()).report();

        assertEquals(original.getAsJsonArray("unknowns"), updated.getAsJsonArray("unknowns"));
        JsonObject flow = updated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject();
        assertEquals(List.of("step-receive", "step-save"), flow.getAsJsonArray("children").asList().stream()
                .map(item -> item.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals("保存消息", flow.getAsJsonArray("children").get(1).getAsJsonObject()
                .get("title").getAsString());
        assertEquals(1, flow.getAsJsonArray("children").get(1).getAsJsonObject()
                .getAsJsonArray("source_node_ids").size());
    }

    @Test
    void rejectsAdditionalSourceOutsideTargetManifestOrWithoutSymbol() {
        String outside = """
                {"schema":"business-domain-refinement-patch/v1","step_updates":[
                  {"step_id":"step-save","additional_sources":[
                    {"file":"src/missing.go","line":1,"symbol":"Missing"}]}]}
                """;
        String noSymbol = """
                {"schema":"business-domain-refinement-patch/v1","step_updates":[
                  {"step_id":"step-save","additional_sources":[
                    {"file":"src/helper.go","line":1,"symbol":""}]}]}
                """;

        assertThrows(ModelClientException.class,
                () -> new DomainReportPatchAssembler().apply(outside, report(), evidence()));
        assertThrows(ModelClientException.class,
                () -> new DomainReportPatchAssembler().apply(noSymbol, report(), evidence()));
    }

    @Test
    void rejectsAReportWhoseStepHasOnlyUntrackedInferredEvidence() {
        JsonObject report = report();
        JsonObject node = report.getAsJsonArray("nodes").get(0).getAsJsonObject();
        node.addProperty("file", "");
        node.addProperty("evidence", "inferred");
        String patch = """
                {"schema":"business-domain-refinement-patch/v1","revision_summary":"no-op"}
                """;

        ModelClientException error = assertThrows(ModelClientException.class,
                () -> new DomainReportPatchAssembler().apply(patch, report, evidence()));

        assertTrue(error.getMessage().contains("step-receive"));
    }

    private EvidencePack evidence() {
        return new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(),
                List.of("src/chat.go", "src/store.go", "src/push.go", "src/history.go", "src/helper.go")
        );
    }

    private JsonObject report() {
        return JsonParser.parseString("""
                {"schema":"code-architecture-report/v1","summary":"旧摘要",
                 "reader_guide":{"subtitle":"旧摘要"},
                 "business_overview":{"purpose":"完成聊天","primary_actor":"用户","plain_story":["发送消息"],
                   "actors":[],"domain_relationships":[],"terms":[],"reading_order":["chat"],
                   "business_objects":[{"id":"message","name":"消息","plain_meaning":"聊天内容",
                     "storage_kind":"table","lifecycle":"接收并保存","field_groups":[],
                     "file":"src/chat.go","line":10,"symbol":"SendMessage","evidence":"direct_source",
                     "confidence":"high","supporting_sources":[]}]},
                 "business_domains":[{"id":"chat","name":"聊天","purpose":"处理消息","why_here":"聊天职责",
                   "actors":["用户"],"owns":["消息"],"receives":["请求"],"produces":["结果"],
                   "not_responsible":["账号"],"depends_on":[],"flow_ids":["flow-chat"],
                   "source_node_ids":["node-receive","node-save"]}],
                 "architecture_design":{"lanes":[{"id":"lane-chat","name":"聊天","code_label":"chat",
                   "represents":"处理消息","why_here":"聊天职责","responsibilities":["消息"],
                   "receives":["请求"],"produces":["结果"],"not_responsible":["账号"],
                   "source_node_ids":["node-receive","node-save"]}]},
                 "unknowns":[{"question":"上游限流阈值是什么","kind":"rule"}],
                 "nodes":[
                   {"id":"node-receive","file":"src/chat.go","line":10,"end_line":10,"label":"SendMessage",
                    "service":"chat","module":"chat","evidence":"direct_source"},
                   {"id":"node-save","file":"src/store.go","line":18,"end_line":18,"label":"SaveMessage",
                    "service":"chat","module":"chat","evidence":"direct_source"},
                   {"id":"node-history","file":"src/history.go","line":5,"end_line":5,"label":"ListHistory",
                    "service":"chat","module":"chat","evidence":"direct_source"}],
                 "edges":[{"id":"edge-save","source":"node-receive","target":"node-save","kind":"call"}],
                 "flow_map":{"id":"root","children":[
                   {"id":"flow-chat","title":"聊天","summary":"处理一条消息","children":[
                     {"id":"step-receive","title":"接收","summary":"接收请求","kind":"stage",
                      "execution":"same_execution","children":[],"source_node_ids":["node-receive"],
                      "business_rules":[],"branches":[],"state_effects":[]},
                     {"id":"step-save","title":"保存","summary":"保存消息","kind":"stage",
                      "execution":"same_execution","children":[],"source_node_ids":["node-save"],
                      "business_rules":[],"branches":[],"state_effects":[]}],
                    "source_node_ids":["node-receive","node-save"]},
                   {"id":"flow-history","title":"历史消息","summary":"读取历史","children":[
                     {"id":"step-history","title":"读取","summary":"读取历史","kind":"stage",
                      "execution":"same_execution","children":[],"source_node_ids":["node-history"],
                      "business_rules":[],"branches":[],"state_effects":[]}],
                    "source_node_ids":["node-history"]}]}}
                """).getAsJsonObject();
    }
}
