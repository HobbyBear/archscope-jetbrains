package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainTextReportAssemblerTest {
    @TempDir
    Path repository;

    @Test
    void buildsAndValidatesTheCompleteSchemaFromTextSlotsAndSourceAnchors() throws Exception {
        Path source = repository.resolve("src/CreatorFlow.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class CreatorFlow {\n  void createCreator() {}\n}\n");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析创作者流程");
        EvidencePack evidence = evidence(source);
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1",
                 "likely_domains":[{"id":"creator","name":"创作者管理","purpose":"管理创作者生命周期"}],
                 "candidate_paths":["src/CreatorFlow.java"],
                 "queries":[{"literal":"createCreator","role":"entry","reason":"入口"}]}
                """, evidence);
        String sourceEvidence = """
                {"schema":"business-domain-source-evidence/v1","query_results":[
                  {"literal":"createCreator","matches":[{"path":"src/CreatorFlow.java","matched_line":2,
                    "snippet":"2: void createCreator() {}"}]}],"candidate_excerpts":[],"control_flow_excerpts":[]}
                """;
        String prose = """
                REPORT_TITLE\t创作者处理流程
                REPORT_SUMMARY\t系统接收创作者请求并执行源码中可见的处理。
                FLOW_1_TITLE\t处理创作者请求
                STEP_1_TITLE\t创建创作者
                STEP_1_SUMMARY\t创建入口接收并处理创作者请求。
                STEP_1_DOMAIN_ID\tcreator
                STEP_1_FLOW_KEY\tcreator_request
                """;

        JsonObject report = new DomainTextReportAssembler().assemble(prose, request, evidence, plan, sourceEvidence);
        new ReportValidator().validateRepository(report.toString(), evidence, repository);

        assertEquals("code-architecture-report/v1", report.get("schema").getAsString());
        assertEquals("创作者处理流程", report.get("title").getAsString());
        assertEquals("src/CreatorFlow.java", report.getAsJsonArray("nodes").get(0).getAsJsonObject()
                .get("file").getAsString());
        assertEquals(2, report.getAsJsonArray("nodes").get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals("creator", report.getAsJsonArray("business_domains").get(0).getAsJsonObject()
                .get("id").getAsString());
    }

    @Test
    void arbitraryModelTextFallsBackLocallyWithoutAJsonRepairRound() throws Exception {
        Path source = repository.resolve("src/CreatorFlow.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class CreatorFlow {}\n");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析创作者流程");
        EvidencePack evidence = evidence(source);
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/CreatorFlow.java"],
                 "queries":[]}
                """, evidence);
        String sourceEvidence = """
                {"schema":"business-domain-source-evidence/v1","query_results":[],
                 "candidate_excerpts":[{"path":"src/CreatorFlow.java","excerpt":"1: class CreatorFlow {}"}],
                 "control_flow_excerpts":[]}
                """;

        JsonObject report = new DomainTextReportAssembler().assemble(
                "这是模型直接返回的一段说明，不包含任何结构化格式。", request, evidence, plan, sourceEvidence
        );
        new ReportValidator().validateRepository(report.toString(), evidence, repository);

        assertTrue(report.get("summary").getAsString().contains("模型直接返回"));
        assertEquals(1, report.getAsJsonObject("flow_map").getAsJsonArray("children").size());
    }

    @Test
    void englishFallbackKeepsChineseSourceNamesOutOfNarrativeFields() throws Exception {
        Path source = repository.resolve("src/创建流程.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class CreatorFlow {}\n");
        AnalysisRequest request = AnalysisRequest.businessDomain(
                repository, "分析创作者流程", com.archscope.jetbrains.model.AnalysisGuidance.EMPTY,
                AnalysisRequest.OutputLanguage.ENGLISH
        );
        EvidencePack evidence = evidence(source);
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/创建流程.java"],
                 "queries":[]}
                """, evidence);
        String sourceEvidence = """
                {"schema":"business-domain-source-evidence/v1","query_results":[],
                 "candidate_excerpts":[{"path":"src/创建流程.java","excerpt":"1: class CreatorFlow {}"}],
                 "control_flow_excerpts":[]}
                """;

        JsonObject report = new DomainTextReportAssembler().assemble(
                "REPORT_TITLE\t错误的中文标题", request, evidence, plan, sourceEvidence
        );

        ReportLanguageValidator.validate(report, AnalysisRequest.OutputLanguage.ENGLISH);
        assertEquals("Business domain analysis", report.get("title").getAsString());
        assertEquals("创建流程", report.getAsJsonArray("nodes").get(0).getAsJsonObject()
                .get("label").getAsString());
    }

    @Test
    void bindsBusinessDescriptionToTheExactInternalAnchorAndItsEnclosingSymbol() throws Exception {
        Path source = repository.resolve("src/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class OrderService {
                  void submitOrder() {
                    validate();
                    publishOrderCreated();
                  }
                }
                """);
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析订单提交");
        EvidencePack evidence = evidence(source);
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/OrderService.java"],
                 "queries":[{"literal":"publishOrderCreated","role":"event","reason":"结果事件"}]}
                """, evidence);
        String sourceEvidence = """
                {"schema":"business-domain-source-evidence/v1",
                 "query_results":[{"literal":"publishOrderCreated","matches":[
                   {"path":"src/OrderService.java","matched_line":4,"snippet":"4: publishOrderCreated();"}]}],
                 "candidate_excerpts":[],"control_flow_excerpts":[{
                   "path":"src/OrderService.java","matched_lines":[4],
                   "excerpt":"complete_function_scope:2-5\n2:  void submitOrder() {\n3:    validate();\n4:    publishOrderCreated();\n5:  }\n"}]}
                """;

        JsonObject report = new DomainTextReportAssembler().assemble(
                "STEP_1_TITLE\t提交订单\nSTEP_1_SUMMARY\t校验订单后发布订单已创建事件。",
                request, evidence, plan, sourceEvidence
        );
        JsonObject node = report.getAsJsonArray("nodes").get(0).getAsJsonObject();

        assertEquals("src/OrderService.java", node.get("file").getAsString());
        assertEquals(4, node.get("line").getAsInt());
        assertEquals("submitOrder", node.get("label").getAsString());
        assertEquals("submitOrder", node.get("symbol").getAsString());
        assertEquals("校验订单后发布订单已创建事件。", node.get("responsibility").getAsString());
    }

    @Test
    void keepsNumberedProseAttachedToItsExplicitSourceBindingAcrossFiles() throws Exception {
        Path payment = repository.resolve("src/PaymentService.java");
        Path inventory = repository.resolve("src/InventoryService.java");
        Files.createDirectories(payment.getParent());
        Files.writeString(payment, "class PaymentService {\n  void charge() {}\n}\n");
        Files.writeString(inventory, "class InventoryService {\n  void reserve() {}\n}\n");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析下单流程");
        EvidencePack evidence = evidence(payment, inventory);
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1",
                 "candidate_paths":["src/PaymentService.java","src/InventoryService.java"],"queries":[]}
                """, evidence);
        String sourceEvidence = """
                {"schema":"business-domain-source-evidence/v1","query_results":[],"candidate_excerpts":[],
                 "control_flow_excerpts":[
                   {"path":"src/PaymentService.java","excerpt":"complete_function_scope:2-2\n2:  void charge() {}\n"},
                   {"path":"src/InventoryService.java","excerpt":"complete_function_scope:2-2\n2:  void reserve() {}\n"}]}
                """;
        DomainTextReportAssembler assembler = new DomainTextReportAssembler();
        DomainTextReportAssembler.TextContract contract = assembler.textContract(sourceEvidence, plan, evidence);

        assertEquals("complete_flow", contract.bindings().get(0).getAsJsonObject().get("binding_kind").getAsString());
        assertEquals("src/PaymentService.java", contract.bindings().get(1).getAsJsonObject().get("file").getAsString());
        assertEquals("charge", contract.bindings().get(1).getAsJsonObject().get("symbol").getAsString());
        assertEquals("src/InventoryService.java", contract.bindings().get(2).getAsJsonObject().get("file").getAsString());
        assertEquals("reserve", contract.bindings().get(2).getAsJsonObject().get("symbol").getAsString());

        JsonObject report = assembler.assemble("""
                STEP_1_TITLE\t收取订单款项
                STEP_1_SUMMARY\t向支付渠道收取本次订单款项。
                STEP_2_TITLE\t预留订单库存
                STEP_2_SUMMARY\t为本次订单预留可售库存。
                """, request, evidence, plan, sourceEvidence);
        Map<String, String> responsibilityByFile = report.getAsJsonArray("nodes").asList().stream()
                .map(element -> element.getAsJsonObject())
                .collect(Collectors.toMap(
                        node -> node.get("file").getAsString(),
                        node -> node.get("responsibility").getAsString()
                ));

        assertEquals("向支付渠道收取本次订单款项。", responsibilityByFile.get("src/PaymentService.java"));
        assertEquals("为本次订单预留可售库存。", responsibilityByFile.get("src/InventoryService.java"));
        assertEquals(2, report.getAsJsonObject("flow_map").getAsJsonArray("children").size());
        assertEquals(1, report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children").size());
        assertEquals("root", report.getAsJsonObject("flow_map").get("id").getAsString());
    }

    @Test
    void keepsDistinctBusinessFactsInsideOneFunctionAsExactSourceBoundSteps() throws Exception {
        Path source = repository.resolve("src/CheckoutService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class CheckoutService {\n  void checkout() {\n    reserve();\n    charge();\n  }\n}\n");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析结算");
        EvidencePack evidence = evidence(source);
        DomainEvidencePlan plan = DomainEvidencePlan.parse("""
                {"schema":"business-domain-evidence-plan/v1","candidate_paths":["src/CheckoutService.java"],
                 "queries":[{"literal":"reserve","role":"state","reason":"库存"},
                            {"literal":"charge","role":"call","reason":"支付"}]}
                """, evidence);
        String sourceEvidence = """
                {"schema":"business-domain-source-evidence/v1","query_results":[
                  {"literal":"reserve","matches":[{"path":"src/CheckoutService.java","matched_line":3}]},
                  {"literal":"charge","matches":[{"path":"src/CheckoutService.java","matched_line":4}]}],
                 "candidate_excerpts":[],"control_flow_excerpts":[{
                   "path":"src/CheckoutService.java","excerpt":"complete_function_scope:2-5\n2:  void checkout() {\n3:    reserve();\n4:    charge();\n5:  }\n"}]}
                """;
        DomainTextReportAssembler assembler = new DomainTextReportAssembler();

        assertEquals(18, assembler.textContract(sourceEvidence, plan, evidence).slots().size());
        JsonObject report = assembler.assemble(
                "STEP_1_SUMMARY\t结算时依次预留库存并收取款项。", request, evidence, plan, sourceEvidence
        );
        assertEquals(2, report.getAsJsonArray("nodes").size());
        assertEquals(3, report.getAsJsonArray("nodes").get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(4, report.getAsJsonArray("nodes").get(1).getAsJsonObject().get("line").getAsInt());
    }

    private EvidencePack evidence(Path source) {
        return evidence(new Path[]{source});
    }

    private EvidencePack evidence(Path... sources) {
        return new EvidencePack(
                repository, "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), java.util.Arrays.stream(sources)
                        .map(source -> repository.relativize(source).toString().replace('\\', '/')).toList()
        );
    }
}
