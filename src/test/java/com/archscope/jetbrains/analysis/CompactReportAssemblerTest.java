package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CompactReportAssemblerTest {
    @TempDir
    Path workspace;

    @Test
    void deterministicallyBuildsAValidatedReportContract() throws Exception {
        Path source = workspace.resolve("repository/src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App { void run() {} }\n");
        CommitInfo commit = new CommitInfo(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", List.of("parent"), "A",
                "2026-08-14T10:00:00+08:00", "Change run"
        );
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "parent", commit.hash(), "tree", "fingerprint",
                List.of(new EvidencePack.CommitEvidence(commit, "parent", "M\tsrc/App.java", List.of("src/App.java"))),
                "M\tsrc/App.java", List.of("src/App.java"), List.of("src/App.java")
        );
        AnalysisRequest request = new AnalysisRequest(Path.of("/repo"), List.of(commit), commit.hash(), "Explain run");
        String compact = """
                {
                  "schema":"closed-change-analysis/v1","title":"Run changed","summary":"Run returns the new result.",
                  "groups":[{"id":"run","title":"Run behavior","summary":"Caller invokes the changed run method.",
                    "before":"old","after":"new","reason":"selected commit","impact":"new result",
                    "commit_ids":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],
                    "steps":[{"id":"run","title":"Run","summary":"Execute new behavior","kind":"stage",
                      "change_status":"changed","commit_ids":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],
                      "file":"src/App.java","line":1,"symbol":"App.run",
                      "evidence":"direct_source","confidence":"high"}]}],
                  "commit_notes":[{"commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","business_purpose":"change run","architecture_effect":"updates core"}],
                  "findings":[],"unknowns":[]
                }
                """;

        JsonObject assembled = new CompactReportAssembler().assemble(compact, request, evidence);
        JsonObject validated = new ReportValidator().validate(new Gson().toJson(assembled), evidence, workspace);

        assertEquals("code-architecture-report/v1", validated.get("schema").getAsString());
        assertEquals(1, validated.getAsJsonArray("nodes").size());
        assertEquals(1, validated.getAsJsonObject("flow_map").getAsJsonArray("children").size());
        assertEquals("independent", validated.getAsJsonObject("flow_map").get("execution").getAsString());
        assertEquals("business", validated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().get("flow_scope").getAsString());
        assertEquals("old", validated.getAsJsonObject("flow_map").getAsJsonArray("children")
                .get(0).getAsJsonObject().getAsJsonObject("change_detail").get("before").getAsString());
        assertEquals(1, validated.getAsJsonObject("change_summary").get("changed_flow_count").getAsInt());
        assertEquals(0, validated.getAsJsonObject("change_summary").get("affected_flow_count").getAsInt());
    }
}
