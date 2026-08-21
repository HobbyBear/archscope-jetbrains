package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.analysis.ArchitectureAnalysisService;
import com.archscope.jetbrains.analysis.FunctionFlowAnalysisService;
import com.archscope.jetbrains.analysis.ModelClientRegistry;
import com.archscope.jetbrains.analysis.ReportArchive;
import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.EvidencePack;
import com.archscope.jetbrains.model.AnalysisResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.concurrent.atomic.AtomicBoolean;

final class ArchitectureReportFileEditor extends UserDataHolderBase implements FileEditor {
    private static final Gson GSON = new Gson();
    private final ArchitectureReportVirtualFile file;
    private final JPanel component = new JPanel(new BorderLayout());
    private final PropertyChangeSupport propertyChanges = new PropertyChangeSupport(this);
    private JBCefBrowser browser;
    private JBCefJSQuery sourceQuery;
    private JBCefJSQuery refineQuery;
    private final AtomicBoolean refining = new AtomicBoolean();
    private boolean disposed;

    ArchitectureReportFileEditor(Project project, ArchitectureReportVirtualFile file) {
        this.file = file;
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            sourceQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
            sourceQuery.addHandler(payload -> {
                HistoricalSourceOpener.open(project, file.repositoryRoot(), payload);
                return null;
            });
            refineQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
            refineQuery.addHandler(payload -> {
                startRefinement(project, payload);
                return null;
            });
            browser.getJBCefClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(
                        org.cef.browser.CefBrowser cefBrowser,
                        org.cef.browser.CefFrame frame,
                        int httpStatusCode
                ) {
                    if (!frame.isMain() || sourceQuery == null || refineQuery == null) return;
                    cefBrowser.executeJavaScript(
                            "window.archscopeOpenSource=function(payload){" + sourceQuery.inject("payload") + "};"
                                    + "window.archscopeRefineReport=function(payload){" + refineQuery.inject("payload") + "};",
                            cefBrowser.getURL(),
                            0
                    );
                }
            }, browser.getCefBrowser());
            browser.loadHTML(editorHtml());
            component.add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            JBTextArea fallback = new JBTextArea(file.getContent().toString());
            fallback.setEditable(false);
            fallback.setFont(JBUI.Fonts.create("Monospaced", 12));
            component.add(new JBLabel(PluginText.text("当前 IDE Runtime 不支持 JCEF，已显示报告 HTML 源码。",
                    "This IDE runtime does not support JCEF. Showing the report HTML source.")), BorderLayout.NORTH);
            component.add(new JBScrollPane(fallback), BorderLayout.CENTER);
        }
    }

    private String editorHtml() {
        if (file.supportsRefinement()) return file.reportHtml();
        return file.reportHtml().replace("</head>", "<style>.refine{display:none!important}</style></head>");
    }

    private void startRefinement(Project project, String payload) {
        if (!file.supportsRefinement()) {
            setRefineState("error", PluginText.text("当前报告缺少可继续分析的会话上下文",
                    "This report does not have the session context required for follow-up analysis"));
            return;
        }
        String prompt;
        try {
            JsonObject request = JsonParser.parseString(payload).getAsJsonObject();
            prompt = request.has("prompt") ? request.get("prompt").getAsString().strip() : "";
        } catch (RuntimeException exception) {
            setRefineState("error", PluginText.text("无法解析补充要求", "Could not parse the follow-up request"));
            return;
        }
        if (prompt.isEmpty()) {
            setRefineState("error", PluginText.text("请输入需要补充的内容", "Enter what should be expanded or corrected"));
            return;
        }
        if (!refining.compareAndSet(false, true)) {
            setRefineState("working", PluginText.text("已有补充分析正在运行", "A follow-up analysis is already running"));
            return;
        }
        setRefineState("working", PluginText.text("正在理解补充要求", "Understanding the follow-up request"));
        FunctionAnalysisRunDialog runDialog = new FunctionAnalysisRunDialog(project, file.request().focus());
        runDialog.show();
        new Task.Backgroundable(project, PluginText.text("补充业务理解报告", "Update business logic report"), true) {
            private AnalysisResult result;
            private String archiveWarning;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                runDialog.attach(indicator);
                try {
                    EvidencePack evidence = file.evidence();
                    if (evidence == null && (file.request().isBusinessDomain() || file.request().isFunctionFlow())) {
                        evidence = new GitEvidenceService().collectSnapshot(file.request(), indicator);
                        file.updateEvidence(evidence);
                    }
                    java.util.function.Consumer<String> statusListener = message ->
                            ApplicationManager.getApplication().invokeLater(() -> {
                                setRefineState("working", message);
                                runDialog.updateStatus(message);
                            });
                    var modelClient = ModelClientRegistry.selected(new ModelProviderStore(project).load());
                    result = file.request().isFunctionFlow()
                            ? new FunctionFlowAnalysisService(modelClient).refine(
                                    file.request(), evidence, file.currentResult().reportJson(), prompt, indicator,
                                    statusListener, runDialog::accept)
                            : new ArchitectureAnalysisService(modelClient).refine(
                                    file.request(), evidence, file.currentResult().reportJson(), prompt, indicator,
                                    statusListener, runDialog::accept);
                    try {
                        new ReportArchive().save(file.repositoryRoot(), file.request(), result);
                        project.getMessageBus().syncPublisher(ReportHistoryListener.TOPIC)
                                .reportsChanged(file.repositoryRoot());
                    } catch (java.io.IOException exception) {
                        archiveWarning = exception.getMessage();
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public void onSuccess() {
                refining.set(false);
                runDialog.completed(PluginText.text(
                        "补充分析完成，报告已更新。", "Follow-up analysis completed; the report was updated."));
                file.updateResult(result);
                if (browser != null) browser.loadHTML(result.reportHtml());
                if (archiveWarning != null) {
                    Messages.showWarningDialog(
                            project,
                            PluginText.text("报告已更新，但本地归档失败：", "The report was updated, but local archiving failed: ")
                                    + PluginText.userMessage(archiveWarning),
                            PluginText.text("本地归档失败", "Local archive failed")
                    );
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                refining.set(false);
                Throwable cause = error;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                setRefineState("error", cause.getMessage() == null
                        ? PluginText.text("补充分析失败", "Follow-up analysis failed")
                        : PluginText.userMessage(cause.getMessage()));
                runDialog.failed(cause.getMessage() == null
                        ? PluginText.text("补充分析失败，已保留执行过程。",
                        "Follow-up analysis failed; execution details were preserved.")
                        : PluginText.userMessage(cause.getMessage()));
            }

            @Override
            public void onCancel() {
                refining.set(false);
                setRefineState("error", PluginText.text("已取消补充分析", "Follow-up analysis canceled"));
                runDialog.cancelled();
            }
        }.queue();
    }

    private void setRefineState(String state, String message) {
        if (browser == null) return;
        message = PluginText.userMessage(message);
        String script = "window.archscopeSetRefineState&&window.archscopeSetRefineState("
                + GSON.toJson(state) + "," + GSON.toJson(message) + ");";
        browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0);
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return browser == null ? component : browser.getComponent();
    }

    @Override
    public @NotNull String getName() {
        return "Architecture Report";
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return !disposed && file.isValid();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChanges.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChanges.removePropertyChangeListener(listener);
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        disposed = true;
        if (sourceQuery != null) {
            sourceQuery.dispose();
            sourceQuery = null;
        }
        if (refineQuery != null) {
            refineQuery.dispose();
            refineQuery = null;
        }
        if (browser != null) {
            browser.dispose();
            browser = null;
        }
    }
}
