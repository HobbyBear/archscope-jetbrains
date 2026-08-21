package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReportValidatorTest {
    private final ReportValidator validator = new ReportValidator();
    @TempDir
    Path workspace;

    @BeforeEach
    void createLockedSource() throws Exception {
        Path source = workspace.resolve("repository/src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n");
    }

    @Test
    void acceptsClosedEvidenceReferences() throws Exception {
        JsonObject report = validator.validate(validReport(), evidence(), workspace);
        assertEquals("code-architecture-report/v1", report.get("schema").getAsString());
        assertEquals("changed", report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().get("change_status").getAsString());
        assertEquals(1, report.getAsJsonObject("change_summary").get("changed_flow_count").getAsInt());
        assertEquals(List.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), strings(report.getAsJsonArray("nodes")
                .get(0).getAsJsonObject().getAsJsonArray("changed_in_commits")));
    }

    @Test
    void fillsMissingPresentationMetadataWithoutDiscardingTheReport() throws Exception {
        JsonObject response = JsonParser.parseString(validReport()).getAsJsonObject();
        response.remove("analysis_focus");
        response.remove("reader_guide");
        response.remove("scope");

        JsonObject report = validator.validate(response.toString(), evidence(), workspace);

        assertTrue(report.get("analysis_focus").isJsonObject());
        assertEquals("Test", report.getAsJsonObject("reader_guide").get("title").getAsString());
        assertTrue(report.get("scope").isJsonObject());
    }

    @Test
    void normalizesRootIdDiffArtifactsAndSourceLineSuffixes() throws Exception {
        JsonObject response = JsonParser.parseString(validReport()).getAsJsonObject();
        response.getAsJsonObject("flow_map").remove("id");
        JsonObject evolution = response.getAsJsonArray("commit_evolution").get(0).getAsJsonObject();
        evolution.add("evidence_paths", JsonParser.parseString("[\"evidence/01-aaaaaaaa.diff\"]"));
        JsonObject finding = JsonParser.parseString("""
                {"id":"rf1","severity":"medium","title":"Finding","meaning":"Evidence-backed",
                 "affected_node_ids":["app.run"],"evidence_paths":["repository/src/App.java:1","src/App.java#L1"],
                 "confidence":"high"}
                """).getAsJsonObject();
        response.getAsJsonArray("review_findings").add(finding);

        JsonObject report = validator.validate(response.toString(), evidence(), workspace);

        assertEquals("root", report.getAsJsonObject("flow_map").get("id").getAsString());
        assertEquals(List.of("src/App.java"), strings(report.getAsJsonArray("commit_evolution")
                .get(0).getAsJsonObject().getAsJsonArray("evidence_paths")));
        assertEquals(List.of("src/App.java"), strings(report.getAsJsonArray("review_findings")
                .get(0).getAsJsonObject().getAsJsonArray("evidence_paths")));
    }

    @Test
    void stillRejectsUnknownEvidencePaths() {
        String invalid = validReport().replace("\"evidence_paths\":[\"src/App.java\"]", "\"evidence_paths\":[\"evidence/99-unknown.diff\"]");
        ReportValidationException exception = assertThrows(
                ReportValidationException.class,
                () -> validator.validate(invalid, evidence(), workspace)
        );
        assertTrue(exception.getMessage().contains("不存在的路径"));
    }

    @Test
    void rejectsInventedSourcePaths() {
        String invalid = validReport().replace("src/App.java", "src/Invented.java");
        ReportValidationException exception = assertThrows(
                ReportValidationException.class,
                () -> validator.validate(invalid, evidence(), workspace)
        );
        assertTrue(exception.getMessage().contains("不存在的文件"));
    }

    @Test
    void rejectsInventedSourceLineOutsideLockedSnapshot() {
        String invalid = validReport().replace("\"line\":1", "\"line\":999");
        ReportValidationException exception = assertThrows(
                ReportValidationException.class,
                () -> validator.validate(invalid, evidence(), workspace)
        );
        assertTrue(exception.getMessage().contains("行号不在目标提交文件范围内"));
    }

    @Test
    void reportsChangedPathsThatAreNotMappedIntoTheBusinessFlow() throws Exception {
        EvidencePack base = evidence();
        EvidencePack evidence = new EvidencePack(
                base.repositoryRoot(), base.headCommit(), base.targetCommit(), base.targetTree(), base.fingerprint(),
                List.of(new EvidencePack.CommitEvidence(
                        base.commits().get(0).commit(), "parent", "M\tsrc/App.java\nM\tdocs/change.md",
                        List.of("src/App.java", "docs/change.md")
                )),
                List.of("src/App.java", "docs/change.md")
        );

        JsonObject report = validator.validate(validReport(), evidence, workspace);

        assertEquals(List.of("docs/change.md"), strings(report.getAsJsonObject("change_summary")
                .getAsJsonArray("unmapped_changed_paths")));
    }

    @Test
    void acceptsOnlyCompleteNewcomerBusinessFlowsForCurrentSnapshot() throws Exception {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );

        JsonObject report = validator.validateRepository(
                businessDomainReport(),
                snapshot,
                workspace.resolve("repository")
        );

        assertEquals("current_snapshot", report.getAsJsonObject("comparison").get("mode").getAsString());
        assertEquals(1, report.getAsJsonArray("business_domains").size());
        assertEquals(4, report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children").size());
    }

    @Test
    void acceptsAnEmptyUnprovenResponsibilityExclusion() throws Exception {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject response = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        response.getAsJsonArray("business_domains").get(0).getAsJsonObject()
                .add("not_responsible", new JsonArray());

        JsonObject report = validator.validateRepository(
                response.toString(), snapshot, workspace.resolve("repository")
        );

        assertTrue(report.getAsJsonArray("business_domains").get(0).getAsJsonObject()
                .getAsJsonArray("not_responsible").isEmpty());
    }

    @Test
    void acceptsInferredDataHopWithoutJumpableSource() throws Exception {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject response = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        JsonObject hop = response.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("data_flow").get(0).getAsJsonObject();
        hop.addProperty("file", "");
        hop.addProperty("evidence", "inferred");

        JsonObject report = validator.validateRepository(
                response.toString(), snapshot, workspace.resolve("repository"));

        assertEquals("inferred", report.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("data_flow").get(0).getAsJsonObject()
                .get("evidence").getAsString());
    }

    @Test
    void normalizesGlobalDataFlowOrdersWithinEachLineage() throws Exception {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject response = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        JsonObject flow = response.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject();
        JsonArray dataFlow = flow.getAsJsonArray("data_flow");
        dataFlow.get(0).getAsJsonObject().addProperty("order", 4);
        dataFlow.get(1).getAsJsonObject().addProperty("order", 9);
        flow.getAsJsonArray("data_origins").add(JsonParser.parseString("""
                {"id":"balance-origin","role":"lookup","data":"Balance","meaning":"Controls admission",
                 "source_kind":"database","source":"Balance store","entry":"load","owner":"Billing",
                 "joins_step_id":"flow.step2","upstream_producer_status":"confirmed","file":"src/App.java",
                 "line":1,"symbol":"run","evidence":"direct_source","confidence":"high"}
                """).getAsJsonObject());
        dataFlow.add(JsonParser.parseString("""
                {"id":"balance-read","lineage_id":"balance-origin","order":3,"step_id":"flow.step2",
                 "phase":"validate","timing":"same_execution","plain_action":"Read the balance",
                 "data":"Balance","from":"Store","to":"Chat","via":"call","transformation":"none",
                 "storage":"memory","consumer":"Admission","file":"src/App.java","line":1,"symbol":"run",
                 "evidence":"direct_source","confidence":"high"}
                """).getAsJsonObject());
        dataFlow.add(JsonParser.parseString("""
                {"id":"balance-use","lineage_id":"balance-origin","order":8,"step_id":"flow.step3",
                 "phase":"transform","timing":"same_execution","plain_action":"Apply the balance decision",
                 "data":"Admission result","from":"Chat","to":"Generator","via":"call","transformation":"gate",
                 "storage":"memory","consumer":"Generator","file":"src/App.java","line":1,"symbol":"run",
                 "evidence":"direct_source","confidence":"high"}
                """).getAsJsonObject());

        JsonObject validated = validator.validateRepository(
                response.toString(), snapshot, workspace.resolve("repository")
        );
        JsonArray normalized = validated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("data_flow");

        assertEquals(1, normalized.get(0).getAsJsonObject().get("order").getAsInt());
        assertEquals(2, normalized.get(1).getAsJsonObject().get("order").getAsInt());
        assertEquals(1, normalized.get(2).getAsJsonObject().get("order").getAsInt());
        assertEquals(2, normalized.get(3).getAsJsonObject().get("order").getAsInt());
    }

    @Test
    void stillRejectsDuplicateOrdersWithinOneLineage() {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject response = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        JsonArray dataFlow = response.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("data_flow");
        dataFlow.get(0).getAsJsonObject().addProperty("order", 4);
        dataFlow.get(1).getAsJsonObject().addProperty("order", 4);

        ReportValidationException error = assertThrows(
                ReportValidationException.class,
                () -> validator.validateRepository(response.toString(), snapshot, workspace.resolve("repository"))
        );

        assertTrue(error.getMessage().contains("order 无效或在同一血缘中重复"));
    }

    @Test
    void acceptsAtomicOneStepBusinessFlow() throws Exception {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject shallow = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        JsonArray steps = shallow.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children");
        while (steps.size() > 1) steps.remove(steps.size() - 1);

        shallow.getAsJsonObject("flow_map").getAsJsonArray("children").get(0).getAsJsonObject()
                .getAsJsonArray("data_flow").remove(1);

        JsonObject validated = validator.validateRepository(
                shallow.toString(), snapshot, workspace.resolve("repository"));
        assertEquals(1, validated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children").size());
    }

    @Test
    void rejectsLaterIndependentReaderInsideTheCurrentExecution() {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject invalid = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        invalid.getAsJsonObject("flow_map").getAsJsonArray("children").get(0).getAsJsonObject()
                .getAsJsonArray("data_flow").get(1).getAsJsonObject()
                .addProperty("timing", "later_independent");

        ReportValidationException error = assertThrows(
                ReportValidationException.class,
                () -> validator.validateRepository(invalid.toString(), snapshot, workspace.resolve("repository"))
        );

        assertTrue(error.getMessage().contains("独立触发不能伪装成当前执行步骤"));
    }

    @Test
    void acceptsEmptyPresentationBoundaryWhenSourceDoesNotProveOne() throws Exception {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject invalid = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        invalid.getAsJsonArray("business_domains").get(0).getAsJsonObject()
                .add("receives", new JsonArray());

        JsonObject validated = validator.validateRepository(
                invalid.toString(), snapshot, workspace.resolve("repository"));
        assertTrue(validated.getAsJsonArray("business_domains").get(0).getAsJsonObject()
                .getAsJsonArray("receives").isEmpty());
    }

    @Test
    void acceptsBusinessOutcomeBranchesWithoutInventingFlowTargets() throws Exception {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject report = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        JsonObject branch = JsonParser.parseString("""
                {"label":"Sensitive question","outcome":"terminal","meaning":"Return a rejection without generation"}
                """).getAsJsonObject();
        report.getAsJsonObject("flow_map").getAsJsonArray("children").get(0).getAsJsonObject()
                .getAsJsonArray("children").get(1).getAsJsonObject().getAsJsonArray("branches").add(branch);

        JsonObject validated = validator.validateRepository(
                report.toString(), snapshot, workspace.resolve("repository")
        );

        assertEquals("terminal", validated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children").get(1).getAsJsonObject()
                .getAsJsonArray("branches").get(0).getAsJsonObject().get("outcome").getAsString());
    }

    @Test
    void rejectsHiddenUncertaintyAfterAllUnknownsAreCleared() {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject report = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        report.getAsJsonObject("business_overview").getAsJsonArray("plain_story")
                .set(1, JsonParser.parseString("\"当前证据不能确认是否返回回复\""));

        ReportValidationException error = assertThrows(
                ReportValidationException.class,
                () -> validator.validateRepository(report.toString(), snapshot, workspace.resolve("repository"))
        );

        assertTrue(error.getMessage().contains("unknowns 已清零，但正文仍保留未确认表述"));
    }

    @Test
    void rejectsSourceBackedBusinessObjectWithUnknownStorageKind() {
        EvidencePack snapshot = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "treehash", "domain-fingerprint",
                List.of(), "", List.of(), List.of("src/App.java")
        );
        JsonObject report = JsonParser.parseString(businessDomainReport()).getAsJsonObject();
        report.getAsJsonObject("business_overview").getAsJsonArray("business_objects")
                .get(0).getAsJsonObject().addProperty("storage_kind", "unknown");

        ReportValidationException error = assertThrows(
                ReportValidationException.class,
                () -> validator.validateRepository(report.toString(), snapshot, workspace.resolve("repository"))
        );

        assertTrue(error.getMessage().contains("不能把 storage_kind 留为 unknown"));
    }

    private EvidencePack evidence() {
        CommitInfo commit = new CommitInfo(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                List.of("parent"),
                "A",
                "2026-08-13T10:00:00+08:00",
                "Add behavior"
        );
        EvidencePack.CommitEvidence commitEvidence = new EvidencePack.CommitEvidence(
                commit,
                "parent",
                "M\tsrc/App.java",
                List.of("src/App.java")
        );
        return new EvidencePack(
                Path.of("/repo"),
                "head",
                commit.hash(),
                "treehash",
                "fingerprint",
                List.of(commitEvidence),
                List.of("src/App.java")
        );
    }

    private String validReport() {
        return """
                {
                  "schema":"code-architecture-report/v1",
                  "source_format":"code-change-walkthrough/v2",
                  "title":"Test",
                  "summary":"Test flow",
                  "analysis_focus":{},
                  "reader_guide":{},
                  "architecture_design":{
                    "principles":[],
                    "lanes":[{"id":"core","name":"Core","code_label":"src","represents":"behavior","responsibilities":["run"],"why_here":"owns behavior","receives":[],"produces":[],"not_responsible":[],"source_node_ids":["app.run"]}],
                    "contracts":[],
                    "risks":[]
                  },
                  "change_summary":{"headline":"Run behavior changed","before":"Old behavior","after":"New behavior","business_impact":"Users observe the new result"},
                  "flow_map":{"id":"root","title":"Flow","summary":"Flow","children":[{"id":"flow.run","title":"Run","summary":"Run","kind":"stage","lane_id":"core","change_status":"changed","commit_ids":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"change_detail":{"before":"Old","after":"New","reason":"Selected change","impact":"New result","commit_ids":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]},"children":[],"branches":[],"contract_in_ids":[],"contract_out_ids":[],"source_node_ids":["app.run"]}]},
                  "scope":{},
                  "comparison":{"mode":"selected_commits","selected_commits":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"target_commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","target_tree":"treehash","fingerprint":"fingerprint"},
                  "features":[],"services":[],
                  "nodes":[{"id":"app.run","kind":"method","label":"run","file":"src/App.java","line":1,"evidence":"direct_source","change":"changed","changed_in_commits":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}],
                  "edges":[],"scenarios":[],"data_structures":[],"tables":[],"evidence":[],"unknowns":[],
                  "commit_evolution":[{"commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","subject":"Add behavior","business_purpose":"run","architecture_effect":"adds core","affected_node_ids":["app.run"],"evidence_paths":["src/App.java"]}],
                  "review_findings":[]
                }
                """;
    }

    private String businessDomainReport() {
        return """
                {
                  "schema":"code-architecture-report/v1",
                  "source_format":"business-domain-walkthrough/v1",
                  "title":"Chat","summary":"Complete chat flow",
                  "analysis_focus":{},"reader_guide":{"title":"Chat"},
                  "business_overview":{"purpose":"Let a user chat","primary_actor":"User",
                    "plain_story":["User sends a message.","The service validates and generates a reply.","The reply returns to the user."],
                    "actors":[{"name":"User","goal":"Get a reply","enters_via":"Chat API"}],
                    "domain_relationships":[],
                    "terms":[{"term":"Session","plain_meaning":"One conversation","why_it_matters":"Carries context"}],
                    "business_objects":[{"id":"message","name":"Message","plain_meaning":"One question and reply","storage_kind":"payload","lifecycle":"Enters from the client and returns as a reply","field_groups":[{"name":"Content","role":"content","fields":["question -> reply"],"meaning":"Conversation text"}],"file":"src/App.java","line":1,"symbol":"run","evidence":"direct_source","confidence":"high"}],
                    "reading_order":["chat"]},
                  "business_domains":[{"id":"chat","name":"Chat","purpose":"Own chat execution",
                    "why_here":"Owns the request lifecycle","actors":["User"],"owns":["messages"],"receives":["question"],"produces":["reply"],"not_responsible":["client rendering"],"depends_on":[],"flow_ids":["flow.chat"],"source_node_ids":["app.run"]}],
                  "architecture_design":{"principles":[],"lanes":[{"id":"core","name":"Core","code_label":"src",
                    "represents":"chat","responsibilities":["run"],"why_here":"owns chat","receives":["question"],"produces":["reply"],
                    "not_responsible":["client rendering"],"source_node_ids":["app.run"]}],"contracts":[],"risks":[]},
                  "flow_map":{"id":"root","execution":"independent","children":[{"id":"flow.chat","flow_scope":"business",
                    "title":"Complete chat","summary":"User gets a reply","flow_type":"request","execution_scope":"single_trigger","actor":"User","trigger":"Send message","routing_condition":"Chat route receives a message",
                    "preconditions":["Session exists"],"outcome":"Reply returned","end_title":"User sees reply",
                    "entry_source":{"step_id":"flow.step1","entry_kind":"route","meaning":"Chat route accepts the request","file":"src/App.java","line":1,"symbol":"run","evidence":"direct_source","confidence":"high"},
                    "data_subject":"One chat message","primary_origin_id":"message-origin",
                    "data_reads":["Session"],"data_writes":["Message"],"failure_paths":["Invalid session"],
                    "data_origins":[{"id":"message-origin","role":"primary","data":"Message","meaning":"The question to answer","source_kind":"api","source":"Client","entry":"Chat API","owner":"User","joins_step_id":"flow.step1","upstream_producer_status":"confirmed","file":"src/App.java","line":1,"symbol":"run","evidence":"direct_source","confidence":"high"}],
                    "data_flow":[
                      {"id":"message-in","lineage_id":"message-origin","order":1,"step_id":"flow.step1","phase":"ingest","timing":"same_execution","plain_action":"The question enters the chat service","data":"Message","from":"Client","to":"Chat","via":"http","transformation":"parse","storage":"memory","consumer":"Generator","file":"src/App.java","line":1,"symbol":"run","evidence":"direct_source","confidence":"high"},
                      {"id":"reply-out","lineage_id":"message-origin","order":2,"step_id":"flow.step4","phase":"deliver","timing":"same_execution","plain_action":"The reply returns to the user","data":"Reply","from":"Generator","to":"Client","via":"http","transformation":"serialize","storage":"none","consumer":"User","file":"src/App.java","line":1,"symbol":"run","evidence":"direct_source","confidence":"high"}],
                    "consumer_targets":[],
                    "lane_id":"core","change_status":"context","commit_ids":[],"branches":[],"contract_in_ids":[],
                    "contract_out_ids":[],"source_node_ids":["app.run"],"business_rules":[],
                    "children":[
                      {"id":"flow.step1","title":"Accept","summary":"Accept message","kind":"stage","execution":"same_execution","lane_id":"core","change_status":"context","commit_ids":[],"children":[],"branches":[],"contract_in_ids":[],"contract_out_ids":[],"source_node_ids":["app.run"],"business_rules":[]},
                      {"id":"flow.step2","title":"Validate","summary":"Validate session","kind":"decision","execution":"same_execution","lane_id":"core","change_status":"context","commit_ids":[],"children":[],"branches":[],"contract_in_ids":[],"contract_out_ids":[],"source_node_ids":["app.run"],"business_rules":[]},
                      {"id":"flow.step3","title":"Generate","summary":"Generate reply","kind":"stage","execution":"same_execution","lane_id":"core","change_status":"context","commit_ids":[],"children":[],"branches":[],"contract_in_ids":[],"contract_out_ids":[],"source_node_ids":["app.run"],"business_rules":[]},
                      {"id":"flow.step4","title":"Return","summary":"Return reply","kind":"success","execution":"same_execution","lane_id":"core","change_status":"context","commit_ids":[],"children":[],"branches":[],"contract_in_ids":[],"contract_out_ids":[],"source_node_ids":["app.run"],"business_rules":[]}
                    ]}]},
                  "scope":{"mode":"current_snapshot"},"change_summary":{},
                  "comparison":{"mode":"current_snapshot","selected_commits":[],"target_commit":"head","target_tree":"treehash","fingerprint":"domain-fingerprint"},
                  "features":[],"services":[],"nodes":[{"id":"app.run","kind":"method","label":"run","file":"src/App.java","line":1,"end_line":1,"responsibility":"chat","inputs":[],"outputs":[],"feature_ids":[],"feature_roles":{},"change":"unchanged","changed_in_commits":[],"source_kind":"repository","evidence":"direct_source","confidence":"high"}],
                  "edges":[],"scenarios":[],"data_structures":[],"tables":[],"evidence":[],"unknowns":[],
                  "commit_evolution":[],"review_findings":[],"revision_history":[]
                }
                """;
    }

    private List<String> strings(JsonArray array) {
        return array.asList().stream().map(item -> item.getAsString()).toList();
    }
}
