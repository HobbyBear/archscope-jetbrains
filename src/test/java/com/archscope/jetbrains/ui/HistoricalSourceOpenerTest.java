package com.archscope.jetbrains.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HistoricalSourceOpenerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesReportSymbolsForPsiLookup() {
        assertEquals("analyzeBusinessDomain", HistoricalSourceOpener.normalizeSymbol(".analyzeBusinessDomain()"));
        assertEquals("publishCreator", HistoricalSourceOpener.normalizeSymbol("CreatorService#publishCreator(String)"));
        assertEquals("Chat", HistoricalSourceOpener.normalizeSymbol("(*Ws).Chat"));
        assertEquals("CheckChatContent", HistoricalSourceOpener.normalizeSymbol("(*ChatService).CheckChatContent(...)"));
        assertEquals("", HistoricalSourceOpener.normalizeSymbol(null));
    }

    @Test
    void mapsAReportPathToItsRenamedCurrentWorkspacePath() {
        assertEquals("src/current/OrderService.java", HistoricalSourceOpener.renamedPath("""
                M\tsrc/Other.java
                R094\tsrc/legacy/OrderService.java\tsrc/current/OrderService.java
                """, "src/legacy/OrderService.java"));
        assertEquals("src/unchanged.java", HistoricalSourceOpener.renamedPath(
                "D\tsrc/deleted.java", "src/unchanged.java"));
    }

    @Test
    void locatesGitRelativeSourceAboveAStoredSubprojectRoot() throws Exception {
        Path repository = temporaryDirectory.resolve("chat");
        Path storedRoot = repository.resolve("apps/chat");
        Path source = repository.resolve("apps/chat/service/character.go");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package service\n");

        assertEquals(repository.toAbsolutePath().normalize(),
                HistoricalSourceOpener.locateSourceRoot(storedRoot, "apps/chat/service/character.go"));
        assertEquals(storedRoot.toAbsolutePath().normalize(),
                HistoricalSourceOpener.locateSourceRoot(storedRoot, "service/character.go"));
    }
}
