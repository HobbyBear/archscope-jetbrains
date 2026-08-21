package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.analysis.ReportArchive;
import com.archscope.jetbrains.model.AnalysisRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReportHistorySearchTest {
    @Test
    void findsFunctionReportsBySymbolPathTitleAndMultipleTerms() {
        ReportArchive.Entry entry = new ReportArchive.Entry(
                "report-1",
                Path.of("/repo"),
                "函数级业务流程：Character.GenStoryBg",
                "支线故事背景图生成流程",
                "Character.GenStoryBg",
                "apps/chat/controllers/character.go",
                AnalysisRequest.Mode.FUNCTION_FLOW,
                AnalysisRequest.OutputLanguage.CHINESE,
                "ec2876e6",
                "fingerprint",
                Instant.parse("2026-08-18T06:50:46Z"),
                235_025,
                Path.of("/archive/report-1")
        );

        assertTrue(ArchitectureToolWindowPanel.matchesHistory(entry, "GenStoryBg"));
        assertTrue(ArchitectureToolWindowPanel.matchesHistory(entry, "controllers character"));
        assertTrue(ArchitectureToolWindowPanel.matchesHistory(entry, "背景图生成"));
        assertFalse(ArchitectureToolWindowPanel.matchesHistory(entry, "Checkout"));
    }
}
