package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArchitectureReportFileEditorProviderTest {
    @Test
    void claimsOnlyArchitectureReportFilesAndHidesTheTextEditor() {
        AnalysisResult result = new AnalysisResult(
                "{}",
                "<html><body>report</body></html>",
                "fingerprint",
                "63003031d076827832c2d2f20f8762d2fba5da27"
        );
        ArchitectureReportVirtualFile report = new ArchitectureReportVirtualFile(result, Path.of("/repo"));
        ArchitectureReportFileEditorProvider provider = new ArchitectureReportFileEditorProvider();
        Project project = (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> null
        );

        assertTrue(provider.accept(project, report));
        assertFalse(provider.accept(project, new LightVirtualFile("plain.txt")));
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.getPolicy());
        assertEquals("<html><body>report</body></html>", report.reportHtml());
        assertEquals(Path.of("/repo"), report.repositoryRoot());
        assertFalse(report.isWritable());
    }

    @Test
    void archivedBusinessReportCanRecollectEvidenceForRefinement() {
        AnalysisResult result = new AnalysisResult("{}", "<html></html>", "fingerprint", "0123456789abcdef");
        AnalysisRequest request = AnalysisRequest.businessDomain(Path.of("/repo"), "分析聊天");

        ArchitectureReportVirtualFile report = new ArchitectureReportVirtualFile(
                result, Path.of("/repo"), request, null);

        assertTrue(report.supportsRefinement());
    }
}
