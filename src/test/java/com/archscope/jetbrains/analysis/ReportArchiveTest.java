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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("creator", entries.get(0).title());
        assertEquals(125432L, saved.elapsedMs());
        assertEquals(125432L, entries.get(0).elapsedMs());
        assertEquals(result, loaded);
        assertTrue(Files.isRegularFile(saved.directory().resolve("metadata.json")));
        assertTrue(Files.isRegularFile(saved.directory().resolve("receipt.json")));
        JsonObject receipt = JsonParser.parseString(Files.readString(saved.directory().resolve("receipt.json"))).getAsJsonObject();
        assertEquals("codebecause-report-delivery/v1", receipt.get("schema").getAsString());
        assertEquals("verified", receipt.get("artifact_pairing").getAsString());
        assertEquals(125432L, JsonParser.parseString(Files.readString(saved.directory().resolve("metadata.json")))
                .getAsJsonObject().get("elapsed_ms").getAsLong());
    }

    @Test
    void rejectsAnArchivedArtifactThatNoLongerMatchesItsReceipt() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        ReportArchive.Entry saved = archive.save(
                repository,
                AnalysisRequest.businessDomain(repository, "tamper"),
                result("tamper")
        );
        Files.writeString(saved.directory().resolve("report.html"), "<html>changed</html>");

        assertThrows(java.io.IOException.class, () -> archive.load(saved));
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

    @Test
    void deletesSeveralValidatedReportDirectoriesTogether() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        ReportArchive.Entry first = archive.save(
                repository, AnalysisRequest.businessDomain(repository, "first"), result("one"));
        ReportArchive.Entry second = archive.save(
                repository, AnalysisRequest.businessDomain(repository, "second"), result("two"));
        ReportArchive.Entry remaining = archive.save(
                repository, AnalysisRequest.businessDomain(repository, "remaining"), result("three"));

        archive.deleteAll(List.of(first, second));

        assertFalse(Files.exists(first.directory()));
        assertFalse(Files.exists(second.directory()));
        assertTrue(Files.isDirectory(remaining.directory()));
        assertEquals(List.of(remaining.id()), archive.list(repository).stream().map(ReportArchive.Entry::id).toList());
    }

    @Test
    void validatesTheWholeBatchBeforeDeletingAnything() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        ReportArchive.Entry valid = archive.save(
                repository, AnalysisRequest.businessDomain(repository, "valid"), result("valid"));
        ReportArchive.Entry outside = new ReportArchive.Entry(
                "outside", valid.repositoryRoot(), valid.focus(), valid.title(), valid.functionSymbol(),
                valid.functionFile(), valid.mode(), valid.outputLanguage(), valid.targetCommit(), valid.fingerprint(),
                valid.createdAt(), valid.elapsedMs(), temporaryDirectory.resolve("outside"));

        assertThrows(java.io.IOException.class, () -> archive.deleteAll(List.of(valid, outside)));

        assertTrue(Files.isDirectory(valid.directory()));
        assertEquals(List.of(valid.id()), archive.list(repository).stream().map(ReportArchive.Entry::id).toList());
    }

    @Test
    void preservesFunctionFlowModeAcrossArchiveReloads() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(repository);
        ReportArchive archive = new ReportArchive(temporaryDirectory.resolve("archive"));
        com.archscope.jetbrains.model.FunctionTarget target = new com.archscope.jetbrains.model.FunctionTarget(
                repository, "service/score.go", "Score", "func Score()", 2, 20);
        AnalysisRequest request = AnalysisRequest.functionFlow(
                repository, target, null, AnalysisRequest.OutputLanguage.CHINESE);

        ReportArchive.Entry saved = archive.save(repository, request, new AnalysisResult("""
                {"title":"评分业务流程","function_target":{"symbol":"Score","file":"service/score.go"}}
                """, "<html data-diagram-checks=\"12/12\"></html>", "function", "0123456789abcdef"));
        Path metadataPath = saved.directory().resolve("metadata.json");
        JsonObject oldMetadata = JsonParser.parseString(Files.readString(metadataPath)).getAsJsonObject();
        oldMetadata.addProperty("schema", "archscope-report-archive/v2");
        oldMetadata.remove("title");
        oldMetadata.remove("function_symbol");
        oldMetadata.remove("function_file");
        Files.writeString(metadataPath, oldMetadata.toString());

        ReportArchive.Entry entry = archive.list(repository).get(0);
        assertEquals(AnalysisRequest.Mode.FUNCTION_FLOW, entry.mode());
        assertEquals("评分业务流程", entry.title());
        assertEquals("Score", entry.functionSymbol());
        assertEquals("service/score.go", entry.functionFile());
        assertEquals("12/12", JsonParser.parseString(Files.readString(entry.directory().resolve("receipt.json")))
                .getAsJsonObject().get("diagram_validation").getAsString());
        assertTrue(archive.load(entry).reportHtml().contains("data-action=\"detail-close\""));
    }

    private AnalysisResult result(String fingerprint) {
        return new AnalysisResult("{}", "<html></html>", fingerprint, "0123456789abcdef");
    }
}
