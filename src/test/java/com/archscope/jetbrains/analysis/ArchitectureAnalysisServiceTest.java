package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArchitectureAnalysisServiceTest {
    @TempDir
    Path repository;

    @Test
    void usesOneModelTurnOnlyForBoundedAggregateChanges() {
        assertTrue(ArchitectureAnalysisService.shouldUseDirectAnalysis(30, 90_000));
        assertFalse(ArchitectureAnalysisService.shouldUseDirectAnalysis(31, 20_000));
        assertFalse(ArchitectureAnalysisService.shouldUseDirectAnalysis(3, 90_001));
    }

    @Test
    void usesIncrementalPatchOnlyForClosedNonStructuralRefinements() {
        JsonObject closed = JsonParser.parseString("""
                {"unknowns":[],"flow_map":{"id":"root","children":[{"id":"flow-1","children":[]}]}}
                """).getAsJsonObject();
        assertTrue(ArchitectureAnalysisService.shouldUseIncrementalDomainPatch(
                closed, "只修正数据来源顺序并补充对象生命周期"
        ));
        assertFalse(ArchitectureAnalysisService.shouldUseIncrementalDomainPatch(
                closed, "新增一条后台任务流程"
        ));
        closed.getAsJsonArray("unknowns").add("入口未知");
        assertFalse(ArchitectureAnalysisService.shouldUseIncrementalDomainPatch(closed, "补充入口"));
    }

    @Test
    void analysisCacheProfileIncludesTheRequestedBusinessTopic() {
        AnalysisRequest chat = AnalysisRequest.businessDomain(repository, "分析聊天逻辑");
        AnalysisRequest creator = AnalysisRequest.businessDomain(repository, "分析创作者逻辑");
        String first = ArchitectureAnalysisService.requestCacheProfile("queue", chat, "v-test");
        String second = ArchitectureAnalysisService.requestCacheProfile("queue", creator, "v-test");
        assertFalse(first.equals(second));
        assertEquals(first, ArchitectureAnalysisService.requestCacheProfile("queue", chat, "v-test"));
    }

    @Test
    void automaticallyExpandsEvidenceUntilUnknownsAreConfirmed() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("src/CreatorFlow.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                final class CreatorFlow {
                    void createCreator() {}
                    void reviewCreator() {}
                    void publishCreator() {}
                    void notifyCreator() {}
                }
                """);
        git("add", "src/CreatorFlow.java");
        git("commit", "-m", "creator flow");

        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析创作者从创建、审核到发布的完整流程");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        QueueModelClient client = new QueueModelClient(List.of(
                plan("createCreator"),
                report("[{\"question\":\"publishCreator 的审核通过条件未知\",\"kind\":\"rule\",\"flow_id\":\"creator-flow\",\"symbols\":[\"publishCreator\"],\"why_material\":\"无法确认发布条件\"}]"),
                resolution()
        ));
        System.setProperty("archscope.cacheDir", repository.resolve("cache").toString());
        AnalysisResult result;
        try {
            result = new ArchitectureAnalysisService(client).analyze(request, evidence, indicator());
        } finally {
            System.clearProperty("archscope.cacheDir");
        }

        JsonObject json = JsonParser.parseString(result.reportJson()).getAsJsonObject();
        assertEquals(3, client.calls);
        assertEquals(0, json.getAsJsonArray("unknowns").size());
        assertEquals(2, json.getAsJsonObject("analysis_diagnostics").get("evidence_rounds").getAsInt());
        assertEquals("confirmed", json.getAsJsonObject("analysis_diagnostics").get("stop_reason").getAsString());
    }

    @Test
    void refinesAClosedBusinessReportWithTwoModelCallsAndAnIncrementalPatch() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("src/CreatorFlow.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                final class CreatorFlow {
                    void createCreator() {}
                    void reviewCreator() {}
                    void publishCreator() {}
                    void notifyCreator() {}
                }
                """);
        git("add", "src/CreatorFlow.java");
        git("commit", "-m", "creator flow");

        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析创作者流程");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        QueueModelClient client = new QueueModelClient(List.of(
                plan("createCreator"),
                report("[]"),
                plan("publishCreator"),
                """
                {"schema":"business-domain-refinement-patch/v1","requires_structural_rebuild":false,
                 "reason":"只修正已有流程摘要","flow_updates":[
                   {"flow_id":"creator-flow","summary":"审核通过后发布并通知"}],
                 "step_updates":[],"domain_updates":[],"revision_summary":"补充发布结果"}
                """
        ));
        System.setProperty("archscope.cacheDir", repository.resolve("patch-cache").toString());
        try {
            ArchitectureAnalysisService service = new ArchitectureAnalysisService(client);
            AnalysisResult initial = service.analyze(request, evidence, indicator());
            AnalysisResult refined = service.refine(
                    request, evidence, initial.reportJson(), "只补充发布后的通知结果", indicator(), ignored -> {}
            );

            JsonObject json = JsonParser.parseString(refined.reportJson()).getAsJsonObject();
            JsonObject flow = json.getAsJsonObject("flow_map").getAsJsonArray("children")
                    .get(0).getAsJsonObject();
            assertEquals(4, client.calls);
            assertEquals("审核通过后发布并通知", flow.get("summary").getAsString());
            assertEquals(0, json.getAsJsonArray("unknowns").size());
            assertEquals("incremental_patch",
                    json.getAsJsonObject("analysis_diagnostics").get("stop_reason").getAsString());
            assertEquals("refinement",
                    json.getAsJsonObject("analysis_diagnostics").get("operation").getAsString());
            assertTrue(json.getAsJsonObject("analysis_diagnostics").has("phase_timings_ms"));
        } finally {
            System.clearProperty("archscope.cacheDir");
        }
    }

    @Test
    void stopsWhenAnUnknownHasNeitherAResolutionNorANewSourceFrontier() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Path source = repository.resolve("src/CreatorFlow.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                final class CreatorFlow {
                    void createCreator() {}
                    void reviewCreator() {}
                    void publishCreator() {}
                    void notifyCreator() {}
                }
                """);
        git("add", "src/CreatorFlow.java");
        git("commit", "-m", "creator flow");

        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析创作者发布流程");
        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());
        QueueModelClient client = new QueueModelClient(List.of(
                plan("createCreator"),
                report("[{\"question\":\"publishCreator 的审核通过条件未知\",\"kind\":\"rule\",\"flow_id\":\"creator-flow\",\"symbols\":[\"publishCreator\"],\"why_material\":\"无法确认发布条件\"}]"),
                unresolvedResolution()
        ));
        System.setProperty("archscope.cacheDir", repository.resolve("stable-cache").toString());
        AnalysisResult result;
        try {
            result = new ArchitectureAnalysisService(client).analyze(request, evidence, indicator());
        } finally {
            System.clearProperty("archscope.cacheDir");
        }

        JsonObject json = JsonParser.parseString(result.reportJson()).getAsJsonObject();
        assertEquals(3, client.calls);
        assertEquals(1, json.getAsJsonArray("unknowns").size());
        assertEquals("unknowns_stable",
                json.getAsJsonObject("analysis_diagnostics").get("stop_reason").getAsString());
    }

    @Test
    void evidenceIdentityIgnoresPlannerWordingButTracksDifferentSourceMatches() {
        String first = """
                {"candidate_excerpts":[],"query_results":[{"literal":"a","reason":"one","matches":[
                  {"path":"src/a.go","matched_line":3,"snippet":"3: first"}]}]}
                """;
        String sameEvidence = """
                {"candidate_excerpts":[],"query_results":[{"literal":"renamed","reason":"two","matches":[
                  {"path":"src/a.go","matched_line":3,"snippet":"3: first"}]}]}
                """;
        String newEvidence = """
                {"candidate_excerpts":[],"query_results":[{"literal":"b","matches":[
                  {"path":"src/a.go","matched_line":9,"snippet":"9: second"}]}]}
                """;

        assertEquals(
                ArchitectureAnalysisService.sourceEvidenceIdentity(first),
                ArchitectureAnalysisService.sourceEvidenceIdentity(sameEvidence)
        );
        assertFalse(ArchitectureAnalysisService.sourceEvidenceIdentity(first)
                .equals(ArchitectureAnalysisService.sourceEvidenceIdentity(newEvidence)));

        String firstControlScope = """
                {"query_results":[{"literal":"first","matches":[{"path":"src/a.go","matched_line":3,"snippet":"first"}]}],
                 "control_flow_excerpts":[{"path":"src/a.go","matched_lines":[3],"excerpt":"complete_function_scope:1-9\\n1:func A()"}]}
                """;
        String renamedControlScope = """
                {"query_results":[{"literal":"renamed","matches":[{"path":"src/a.go","matched_line":8,"snippet":"other hit"}]}],
                 "control_flow_excerpts":[{"path":"src/a.go","matched_lines":[8],"excerpt":"complete_function_scope:1-9\\n1:func A()"}]}
                """;
        assertEquals(
                ArchitectureAnalysisService.sourceEvidenceIdentity(firstControlScope),
                ArchitectureAnalysisService.sourceEvidenceIdentity(renamedControlScope)
        );
    }

    @Test
    void refinementKeepsEveryTrackedSourceAlreadyReferencedByTheCurrentReport() {
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/controller.go", "src/service.go", "src/store.go")
        );
        String report = """
                {"nodes":[{"file":"src/controller.go"},{"file":"src/service.go"}],
                 "business_overview":{"business_objects":[{"file":"src/controller.go",
                   "supporting_sources":[{"file":"src/store.go"},{"file":"secret.env"}]}]}}
                """;

        assertEquals(
                java.util.Set.of("src/controller.go", "src/service.go", "src/store.go"),
                ArchitectureAnalysisService.reportSourcePaths(report, evidence)
        );
    }

    private String plan(String literal) {
        return """
                {"schema":"business-domain-evidence-plan/v1","topic":"creator",
                 "likely_domains":[{"id":"creator","name":"创作者","purpose":"管理创作者生命周期"}],
                 "candidate_paths":["src/CreatorFlow.java"],
                 "queries":[{"literal":"%s","role":"state","reason":"定位流程状态"}]}
                """.formatted(literal);
    }

    private String report(String unknowns) {
        return """
                {"schema":"closed-business-domain-analysis/v1","title":"创作者流程","summary":"创建、审核并发布创作者",
                 "business_overview":{"purpose":"管理创作者发布","primary_actor":"运营",
                   "plain_story":["运营提交创作者资料。","系统审核资料。","审核通过后发布并通知。"],
                   "actors":[{"name":"运营","goal":"发布创作者","enters_via":"后台"}],
                   "domain_relationships":[],
                   "terms":[{"term":"创作者状态","plain_meaning":"创作者从创建到发布所处的阶段","why_it_matters":"决定是否可对外展示"}],
                   "business_objects":[{"id":"creator-record","name":"创作者记录","plain_meaning":"待审核和发布的创作者资料","storage_kind":"struct","lifecycle":"后台提交后经历审核和发布","field_groups":[{"name":"身份","role":"identity","fields":["creator id"],"meaning":"定位创作者"}],"file":"src/CreatorFlow.java","line":2,"symbol":"createCreator","evidence":"direct_source","confidence":"high"}],
                   "reading_order":["creator"]},
                 "domains":[{"id":"creator","name":"创作者管理","purpose":"管理生命周期","why_here":"拥有创作者状态","actors":["运营"],"owns":["创建","审核","发布"],"receives":["创作者资料"],"produces":["已发布创作者"],"not_responsible":["前端展示"],"depends_on":[]}],
                 "flows":[{"id":"creator-flow","domain_ids":["creator"],"title":"创建到发布","summary":"完整流程",
                   "flow_type":"request","execution_scope":"single_trigger",
                   "actor":"运营","trigger":"创建创作者","routing_condition":"运营调用 createCreator","preconditions":["运营已登录"],"outcome":"创作者已发布","end_title":"发布完成",
                   "entry_source":{"step_id":"s1","entry_kind":"public_caller","meaning":"创建方法是本测试仓库可见入口","file":"src/CreatorFlow.java","line":2,"symbol":"createCreator","evidence":"direct_source","confidence":"high"},
                   "data_subject":"一条创作者记录","primary_origin_id":"creator-origin",
                   "data_reads":["创作者"],"data_writes":["创作者状态"],"failure_paths":["审核拒绝"],"business_rules":[],
                   "data_origins":[{"id":"creator-origin","role":"primary","data":"创作者资料","meaning":"创建与审核的主体","source_kind":"api","source":"运营后台提交","entry":"createCreator","owner":"运营","joins_step_id":"s1","upstream_producer_status":"confirmed","file":"src/CreatorFlow.java","line":2,"symbol":"createCreator","evidence":"direct_source","confidence":"high"}],
                   "data_flow":[
                     {"id":"creator-in","lineage_id":"creator-origin","order":1,"step_id":"s1","phase":"ingest","timing":"same_execution","plain_action":"运营资料创建为创作者记录","data":"创作者资料","from":"运营后台","to":"创建服务","via":"call","transformation":"创建记录","storage":"创作者记录","consumer":"reviewCreator","file":"src/CreatorFlow.java","line":2,"symbol":"createCreator","evidence":"direct_source","confidence":"high"},
                     {"id":"creator-published","lineage_id":"creator-origin","order":2,"step_id":"s3","phase":"persist","timing":"same_execution","plain_action":"审核通过后记录变为已发布","data":"已发布创作者","from":"审核服务","to":"发布结果","via":"call","transformation":"状态改为发布","storage":"创作者状态","consumer":"notifyCreator","file":"src/CreatorFlow.java","line":4,"symbol":"publishCreator","evidence":"direct_source","confidence":"high"}],
                   "consumer_targets":[],
                   "steps":[
                     {"id":"s1","title":"创建","summary":"创建记录","kind":"stage","execution":"same_execution","domain_id":"creator","file":"src/CreatorFlow.java","line":2,"symbol":"createCreator","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"创建","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]},
                     {"id":"s2","title":"审核","summary":"审核记录","kind":"decision","execution":"same_execution","domain_id":"creator","file":"src/CreatorFlow.java","line":3,"symbol":"reviewCreator","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"审核","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]},
                     {"id":"s3","title":"发布","summary":"发布记录","kind":"stage","execution":"same_execution","domain_id":"creator","file":"src/CreatorFlow.java","line":4,"symbol":"publishCreator","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"发布","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]},
                     {"id":"s4","title":"通知","summary":"通知结果","kind":"success","execution":"same_execution","domain_id":"creator","file":"src/CreatorFlow.java","line":5,"symbol":"notifyCreator","node_kind":"method","inputs":[],"outputs":[],"relation_kind":"call","relation_label":"通知","evidence":"direct_source","confidence":"high","business_rules":[],"branches":[]}]}],
                 "unknowns":%s,"revision_history":[]}
                """.formatted(unknowns);
    }

    private String resolution() {
        return """
                {"schema":"business-domain-evidence-resolution/v1","report_summary":"创建、审核、发布均已由源码确认",
                 "resolutions":[{"question":"publishCreator 的审核通过条件未知","status":"confirmed",
                   "conclusion":"审核通过后调用发布方法","file":"src/CreatorFlow.java","line":4,
                   "symbol":"publishCreator","evidence":"direct_source","confidence":"high","flow_id":"creator-flow"}],
                 "new_unknowns":[],"flow_updates":[{"flow_id":"creator-flow","summary":"审核通过后发布"}]}
                """;
    }

    private String unresolvedResolution() {
        return """
                {"schema":"business-domain-evidence-resolution/v1","resolutions":[
                  {"question":"publishCreator 的审核通过条件未知","status":"unresolved","conclusion":"当前批次不能确认",
                   "file":"","line":1,"symbol":"","evidence":"inferred","confidence":"low","flow_id":"creator-flow"}],
                 "new_unknowns":[],"next_frontier_queries":[],"flow_updates":[],"step_updates":[]}
                """;
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

    private static final class QueueModelClient implements ModelClient {
        private final Queue<String> responses;
        private int calls;

        private QueueModelClient(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public String id() {
            return "queue-model";
        }

        @Override
        public String displayName() {
            return "Queue Model";
        }

        @Override
        public String complete(
                String systemPrompt,
                String userPrompt,
                Path workingDirectory,
                ProgressIndicator indicator,
                String stage,
                Consumer<String> statusListener,
                WorkspaceAccess workspaceAccess
        ) throws ModelClientException {
            calls++;
            String response = responses.poll();
            if (response == null) throw new ModelClientException("Unexpected model call " + calls);
            return response;
        }
    }
}
