package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.i18n.PluginLanguage;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PluginText {
    private static final Map<String, Pair> TRANSLATIONS = new ConcurrentHashMap<>();

    private PluginText() {
    }

    static boolean isChinese() {
        loadStoredLanguage();
        return !PluginLanguage.isEnglish();
    }

    static String text(String chinese, String english) {
        loadStoredLanguage();
        Pair pair = new Pair(chinese, english);
        TRANSLATIONS.put(chinese, pair);
        TRANSLATIONS.put(english, pair);
        return PluginLanguage.text(chinese, english);
    }

    static String userMessage(String message) {
        loadStoredLanguage();
        return PluginLanguage.userMessage(message);
    }

    static void refresh(Component component) {
        if (component instanceof JLabel label) label.setText(translateKnown(label.getText()));
        if (component instanceof AbstractButton button) button.setText(translateKnown(button.getText()));
        if (component instanceof JComponent swing) {
            swing.setToolTipText(translateKnown(swing.getToolTipText()));
            if (swing.getBorder() instanceof TitledBorder border) border.setTitle(translateKnown(border.getTitle()));
        }
        if (component instanceof JTabbedPane tabs) {
            for (int index = 0; index < tabs.getTabCount(); index++) {
                tabs.setTitleAt(index, translateKnown(tabs.getTitleAt(index)));
                tabs.setToolTipTextAt(index, translateKnown(tabs.getToolTipTextAt(index)));
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) refresh(child);
        }
    }

    private static String translateKnown(String value) {
        if (value == null || value.isEmpty()) return value;
        Pair pair = TRANSLATIONS.get(value);
        if (pair == null) return value;
        return PluginLanguage.text(pair.chinese(), pair.english());
    }

    private static void loadStoredLanguage() {
        if (ApplicationManager.getApplication() == null) return;
        String stored = PropertiesComponent.getInstance().getValue("archscope.output.language");
        if (stored != null) {
            PluginLanguage.use("en".equals(stored)
                    ? AnalysisRequest.OutputLanguage.ENGLISH
                    : AnalysisRequest.OutputLanguage.CHINESE);
        }
    }

    private record Pair(String chinese, String english) {
    }
}
