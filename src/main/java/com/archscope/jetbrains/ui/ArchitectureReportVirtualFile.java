package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.LightVirtualFile;

import java.nio.file.Path;

final class ArchitectureReportVirtualFile extends LightVirtualFile {
    private final Path repositoryRoot;
    private final AnalysisRequest request;
    private volatile EvidencePack evidence;
    private volatile AnalysisResult currentResult;

    ArchitectureReportVirtualFile(AnalysisResult result, Path repositoryRoot) {
        this(result, repositoryRoot, null, null);
    }

    ArchitectureReportVirtualFile(
            AnalysisResult result,
            Path repositoryRoot,
            AnalysisRequest request,
            EvidencePack evidence
    ) {
        super(
                "AI Code Review " + result.targetCommit().substring(0, 12),
                PlainTextFileType.INSTANCE,
                result.reportHtml()
        );
        this.repositoryRoot = repositoryRoot;
        this.request = request;
        this.evidence = evidence;
        this.currentResult = result;
        setWritable(false);
    }

    Path repositoryRoot() {
        return repositoryRoot;
    }

    String reportHtml() {
        return currentResult.reportHtml();
    }

    AnalysisResult currentResult() {
        return currentResult;
    }

    AnalysisRequest request() {
        return request;
    }

    EvidencePack evidence() {
        return evidence;
    }

    boolean supportsRefinement() {
        return request != null;
    }

    void updateEvidence(EvidencePack evidence) {
        this.evidence = evidence;
    }

    void updateResult(AnalysisResult result) {
        currentResult = result;
    }
}
