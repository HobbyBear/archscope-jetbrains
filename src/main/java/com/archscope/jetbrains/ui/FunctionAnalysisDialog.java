package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.FunctionTarget;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

final class FunctionAnalysisDialog extends DialogWrapper {
    private final FunctionTarget target;
    private final JBTextArea prompt = new JBTextArea(5, 52);

    FunctionAnalysisDialog(Project project, FunctionTarget target) {
        super(project, true);
        this.target = target;
        setTitle(PluginText.text("分析函数业务流程", "Analyze function flow"));
        setOKButtonText(PluginText.text("开始分析", "Analyze"));
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.getEmptyText().setText(PluginText.text(
                "可选：指定重点分支、数据变化、异常路径或需要忽略的调用",
                "Optional: focus branches, data changes, failures, or calls to ignore"));
        init();
    }

    String additionalPrompt() {
        return prompt.getText().strip();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.add(new JBLabel("<html><b>" + escape(target.symbol()) + "</b><br>" + escape(target.relativeFile())
                + ":" + target.startLine() + "</html>"), BorderLayout.NORTH);
        panel.add(new JBScrollPane(prompt), BorderLayout.CENTER);
        return panel;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
