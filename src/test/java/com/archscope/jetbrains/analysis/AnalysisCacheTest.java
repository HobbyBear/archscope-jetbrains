package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class AnalysisCacheTest {
    @TempDir
    Path directory;

    @Test
    void reusesOnlyTheExactFingerprintAndTargetCommit() {
        AnalysisCache cache = new AnalysisCache(directory);
        EvidencePack evidence = evidence("fingerprint-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        JsonObject report = new JsonObject();
        report.addProperty("title", "Cached report");

        cache.store(evidence, "provider-a/domain-v1", report);

        assertEquals("Cached report", cache.load(evidence, "provider-a/domain-v1").get("title").getAsString());
        assertNull(cache.load(evidence, "provider-b/domain-v1"));
        assertNull(cache.load(evidence("fingerprint-b", evidence.targetCommit()), "provider-a/domain-v1"));
        assertNull(cache.load(evidence("fingerprint-a", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "provider-a/domain-v1"));
    }

    private EvidencePack evidence(String fingerprint, String target) {
        CommitInfo commit = new CommitInfo(target, List.of("parent"), "A", "2026-08-15T00:00:00+08:00", "Change");
        return new EvidencePack(
                Path.of("/repo"), "head", "parent", target, "tree", fingerprint,
                List.of(new EvidencePack.CommitEvidence(commit, "parent", "M\tsrc/App.java", List.of("src/App.java"))),
                "M\tsrc/App.java", List.of("src/App.java"), List.of("src/App.java")
        );
    }
}
