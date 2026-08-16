package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.i18n.PluginLanguage;
import com.archscope.jetbrains.model.AnalysisRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginTextTest {
    @AfterEach
    void clearLanguageOverride() {
        PluginLanguage.use(AnalysisRequest.OutputLanguage.CHINESE);
    }

    @Test
    void switchingToEnglishRetranslatesTheCurrentPluginWindow() {
        PluginLanguage.use(AnalysisRequest.OutputLanguage.CHINESE);
        JPanel root = new JPanel();
        root.add(new JLabel(PluginText.text("插件语言", "Plugin language")));
        root.add(new JButton(PluginText.text("分析当前项目", "Analyze current project")));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(PluginText.text("新建分析", "New analysis"), new JPanel());
        root.add(tabs);

        PluginLanguage.use(AnalysisRequest.OutputLanguage.ENGLISH);
        PluginText.refresh(root);

        List<String> visibleText = visibleText(root);
        assertTrue(visibleText.contains("Plugin language"));
        assertTrue(visibleText.contains("Analyze current project"));
        assertTrue(visibleText.contains("New analysis"));
        assertFalse(String.join("\n", visibleText).matches(".*[\\p{IsHan}].*"));
    }

    private static List<String> visibleText(Component component) {
        List<String> values = new ArrayList<>();
        if (component instanceof JLabel label) values.add(label.getText());
        if (component instanceof JButton button) values.add(button.getText());
        if (component instanceof JTabbedPane tabs) {
            for (int index = 0; index < tabs.getTabCount(); index++) values.add(tabs.getTitleAt(index));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) values.addAll(visibleText(child));
        }
        return values;
    }
}
