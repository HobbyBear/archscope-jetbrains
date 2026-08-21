package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.analysis.ModelStreamEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.Action;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

final class FunctionAnalysisRunDialog extends DialogWrapper {
    private static final int MAX_TEXT_LENGTH = 800_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JBLabel targetLabel;
    private final Project project;
    private final String target;
    private final JBLabel statusLabel = new JBLabel();
    private final JBTextArea process = textArea();
    private final JBTextArea output = textArea();
    private final Instant startedAt = Instant.now();
    private final Timer elapsedTimer;
    private Action collapseAction;
    private volatile ProgressIndicator indicator;
    private volatile boolean cancelRequested;
    private volatile boolean running = true;
    private ModelStreamEvent.Kind lastKind;
    private volatile String currentStatus;

    FunctionAnalysisRunDialog(Project project, String target) {
        super(project, false);
        this.project = project;
        this.target = target;
        setTitle(PluginText.text("分析实时过程", "Live analysis"));
        setOKButtonText(PluginText.text("关闭", "Close"));
        setCancelButtonText(PluginText.text("取消分析", "Cancel analysis"));
        targetLabel = new JBLabel("<html><b>" + escape(target) + "</b></html>");
        currentStatus = PluginText.text("正在准备分析", "Preparing analysis");
        statusLabel.setText(currentStatus);
        elapsedTimer = new Timer(1000, ignored -> refreshStatus());
        setModal(false);
        init();
        getOKAction().setEnabled(false);
        elapsedTimer.start();
        appendProcess(PluginText.text("分析任务已创建，等待 CLI 启动。", "Analysis created; waiting for the CLI to start."));
        FunctionAnalysisRunRegistry.register(project, this);
    }

    void attach(ProgressIndicator value) {
        indicator = value;
        if (cancelRequested) value.cancel();
    }

    void updateStatus(String status) {
        onUiThread(() -> {
            currentStatus = PluginText.userMessage(status);
            refreshStatus();
        });
    }

    void accept(ModelStreamEvent event) {
        onUiThread(() -> appendEvent(event));
    }

    void completed(String message) {
        finish(message, false);
    }

    void failed(String message) {
        finish(message, true);
    }

    void cancelled() {
        finish(PluginText.text("分析已取消，以上过程已保留。", "Analysis canceled; the process above was preserved."), true);
    }

    boolean isRunning() {
        return running;
    }

    String taskName() {
        return target;
    }

    String taskStatus() {
        long seconds = Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
        return currentStatus + " · " + formatDuration(seconds);
    }

    void showAgain() {
        onUiThread(() -> {
            if (isDisposed()) return;
            getWindow().setVisible(true);
            getWindow().toFront();
        });
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.add(targetLabel, BorderLayout.NORTH);
        header.add(statusLabel, BorderLayout.SOUTH);

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab(PluginText.text("执行过程", "Execution"), new JBScrollPane(process));
        tabs.addTab(PluginText.text("模型输出", "Model output"), new JBScrollPane(output));

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(JBUI.Borders.empty(10));
        panel.add(header, BorderLayout.NORTH);
        panel.add(tabs, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(880, 560));
        return panel;
    }

    @Override
    protected Action[] createActions() {
        collapseAction = new DialogWrapperAction(PluginText.text("收起到后台", "Run in background")) {
            @Override
            protected void doAction(ActionEvent event) {
                getWindow().setVisible(false);
                com.intellij.openapi.wm.StatusBar.Info.set(
                        PluginText.text("分析正在后台运行，可从 CodeBecause 底部或 Tools 菜单重新打开实时过程。",
                                "Analysis is running in the background. Reopen it from CodeBecause or the Tools menu."),
                        project
                );
            }
        };
        return new Action[]{collapseAction, getOKAction(), getCancelAction()};
    }

