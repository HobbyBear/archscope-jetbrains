package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.analysis.ArchitectureAnalysisService;
import com.archscope.jetbrains.analysis.ModelClientRegistry;
import com.archscope.jetbrains.analysis.ReportArchive;
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
            browser.loadHTML(file.reportHtml());
            component.add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            JBTextArea fallback = new JBTextArea(file.getContent().toString());
            fallback.setEditable(false);
            fallback.setFont(JBUI.Fonts.create("Monospaced", 12));
            component.add(new JBLabel("当前 IDE Runtime 不支持 JCEF，已显示报告 HTML 源码。"), BorderLayout.NORTH);
            component.add(new JBScrollPane(fallback), BorderLayout.CENTER);
        }
    }

    private void startRefinement(Project project, String payload) {
        if (!file.supportsRefinement()) {
            setRefineState("error", "当前报告缺少可继续分析的会话上下文");
            return;
        }
        String prompt;
        try {
            JsonObject request = JsonParser.parseString(payload).getAsJsonObject();
            prompt = request.has("prompt") ? request.get("prompt").getAsString().strip() : "";
        } catch (RuntimeException exception) {
            setRefineState("error", "无法解析补充要求");
            return;
        }
        if (prompt.isEmpty()) {
            setRefineState("error", "请输入需要补充的内容");
            return;
        }
        if (!refining.compareAndSet(false, true)) {
            setRefineState("working", "已有补充分析正在运行");
            return;
        }
        setRefineState("working", "正在理解补充要求");
        new Task.Backgroundable(project, "补充业务理解报告", true) {
            private AnalysisResult result;
            private String archiveWarning;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = new ArchitectureAnalysisService(
                            ModelClientRegistry.selected(new ModelProviderStore(project).load())
                    ).refine(
                            file.request(),
                            file.evidence(),
                            file.currentResult().reportJson(),
                            prompt,
                            indicator,
                            message -> ApplicationManager.getApplication().invokeLater(
                                    () -> setRefineState("working", message)
                            )
                    );
                    try {
                        new ReportArchive().save(file.repositoryRoot(), file.request(), result);
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
                file.updateResult(result);
                if (browser != null) browser.loadHTML(result.reportHtml());
                if (archiveWarning != null) {
                    Messages.showWarningDialog(
                            project,
                            "报告已更新，但本地归档失败：" + archiveWarning,
                            "本地归档失败"
                    );
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                refining.set(false);
                Throwable cause = error;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                setRefineState("error", cause.getMessage() == null ? "补充分析失败" : cause.getMessage());
            }

            @Override
            public void onCancel() {
                refining.set(false);
                setRefineState("error", "已取消补充分析");
            }
        }.queue();
    }

    private void setRefineState(String state, String message) {
        if (browser == null) return;
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
