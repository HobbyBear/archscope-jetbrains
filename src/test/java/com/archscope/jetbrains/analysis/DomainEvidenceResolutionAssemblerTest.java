package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainEvidenceResolutionAssemblerTest {
    @Test
    void removesOnlyUnknownsBoundToTrackedSourceEvidence() throws Exception {
        JsonObject report = JsonParser.parseString("""
                {"summary":"old","business_overview":{"purpose":"old purpose","plain_story":["old story"],
                 "actors":[{"name":"old actor"}],"domain_relationships":[{"meaning":"event not proven"}],
                 "terms":[{"term":"old term"}],
                 "business_objects":[{"id":"context","plain_meaning":"old","storage_kind":"unknown","lifecycle":"not confirmed","field_groups":[]}]},
                 "unknowns":["confirmed question","unproven question"],
                 "nodes":[{"id":"old-inferred","evidence":"inferred","responsibility":"implementation 未完整展示"}],
                 "flow_map":{"id":"root","children":[{"id":"flow-1","summary":"old","children":[{"id":"step-1","summary":"old step","source_node_ids":["old-inferred"]}]}]}}
                """).getAsJsonObject();
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/creator.go")
        );
        String patch = """
                {"schema":"business-domain-evidence-resolution/v1","report_summary":"new",
                 "resolutions":[
                   {"question":"confirmed question","status":"confirmed","conclusion":"proved",
                    "file":"src/creator.go","line":12,"symbol":"Publish","evidence":"direct_source","confidence":"high","flow_id":"flow-1"},
                   {"question":"unproven question","status":"confirmed","conclusion":"invented",
                    "file":"src/missing.go","line":1,"symbol":"Missing","evidence":"direct_source","confidence":"high"}],
                 "new_unknowns":["new question"],
                 "overview_update":{"purpose":"new purpose","plain_story":["first","second","third"],
                   "actors":[{"name":"chat user"}],"domain_relationships":[{"meaning":"event emission confirmed"}],
                   "terms":[{"term":"runtime event"}],
                   "business_object_updates":[{"id":"context","storage_kind":"struct","lifecycle":"confirmed lifecycle"}]},
                 "flow_updates":[{"flow_id":"flow-1","summary":"updated"}],
                 "step_updates":[{"step_id":"step-1","source_question":"confirmed question","summary":"state confirmed","state_effects":[
                   {"state":"pending_streaming","effect":"deleted","when":"early return","meaning":"defer cleanup"}]}]}
                """;

        JsonObject updated = new DomainEvidenceResolutionAssembler().apply(patch, report, evidence);

        assertEquals("new", updated.get("summary").getAsString());
        assertEquals("new purpose", updated.getAsJsonObject("business_overview").get("purpose").getAsString());
        assertEquals("chat user", updated.getAsJsonObject("business_overview").getAsJsonArray("actors")
                .get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("event emission confirmed", updated.getAsJsonObject("business_overview")
                .getAsJsonArray("domain_relationships").get(0).getAsJsonObject().get("meaning").getAsString());
        assertEquals("runtime event", updated.getAsJsonObject("business_overview").getAsJsonArray("terms")
                .get(0).getAsJsonObject().get("term").getAsString());
        assertEquals("struct", updated.getAsJsonObject("business_overview").getAsJsonArray("business_objects")
                .get(0).getAsJsonObject().get("storage_kind").getAsString());
        assertEquals(List.of("unproven question", "new question"), updated.getAsJsonArray("unknowns").asList().stream()
                .map(item -> item.getAsJsonObject().get("question").getAsString()).toList());
        assertEquals(2, updated.getAsJsonArray("nodes").size());
        assertEquals(1, updated.getAsJsonArray("evidence_resolutions").size());
        assertEquals("updated", updated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().get("summary").getAsString());
        JsonObject step = updated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("state confirmed", step.get("summary").getAsString());
        assertEquals("deleted", step.getAsJsonArray("state_effects").get(0).getAsJsonObject()
                .get("effect").getAsString());
        assertEquals("domain-resolution-node-2", step.getAsJsonArray("source_node_ids").get(0).getAsString());
        assertTrue(updated.getAsJsonArray("nodes").get(1).getAsJsonObject().get("file").getAsString()
                .equals("src/creator.go"));
    }

    @Test
    void doesNotGrowUnknownsWhenAResolutionRoundMakesNoProgress() throws Exception {
        JsonObject report = JsonParser.parseString("""
                {"unknowns":["发布条件未知"],"nodes":[],"flow_map":{"id":"root","children":[]}}
                """).getAsJsonObject();
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/creator.go")
        );
        String patch = """
                {"schema":"business-domain-evidence-resolution/v1","resolutions":[
                  {"question":"发布条件未知","status":"unresolved","file":"","line":1,"symbol":""}],
                 "new_unknowns":["发布条件仍然未知（换一种说法）"],"flow_updates":[],"step_updates":[]}
                """;

        JsonObject updated = new DomainEvidenceResolutionAssembler().apply(patch, report, evidence);

        assertEquals(1, updated.getAsJsonArray("unknowns").size());
        assertEquals("发布条件未知", updated.getAsJsonArray("unknowns").get(0).getAsJsonObject()
                .get("question").getAsString());
    }

    @Test
    void shorterBranchEvidenceCannotReplaceTheMainDataLineage() throws Exception {
        JsonObject report = JsonParser.parseString("""
                {"unknowns":[],"nodes":[],"flow_map":{"id":"root","children":[{"id":"flow-1",
                 "data_origins":[{"data":"primary"}],
                 "data_flow":[{"order":1},{"order":2},{"order":3}]}]}}
                """).getAsJsonObject();
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "head", "head", "tree", "fingerprint",
                List.of(), "", List.of(), List.of("src/creator.go")
        );
        String patch = """
                {"schema":"business-domain-evidence-resolution/v1","resolutions":[],"new_unknowns":[],
                 "flow_updates":[{"flow_id":"flow-1","data_origins":[],
                   "data_flow":[{"order":1,"data":"side branch"}]}],"step_updates":[]}
                """;

        JsonObject updated = new DomainEvidenceResolutionAssembler().apply(patch, report, evidence);
        JsonObject flow = updated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject();
        assertEquals(1, flow.getAsJsonArray("data_origins").size());
        assertEquals(3, flow.getAsJsonArray("data_flow").size());
    }
}
