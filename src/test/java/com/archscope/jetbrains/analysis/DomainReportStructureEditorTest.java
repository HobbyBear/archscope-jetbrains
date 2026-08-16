package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DomainReportStructureEditorTest {
    @TempDir
    Path repository;

    @Test
    void recognizesAndGuaranteesTheRequestedSingleFlowDiagram() throws Exception {
        Path source = repository.resolve("src/ReviewService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class ReviewService { void submit() {} void decide() {} }\n");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析审核流程");
        EvidencePack evidence = new EvidencePack(
                repository, "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/ReviewService.java")
        );
        JsonObject report = new DomainReportAssembler().assemble(analysis(), request, evidence);
        ReportValidator validator = new ReportValidator();
        validator.validateRepository(report.toString(), evidence, repository);
        DomainReportStructureEditor editor = new DomainReportStructureEditor();
        DomainReportStructureEditor.Intent intent = editor.intent(
                "现在流程分了太多业务领域，直接合并成一个流程图"
        );

        JsonObject merged = editor.apply(report, intent, AnalysisRequest.OutputLanguage.CHINESE);
        validator.validateRepository(merged.toString(), evidence, repository);

        assertEquals(DomainReportStructureEditor.Intent.MERGE_FLOWS, intent);
        assertEquals(2, merged.getAsJsonArray("business_domains").size());
        assertEquals(1, merged.getAsJsonObject("flow_map").getAsJsonArray("children").size());
        JsonObject flow = merged.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject();
        assertEquals(2, flow.getAsJsonArray("children").size());
        assertEquals("merged-business-flow", merged.getAsJsonArray("nodes").get(0).getAsJsonObject()
                .getAsJsonArray("feature_ids").get(0).getAsString());
    }

    @Test
    void leavesNamedPartialMergesToTheTargetAwareEditPrompt() {
        DomainReportStructureEditor editor = new DomainReportStructureEditor();

        assertEquals(DomainReportStructureEditor.Intent.NONE,
                editor.intent("把支付业务域和结算业务域合并为交易业务域，其他业务域不要动"));
        assertEquals(DomainReportStructureEditor.Intent.NONE,
                editor.intent("把退款流程和撤销流程合并，保留下单流程"));
        assertEquals(DomainReportStructureEditor.Intent.MERGE_DOMAINS,
                editor.intent("把全部业务域合并为一个业务域"));
    }

    private String analysis() {
        return """
                {"schema":"closed-business-domain-analysis/v1","title":"统一审核流程","summary":"提交后完成审核判定",
                 "business_overview":{"purpose":"审核内容","primary_actor":"运营","plain_story":[],"actors":[],
                   "domain_relationships":[],"terms":[],"business_objects":[],"reading_order":["submit-domain","decision-domain"]},
                 "domains":[
                   {"id":"submit-domain","name":"提交","purpose":"接收内容","why_here":"接收内容","actors":[],"owns":["提交"],"receives":[],"produces":[],"not_responsible":[],"depends_on":[]},
                   {"id":"decision-domain","name":"判定","purpose":"给出结果","why_here":"给出结果","actors":[],"owns":["判定"],"receives":[],"produces":[],"not_responsible":[],"depends_on":["submit-domain"]}],
                 "flows":[
                   {"id":"submit-flow","domain_ids":["submit-domain"],"title":"提交内容","summary":"接收待审核内容","flow_type":"request","execution_scope":"single_trigger","actor":"运营","trigger":"提交","routing_condition":"提交请求","preconditions":[],"outcome":"内容已接收","end_title":"接收完成","data_subject":"内容","primary_origin_id":"submit-origin","data_reads":[],"data_writes":[],"failure_paths":[],"business_rules":[],"consumer_targets":[],
                    "entry_source":{"step_id":"submit-step","entry_kind":"public_caller","meaning":"提交入口","file":"src/ReviewService.java","line":1,"symbol":"submit","evidence":"direct_source","confidence":"high"},
                    "data_origins":[{"id":"submit-origin","role":"primary","data":"内容","meaning":"待审核内容","source_kind":"api","source":"运营","entry":"submit","owner":"运营","joins_step_id":"submit-step","upstream_producer_status":"confirmed","file":"src/ReviewService.java","line":1,"symbol":"submit","evidence":"direct_source","confidence":"high"}],
                    "data_flow":[{"id":"submit-hop","lineage_id":"submit-origin","order":1,"step_id":"submit-step","phase":"ingest","timing":"same_execution","plain_action":"接收内容","data":"内容","from":"运营","to":"审核服务","via":"call","transformation":"保持原样","storage":"审核队列","consumer":"decide","file":"src/ReviewService.java","line":1,"symbol":"submit","evidence":"direct_source","confidence":"high"}],
                    "steps":[{"id":"submit-step","title":"提交","summary":"接收待审核内容","kind":"stage","execution":"same_execution","domain_id":"submit-domain","file":"src/ReviewService.java","line":1,"symbol":"submit","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"提交","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]}]},
                   {"id":"decision-flow","domain_ids":["decision-domain"],"title":"审核判定","summary":"给出审核结果","flow_type":"request","execution_scope":"single_trigger","actor":"系统","trigger":"开始判定","routing_condition":"存在待审核内容","preconditions":[],"outcome":"产生审核结果","end_title":"判定完成","data_subject":"审核结果","primary_origin_id":"decision-origin","data_reads":[],"data_writes":[],"failure_paths":[],"business_rules":[],"consumer_targets":[],
                    "entry_source":{"step_id":"decision-step","entry_kind":"public_caller","meaning":"判定入口","file":"src/ReviewService.java","line":1,"symbol":"decide","evidence":"direct_source","confidence":"high"},
                    "data_origins":[{"id":"decision-origin","role":"primary","data":"待审核内容","meaning":"进入判定的内容","source_kind":"storage","source":"审核队列","entry":"decide","owner":"系统","joins_step_id":"decision-step","upstream_producer_status":"confirmed","file":"src/ReviewService.java","line":1,"symbol":"decide","evidence":"direct_source","confidence":"high"}],
                    "data_flow":[{"id":"decision-hop","lineage_id":"decision-origin","order":1,"step_id":"decision-step","phase":"deliver","timing":"same_execution","plain_action":"产生判定","data":"审核结果","from":"审核服务","to":"运营","via":"call","transformation":"形成结果","storage":"审核结果","consumer":"运营","file":"src/ReviewService.java","line":1,"symbol":"decide","evidence":"direct_source","confidence":"high"}],
                    "steps":[{"id":"decision-step","title":"判定","summary":"产生审核结果","kind":"success","execution":"same_execution","domain_id":"decision-domain","file":"src/ReviewService.java","line":1,"symbol":"decide","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"判定","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]}]}
                 ],"unknowns":[],"revision_history":[]}
                """;
    }
}
