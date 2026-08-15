package com.archscope.jetbrains.model;

import java.nio.file.Path;
import java.util.List;

public record AnalysisRequest(
        Path repositoryRoot,
        List<CommitInfo> selectedCommits,
        String targetCommit,
        String focus,
        Mode mode,
        AnalysisGuidance guidance
) {
    public AnalysisRequest {
        guidance = guidance == null ? AnalysisGuidance.EMPTY : guidance;
    }

    public AnalysisRequest(Path repositoryRoot, List<CommitInfo> selectedCommits, String targetCommit, String focus) {
        this(repositoryRoot, selectedCommits, targetCommit, focus, Mode.SELECTED_CHANGES, AnalysisGuidance.EMPTY);
    }

    public AnalysisRequest(
            Path repositoryRoot,
            List<CommitInfo> selectedCommits,
            String targetCommit,
            String focus,
            Mode mode
    ) {
        this(repositoryRoot, selectedCommits, targetCommit, focus, mode, AnalysisGuidance.EMPTY);
    }

    public static AnalysisRequest businessDomain(Path repositoryRoot, String focus) {
        return businessDomain(repositoryRoot, focus, AnalysisGuidance.EMPTY);
    }

    public static AnalysisRequest businessDomain(Path repositoryRoot, String focus, AnalysisGuidance guidance) {
        return new AnalysisRequest(repositoryRoot, List.of(), "", focus, Mode.BUSINESS_DOMAIN, guidance);
    }

    public boolean isBusinessDomain() {
        return mode == Mode.BUSINESS_DOMAIN;
    }

    public enum Mode {
        SELECTED_CHANGES,
        BUSINESS_DOMAIN
    }
}
