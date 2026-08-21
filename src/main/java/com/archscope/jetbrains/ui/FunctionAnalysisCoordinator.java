package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.analysis.FunctionFlowAnalysisService;
import com.archscope.jetbrains.analysis.ModelClientRegistry;
import com.archscope.jetbrains.analysis.ModelStreamEvent;
import com.archscope.jetbrains.analysis.ReportArchive;
import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.AnalysisGuidance;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.EvidencePack;
import com.archscope.jetbrains.model.FunctionTarget;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;

final class FunctionAnalysisCoordinator {
    private FunctionAnalysisCoordinator() {
    }

    static void configureAndAnalyze(Project project, FunctionTarget target) {
        FunctionAnalysisDialog dialog = new FunctionAnalysisDialog(project, target);
        if (!dialog.showAndGet()) return;
        analyze(project, target, dialog.additionalPrompt());
    }

    static void openLatestOrAnalyze(Project project, FunctionTarget target) {
        new Task.Backgroundable(project, PluginText.text("查找函数流程历史", "Find function-flow history"), true) {
            private ReportArchive.Entry entry;
            private AnalysisResult result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    ReportArchive archive = new ReportArchive();
                    entry = FunctionReportSupport.latest(archive, target.repositoryRoot(), target.stableId()).orElse(null);
                    if (entry != null) result = archive.load(entry);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public void onSuccess() {
                if (entry == null || result == null) {
                    configureAndAnalyze(project, target);
                    return;
                }
                AnalysisRequest request = request(project, target, entry.outputLanguage());
                open(project, target.repositoryRoot(), request, null, result);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                showError(project, error);
            }
        }.queue();
    }

    private static void analyze(Project project, FunctionTarget target, String prompt) {
        FunctionAnalysisRunDialog runDialog = new FunctionAnalysisRunDialog(project, target.displayName());
        runDialog.show();
        new Task.Backgroundable(project, PluginText.text("分析函数业务流程", "Analyze function flow"), true) {
            private AnalysisRequest request;
            private EvidencePack evidence;
            private AnalysisResult result;
            private String archiveWarning;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                runDialog.attach(indicator);
                try {
                    String snapshotStatus = PluginText.text("正在锁定 Git 快照", "Locking the Git snapshot");
                    runDialog.updateStatus(snapshotStatus);
                    runDialog.accept(ModelStreamEvent.status(snapshotStatus));
                    AnalysisRequest.OutputLanguage language = new OutputLanguageStore(project).load();
                    request = request(project, target, language);
                    evidence = new GitEvidenceService().collectSnapshot(request, indicator);
                    result = new FunctionFlowAnalysisService(
                            ModelClientRegistry.selected(new ModelProviderStore(project).load())
                    ).analyze(
                            request,
                            evidence,
                            target,
                            prompt,
                            indicator,
                            message -> {
                                indicator.setText(message);
                                runDialog.updateStatus(message);
                            },
                            runDialog::accept
                    );
                    try {
                        new ReportArchive().save(target.repositoryRoot(), request, result);
                    } catch (Exception exception) {
                        archiveWarning = exception.getMessage();
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public void onSuccess() {
                runDialog.completed(PluginText.text("分析完成，报告已在编辑器中打开。",
                        "Analysis completed; the report is open in the editor."));
                project.getMessageBus().syncPublisher(ReportHistoryListener.TOPIC).reportsChanged(target.repositoryRoot());
                open(project, target.repositoryRoot(), request, evidence, result);
                if (archiveWarning != null) {
                    Messages.showWarningDialog(project,
                            PluginText.text("函数报告已生成，但历史归档失败：", "The function report was generated, but archiving failed: ")
                                    + PluginText.userMessage(archiveWarning),
                            PluginText.text("归档失败", "Archiving failed"));
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Throwable cause = rootCause(error);
                runDialog.failed(cause.getMessage() == null
                        ? PluginText.text("函数分析失败，已保留执行过程。", "Function analysis failed; execution details were preserved.")
                        : PluginText.userMessage(cause.getMessage()));
            }

            @Override
            public void onCancel() {
                runDialog.cancelled();
            }
        }.queue();
    }

    private static AnalysisRequest request(
            Project project,
            FunctionTarget target,
            AnalysisRequest.OutputLanguage language
    ) {
        AnalysisGuidance guidance = new ProjectAnalysisGuidanceStore(project).load(target.repositoryRoot());
        AnalysisRequest request = AnalysisRequest.functionFlow(target.repositoryRoot(), target, guidance, language);
        String configured = new CliWorkingDirectoryStore(project).load(target.repositoryRoot()).strip();
        try {
            Path directory = configured.isBlank() ? target.repositoryRoot() : Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(directory)) return request.withCliWorkingDirectory(directory);
        } catch (RuntimeException ignored) {
            // Fall back to the repository root when a previously stored directory is no longer available.
        }
        return request.withCliWorkingDirectory(target.repositoryRoot());
    }

    private static void open(
            Project project,
            Path repositoryRoot,
            AnalysisRequest request,
            EvidencePack evidence,
            AnalysisResult result
    ) {
        ArchitectureReportVirtualFile file = new ArchitectureReportVirtualFile(result, repositoryRoot, request, evidence);
        FileEditorManager.getInstance(project).openFile(file, true);
    }

    private static void showError(Project project, Throwable error) {
        Throwable cause = rootCause(error);
        Messages.showErrorDialog(project,
                PluginText.userMessage(cause.getMessage() == null ? "Function analysis failed" : cause.getMessage()),
                PluginText.text("函数分析失败", "Function analysis failed"));
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }
}