    @Override
    public void doCancelAction() {
        if (!running) {
            super.doCancelAction();
            return;
        }
        ProgressIndicator active = indicator;
        cancelRequested = true;
        if (active == null) {
            running = false;
            elapsedTimer.stop();
            super.doCancelAction();
            return;
        }
        active.cancel();
        currentStatus = PluginText.text("正在取消 Claude/Codex CLI", "Canceling Claude/Codex CLI");
        refreshStatus();
        getCancelAction().setEnabled(false);
        appendProcess(PluginText.text("已请求取消，正在终止 CLI 进程。", "Cancellation requested; terminating the CLI process."));
    }

    @Override
    protected void dispose() {
        elapsedTimer.stop();
        FunctionAnalysisRunRegistry.unregister(project, this);
        super.dispose();
    }

    private void appendEvent(ModelStreamEvent event) {
        switch (event.kind()) {
            case STATUS -> {
                updateStatus(event.content());
                appendProcess(event.content());
            }
            case REASONING -> appendContinuous(ModelStreamEvent.Kind.REASONING,
                    PluginText.text("可见推理", "Visible reasoning"), event.content());
            case TOOL_CALL -> appendSection(PluginText.text("工具调用", "Tool call")
                    + (event.title().isBlank() ? "" : " · " + event.title()), event.content());
            case TOOL_RESULT -> appendSection(PluginText.text("工具返回", "Tool result")
                    + (event.title().isBlank() ? "" : " · " + event.title()), event.content());
            case RESPONSE -> appendOutput(event.content());
            case ERROR -> appendSection(PluginText.text("模型错误", "Model error"), event.content());
        }
    }

    private void appendContinuous(ModelStreamEvent.Kind kind, String label, String text) {
        if (text.isEmpty()) return;
        if (lastKind != kind) appendRaw(process, "\n[" + timestamp() + "] " + label + "\n");
        appendRaw(process, text);
        lastKind = kind;
    }

    private void appendSection(String label, String text) {
        appendRaw(process, "\n[" + timestamp() + "] " + label + "\n");
        if (!text.isBlank()) appendRaw(process, text.strip() + "\n");
        lastKind = null;
    }

    private void appendProcess(String text) {
        if (text == null || text.isBlank()) return;
        appendRaw(process, "\n[" + timestamp() + "] " + text.strip() + "\n");
        lastKind = null;
    }

    private void appendOutput(String text) {
        if (text == null || text.isEmpty()) return;
        appendRaw(output, text);
    }

    private void finish(String message, boolean error) {
        onUiThread(() -> {
            if (!running) return;
            running = false;
            FunctionAnalysisRunRegistry.unregister(project, this);
            elapsedTimer.stop();
            currentStatus = PluginText.userMessage(message);
            refreshStatus();
            if (error) appendSection(PluginText.text("任务结束", "Run finished"), currentStatus);
            else appendProcess(currentStatus);
            getOKAction().setEnabled(true);
            getCancelAction().setEnabled(false);
            collapseAction.setEnabled(false);
        });
    }

    private void refreshStatus() {
        long seconds = Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
        statusLabel.setText(currentStatus + " · " + formatDuration(seconds));
    }

    private static String formatDuration(long seconds) {
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private static String timestamp() {
        return LocalTime.now().format(CLOCK);
    }

    private static JBTextArea textArea() {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(JBUI.Fonts.create("Monospaced", 12));
        ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        return area;
    }

    private static void appendRaw(JBTextArea area, String text) {
        area.append(text);
        int overflow = area.getDocument().getLength() - MAX_TEXT_LENGTH;
        if (overflow <= 0) return;
        try {
            area.getDocument().remove(0, overflow);
        } catch (BadLocationException ignored) {
            // A concurrent UI update can make the trim unnecessary; later appends will retry.
        }
    }

    private static void onUiThread(Runnable action) {
        if (ApplicationManager.getApplication().isDispatchThread()) action.run();
        else ApplicationManager.getApplication().invokeLater(action);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
