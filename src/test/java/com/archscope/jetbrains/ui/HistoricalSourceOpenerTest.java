package com.archscope.jetbrains.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HistoricalSourceOpenerTest {
    @Test
    void normalizesReportSymbolsForPsiLookup() {
        assertEquals("analyzeBusinessDomain", HistoricalSourceOpener.normalizeSymbol(".analyzeBusinessDomain()"));
        assertEquals("publishCreator", HistoricalSourceOpener.normalizeSymbol("CreatorService#publishCreator(String)"));
        assertEquals("Chat", HistoricalSourceOpener.normalizeSymbol("(*Ws).Chat"));
        assertEquals("CheckChatContent", HistoricalSourceOpener.normalizeSymbol("(*ChatService).CheckChatContent(...)"));
        assertEquals("", HistoricalSourceOpener.normalizeSymbol(null));
    }
}
