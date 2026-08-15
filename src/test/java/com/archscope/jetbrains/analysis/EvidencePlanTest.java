package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EvidencePlanTest {
    @Test
    void keepsIndependentFlowsThatShareTheSameCoreChangedSourceAndCommit() throws Exception {
        String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        CommitInfo commit = new CommitInfo(hash, List.of("parent"), "A", "2026-08-14T10:00:00+08:00", "Chat");
        EvidencePack evidence = new EvidencePack(
                Path.of("/repo"), "head", "parent", hash, "tree", "fingerprint",
                List.of(new EvidencePack.CommitEvidence(
                        commit, "parent", "M\tsrc/chat.go\nM\tsrc/chat_test.go", List.of("src/chat.go", "src/chat_test.go")
                )),
                "M\tsrc/chat.go\nM\tsrc/chat_test.go", List.of("src/chat.go", "src/chat_test.go"),
                List.of("src/chat.go", "src/chat_test.go")
        );
        String response = """
                {"schema":"change-evidence-plan/v1","change_groups":[
                  {"id":"charge","title":"Charge","purpose":"settle","changed_paths":["src/chat.go"],"commit_ids":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"evidence_queries":[]},
                  {"id":"stop","title":"Stop","purpose":"cancel","changed_paths":["src/chat.go","src/chat_test.go"],"commit_ids":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"evidence_queries":[]}
                ]}
                """;

        EvidencePlan plan = EvidencePlan.parse(response, evidence);

        assertEquals(2, plan.groups().size());
        assertEquals("Charge", plan.groups().get(0).title());
        assertEquals("Stop", plan.groups().get(1).title());
    }
}
