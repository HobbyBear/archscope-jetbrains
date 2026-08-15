package com.archscope.jetbrains.model;

public record AnalysisResult(
        String reportJson,
        String reportHtml,
        String fingerprint,
        String targetCommit
) {
}

