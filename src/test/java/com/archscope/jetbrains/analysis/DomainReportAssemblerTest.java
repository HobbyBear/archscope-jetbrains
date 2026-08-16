package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainReportAssemblerTest {
    @Test
    void expandsCompactBusinessSemanticsIntoACompleteReport() throws Exception {
        AnalysisRequest request = AnalysisRequest.businessDomain(Path.of("/repo"), "分析聊天逻辑");
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/chat.go")
        );
        String compact = """
                {"schema":"closed-business-domain-analysis/v1","title":"聊天","summary":"用户得到回复",
                 "business_overview":{"purpose":"完成聊天","primary_actor":"用户",
                   "plain_story":["用户发送问题。","系统校验后生成回答。","回答通过连接返回给用户。"],
                   "actors":[{"name":"用户","goal":"得到回复","enters_via":"WebSocket"}],
                   "domain_relationships":[{"source":"entry","target":"runtime","meaning":"提交请求"},{"source":"common","target":"runtime","meaning":"共享定义"}],
                   "terms":[{"term":"会话","plain_meaning":"连续对话","why_it_matters":"保留上下文"}],
                   "business_objects":[{"id":"message","name":"聊天消息","plain_meaning":"客户端传入的用户问题及系统回答","storage_kind":"unknown","lifecycle":"作为请求载荷从连接进入，生成回答后返回","field_groups":[{"name":"内容","role":"content","fields":["question -> answer"],"meaning":"一次问答的正文"}],"file":"src/chat.go","line":1,"symbol":"accept","evidence":"direct_source","confidence":"high"}],
                   "reading_order":["entry","common","runtime"]},
                 "domains":[
                   {"id":"entry","name":"接入","purpose":"接收请求","why_here":"拥有连接边界","actors":["用户"],"owns":["入口"],"receives":["用户问题"],"produces":["已校验问题"],"not_responsible":["回答生成"],"depends_on":["runtime"],"source_step_ids":["s1","s2"]},
                   {"id":"runtime","name":"生成","purpose":"生成回复","why_here":"拥有生成边界","actors":["用户"],"owns":["模型调用"],"receives":["已校验问题"],"produces":["回答"],"not_responsible":["连接管理"],"depends_on":[],"source_step_ids":["s3"]},
                   {"id":"common","name":"共享定义","purpose":"提供公共类型","why_here":"模型候选域","actors":["系统"],"owns":["类型"],"receives":["定义"],"produces":["定义"],"not_responsible":[],"depends_on":[],"source_step_ids":[]}],
                 "flows":[{"id":"chat-flow","domain_ids":["entry","runtime"],"title":"发送并回复","summary":"完整聊天",
                   "flow_type":"request","execution_scope":"single_trigger",
                   "actor":"用户","trigger":"发送消息","routing_condition":"WebSocket 收到聊天消息","preconditions":["连接可用"],"outcome":"看到回复","end_title":"回复完成",
                   "entry_source":{"step_id":"s1","entry_kind":"external_boundary","meaning":"连接回调接收消息","file":"src/chat.go","line":1,"symbol":"accept","evidence":"direct_source","confidence":"high"},
                   "data_subject":"一次聊天消息","primary_origin_id":"message-origin",
                   "data_reads":["历史"],"data_writes":["回答"],"failure_paths":["模型失败"],"business_rules":[],
                   "data_origins":[{"id":"message-origin","role":"primary","data":"用户问题","meaning":"本次回答要处理的正文","source_kind":"api","source":"WebSocket客户端","entry":"accept","owner":"用户","joins_step_id":"missing-entry-step","upstream_producer_status":"confirmed","file":"src/chat.go","line":1,"symbol":"accept","evidence":"direct_source","confidence":"high"}],
                   "data_flow":[
                     {"id":"message-in","lineage_id":"message-origin","order":1,"step_id":"s1","phase":"ingest","timing":"same_execution","plain_action":"用户问题进入聊天接入层","data":"用户问题","from":"客户端","to":"接入","via":"websocket","transformation":"解析","storage":"会话内存","consumer":"validate","file":"src/chat.go","line":1,"symbol":"accept","evidence":"direct_source","confidence":"high"},
                     {"id":"message-out","lineage_id":"message-origin","order":2,"step_id":"missing-return-step","phase":"deliver","timing":"same_execution","plain_action":"生成的回答返回用户","data":"回答","from":"生成","to":"客户端","via":"websocket","transformation":"流式分片","storage":"无","consumer":"用户","file":"src/chat.go","line":1,"symbol":"reply","evidence":"direct_source","confidence":"high"}],
                   "consumer_targets":[{"name":"历史查询","meaning":"稍后的独立请求读取已保存回答","after_step_id":"missing-consumer-step","file":"src/chat.go","line":1,"symbol":"reply","evidence":"direct_source","confidence":"high"}],
                   "steps":[
                     {"id":"s1","title":"接收","summary":"接收消息","kind":"stage","execution":"same_execution","domain_id":"entry","file":"src/chat.go","line":1,"symbol":"accept","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"接收","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]},
                     {"id":"s2","title":"校验","summary":"校验消息","kind":"decision","execution":"same_execution","domain_id":"entry","file":"src/chat.go","line":1,"symbol":"validate","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"校验","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[],"state_effects":[{"state":"inproc","effect":"decremented","when":"拒绝","meaning":"释放处理中名额"}]},
                     {"id":"s3","title":"生成","summary":"生成回答","kind":"stage","execution":"same_execution","domain_id":"runtime","file":"src/chat.go","line":1,"symbol":"generate","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"调用模型","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[],"supporting_sources":[{"meaning":"回答传输边界","file":"src/chat.go","line":1,"symbol":"reply","evidence":"direct_source","confidence":"high"}]},
                     {"id":"s4","title":"返回","summary":"返回回答","kind":"success","execution":"same_execution","domain_id":"entry","file":"src/chat.go","line":1,"symbol":"reply","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"data","relation_label":"交付","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]}]}],
                 "unknowns":[{"question":"source_evidence 未展示上游生产者","kind":"origin","flow_id":"chat-flow","symbols":["accept"],"why_material":"无法确认问题进入客户端前的生产者"}],"revision_history":[]}
                """;

        JsonObject compactJson = com.google.gson.JsonParser.parseString(compact).getAsJsonObject();
        JsonObject duplicatePrimary = compactJson.getAsJsonArray("flows").get(0).getAsJsonObject()
                .getAsJsonArray("data_origins").get(0).getAsJsonObject().deepCopy();
        duplicatePrimary.addProperty("id", "history-origin");
        duplicatePrimary.addProperty("role", "primary");
        compactJson.getAsJsonArray("flows").get(0).getAsJsonObject()
                .getAsJsonArray("data_origins").add(duplicatePrimary);

        JsonObject report = new DomainReportAssembler().assemble(compactJson.toString(), request, evidence);

        assertEquals("business-domain-walkthrough/v1", report.get("source_format").getAsString());
        assertEquals(2, report.getAsJsonArray("business_domains").size());
        assertEquals(2, report.getAsJsonObject("business_overview").getAsJsonArray("reading_order").size());
        assertEquals(1, report.getAsJsonObject("business_overview").getAsJsonArray("domain_relationships").size());
        assertEquals(5, report.getAsJsonArray("nodes").size());
        assertEquals(3, report.getAsJsonArray("edges").size());
        assertTrue(report.getAsJsonObject("architecture_design").getAsJsonArray("contracts").size() >= 1);
        assertEquals(4, report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children").size());
        assertEquals("decremented", report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children").get(1).getAsJsonObject()
                .getAsJsonArray("state_effects").get(0).getAsJsonObject().get("effect").getAsString());
        assertEquals("WebSocket客户端", report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("data_origins").get(0).getAsJsonObject()
                .get("source").getAsString());
        assertEquals("lookup", report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("data_origins").get(1).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("domain-step-1-1", report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("data_origins").get(0).getAsJsonObject()
                .get("joins_step_id").getAsString());
        assertEquals(1, report.getAsJsonArray("data_structures").size());
        assertEquals("payload", report.getAsJsonObject("business_overview").getAsJsonArray("business_objects")
                .get(0).getAsJsonObject().get("storage_kind").getAsString());
        assertEquals("domain-step-1-4", report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("consumer_targets").get(0).getAsJsonObject()
                .get("after_step_id").getAsString());
        assertEquals("用户问题", report.getAsJsonArray("business_domains").get(0).getAsJsonObject()
                .getAsJsonArray("receives").get(0).getAsString());
        assertEquals("当前仓库源码 未展示上游生产者",
                report.getAsJsonArray("unknowns").get(0).getAsJsonObject().get("question").getAsString());
        assertEquals(2, report.getAsJsonObject("flow_map").getAsJsonArray("children").get(0).getAsJsonObject()
                .getAsJsonArray("children").get(2).getAsJsonObject().getAsJsonArray("source_node_ids").size());
    }
}
