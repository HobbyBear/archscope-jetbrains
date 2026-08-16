package com.archscope.jetbrains.i18n;

import com.archscope.jetbrains.model.AnalysisRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class PluginLanguageTest {
    @AfterEach
    void restoreChineseDefaultForExistingTests() {
        PluginLanguage.use(AnalysisRequest.OutputLanguage.CHINESE);
    }

    @Test
    void englishSelectionControlsPluginTextAndRemovesChineseRuntimeFailures() {
        PluginLanguage.use(AnalysisRequest.OutputLanguage.ENGLISH);

        assertEquals("Plugin language", PluginLanguage.text("插件语言", "Plugin language"));
        assertEquals("Operation failed: fatal: repository not found",
                PluginLanguage.userMessage("操作失败：fatal: repository not found"));
        assertFalse(PluginLanguage.userMessage("模型没有返回合法 JSON：解析失败")
                .matches(".*[\\p{IsHan}].*"));
    }

    @Test
    void chineseSelectionKeepsChinesePluginText() {
        PluginLanguage.use(AnalysisRequest.OutputLanguage.CHINESE);

        assertEquals("插件语言", PluginLanguage.text("插件语言", "Plugin language"));
    }
}
