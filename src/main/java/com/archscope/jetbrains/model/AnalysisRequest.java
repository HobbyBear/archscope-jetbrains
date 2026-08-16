package com.archscope.jetbrains.model;

import java.nio.file.Path;
import java.util.List;

public record AnalysisRequest(
        Path repositoryRoot,
        List<CommitInfo> selectedCommits,
        String targetCommit,
        String focus,
        Mode mode,
        AnalysisGuidance guidance,
        OutputLanguage outputLanguage
) {
    public AnalysisRequest {
        guidance = guidance == null ? AnalysisGuidance.EMPTY : guidance;
        outputLanguage = outputLanguage == null ? OutputLanguage.CHINESE : outputLanguage;
    }

    public AnalysisRequest(Path repositoryRoot, List<CommitInfo> selectedCommits, String targetCommit, String focus) {
        this(repositoryRoot, selectedCommits, targetCommit, focus, Mode.SELECTED_CHANGES,
                AnalysisGuidance.EMPTY, OutputLanguage.CHINESE);
    }

    public AnalysisRequest(
            Path repositoryRoot,
            List<CommitInfo> selectedCommits,
            String targetCommit,
            String focus,
            Mode mode
    ) {
        this(repositoryRoot, selectedCommits, targetCommit, focus, mode,
                AnalysisGuidance.EMPTY, OutputLanguage.CHINESE);
    }

    public AnalysisRequest(
            Path repositoryRoot,
            List<CommitInfo> selectedCommits,
            String targetCommit,
            String focus,
            Mode mode,
            AnalysisGuidance guidance
    ) {
        this(repositoryRoot, selectedCommits, targetCommit, focus, mode, guidance, OutputLanguage.CHINESE);
    }

    public static AnalysisRequest businessDomain(Path repositoryRoot, String focus) {
        return businessDomain(repositoryRoot, focus, AnalysisGuidance.EMPTY);
    }

    public static AnalysisRequest businessDomain(Path repositoryRoot, String focus, AnalysisGuidance guidance) {
        return businessDomain(repositoryRoot, focus, guidance, OutputLanguage.CHINESE);
    }

    public static AnalysisRequest businessDomain(
            Path repositoryRoot,
            String focus,
            AnalysisGuidance guidance,
            OutputLanguage outputLanguage
    ) {
        return new AnalysisRequest(repositoryRoot, List.of(), "", focus, Mode.BUSINESS_DOMAIN, guidance, outputLanguage);
    }

    public boolean isBusinessDomain() {
        return mode == Mode.BUSINESS_DOMAIN;
    }

    public enum Mode {
        SELECTED_CHANGES,
        BUSINESS_DOMAIN
    }

    public enum OutputLanguage {
        CHINESE("zh-CN"),
        ENGLISH("en");

        private final String code;

        OutputLanguage(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public boolean isEnglish() {
            return this == ENGLISH;
        }
    }
}
