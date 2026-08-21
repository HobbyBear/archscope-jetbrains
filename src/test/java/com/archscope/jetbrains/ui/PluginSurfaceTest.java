package com.archscope.jetbrains.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginSurfaceTest {
    @Test
    void exposesOnlyBusinessAnalysisAndOneSystemPromptSurface() throws Exception {
        String pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"));
        String panel = Files.readString(Path.of(
                "src/main/java/com/archscope/jetbrains/ui/ArchitectureToolWindowPanel.java"));
        String functionRunDialog = Files.readString(Path.of(
                "src/main/java/com/archscope/jetbrains/ui/FunctionAnalysisRunDialog.java"));

        assertFalse(pluginXml.contains("Vcs.Log.ContextMenu"));
        assertFalse(pluginXml.contains("AnalyzeGitLogSelection"));
        assertTrue(pluginXml.contains("CodeBecause.AnalyzeFunctionFlow"));
        assertTrue(pluginXml.contains("EditorPopupMenu"));
        assertTrue(pluginXml.contains("FunctionFlowLineMarkerProvider"));
        assertTrue(pluginXml.contains("CodeBecause.ShowFunctionAnalysisRun"));
        assertFalse(panel.contains("自定义提示词"));
        assertFalse(panel.contains("附加系统提示词"));
        assertTrue(panel.contains("navigation.addTab(\"System Prompt\""));
        assertTrue(panel.contains("workingDirectoryPanel.add(cliWorkingDirectory"));
        assertTrue(panel.contains("搜索函数名、文件路径或报告标题"));
        assertTrue(panel.contains("functionHistory.isSelected()"));
        assertTrue(panel.contains("matchesHistory(entry, query)"));
        assertTrue(panel.contains("ListSelectionModel.MULTIPLE_INTERVAL_SELECTION"));
        assertTrue(panel.contains("toggleAllVisibleHistory()"));
        assertTrue(panel.contains("reportArchive.deleteAll(selected)"));
        assertTrue(panel.contains("删除所选 ("));
        assertTrue(panel.contains("查看实时过程"));
        assertTrue(panel.contains("FunctionAnalysisRunRegistry.showActive(project, showLiveAnalysis)"));
        assertTrue(panel.contains("runDialog::accept"));
        assertTrue(panel.contains("activeOperations++"));
        assertTrue(panel.contains("查看任务 ("));
        assertTrue(panel.contains("openCompletedReport(request, evidence, result)"));
        assertFalse(panel.contains("analyze.setEnabled(!busy"));
        assertFalse(panel.substring(panel.indexOf("private JComponent buildModelPanel()"),
                panel.indexOf("private int addPromptField"))
                .contains("cliWorkingDirectory"));
        assertTrue(functionRunDialog.contains("setModal(false)"));
        assertTrue(functionRunDialog.indexOf("setModal(false)") < functionRunDialog.indexOf("init();"));
        assertTrue(functionRunDialog.contains("if (cancelRequested) value.cancel()"));
        assertTrue(functionRunDialog.contains("收起到后台"));
        assertTrue(functionRunDialog.contains("CodeBecause 底部或 Tools 菜单"));
        assertTrue(functionRunDialog.contains("getWindow().setVisible(false)"));
        assertTrue(functionRunDialog.contains("FunctionAnalysisRunRegistry.register"));
        String runRegistry = Files.readString(Path.of(
                "src/main/java/com/archscope/jetbrains/ui/FunctionAnalysisRunRegistry.java"));
        assertTrue(runRegistry.contains("createPopupChooserBuilder(dialogs)"));
        assertTrue(runRegistry.contains("value.taskStatus()"));
    }
}
