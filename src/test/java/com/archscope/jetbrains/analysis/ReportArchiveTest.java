package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReportArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesListsAndLoadsARepositoryReport() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        AnalysisRequest request = AnalysisRequest.businessDomain(repository, "分析创作者流程");
        AnalysisResult result = new AnalysisResult(
                "{\"title\":\"creator\",\"analysis_diagnostics\":{\"elapsed_ms\":125432}}",
                "<html><body>creator</body></html>",
                "fingerprint",
                "63003031d076827832c2d2f20f8762d2fba5da27"
        );

        ReportArchive.Entry saved = archive.save(repository, request, result);
        List<ReportArchive.Entry> entries = archive.list(repository);
        AnalysisResult loaded = archive.load(entries.get(0));

        assertEquals(1, entries.size());
        assertEquals(saved.id(), entries.get(0).id());
        assertEquals("分析创作者流程", entries.get(0).focus());
        assertEquals(125432L, saved.elapsedMs());
        assertEquals(125432L, entries.get(0).elapsedMs());
        assertEquals(result, loaded);
        assertTrue(Files.isRegularFile(saved.directory().resolve("metadata.json")));
        assertEquals(125432L, JsonParser.parseString(Files.readString(saved.directory().resolve("metadata.json")))
                .getAsJsonObject().get("elapsed_ms").getAsLong());
    }

    @Test
    void keepsRepositoriesSeparated() throws Exception {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        archive.save(first, AnalysisRequest.businessDomain(first, "first"), result("one"));
        archive.save(second, AnalysisRequest.businessDomain(second, "second"), result("two"));

        assertEquals(List.of("first"), archive.list(first).stream().map(ReportArchive.Entry::focus).toList());
        assertEquals(List.of("second"), archive.list(second).stream().map(ReportArchive.Entry::focus).toList());
    }

    @Test
    void readsLegacyMetadataWithoutElapsedTime() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        ReportArchive.Entry saved = archive.save(
                repository,
                AnalysisRequest.businessDomain(repository, "legacy"),
                result("legacy")
        );
        Path metadataPath = saved.directory().resolve("metadata.json");
        JsonObject metadata = JsonParser.parseString(Files.readString(metadataPath)).getAsJsonObject();
        metadata.remove("elapsed_ms");
        Files.writeString(metadataPath, metadata.toString());

        List<ReportArchive.Entry> entries = archive.list(repository);

        assertEquals(1, entries.size());
        assertEquals(0L, entries.get(0).elapsedMs());
    }

    @Test
    void deletesOnlyTheSelectedReportDirectory() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        ReportArchive.Entry first = archive.save(
                repository,
                AnalysisRequest.businessDomain(repository, "first"),
                result("one")
        );
        ReportArchive.Entry second = archive.save(
                repository,
                AnalysisRequest.businessDomain(repository, "second"),
                result("two")
        );

        archive.delete(first);

        assertFalse(Files.exists(first.directory()));
        assertTrue(Files.isDirectory(second.directory()));
        assertEquals(List.of(second.id()), archive.list(repository).stream().map(ReportArchive.Entry::id).toList());
    }

    private AnalysisResult result(String fingerprint) {
        return new AnalysisResult("{}", "<html></html>", fingerprint, "0123456789abcdef");
    }
}
