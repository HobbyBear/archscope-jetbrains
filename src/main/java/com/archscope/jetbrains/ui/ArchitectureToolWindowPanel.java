package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.analysis.ArchitectureAnalysisService;
import com.archscope.jetbrains.analysis.ModelClientRegistry;
import com.archscope.jetbrains.analysis.ReportArchive;
import com.archscope.jetbrains.git.GitEvidenceService;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.AnalysisGuidance;
import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class ArchitectureToolWindowPanel implements Disposable {
    private static final Key<ArchitectureToolWindowPanel> PANEL_KEY =
            Key.create("ai.code.review.tool.window.panel");
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final DefaultListModel<String> selectedCommitModel = new DefaultListModel<>();
    private final JBList<String> selectedCommitList = new JBList<>(selectedCommitModel);
    private final JBLabel selectionTitle = new JBLabel(PluginText.text("尚未选择提交", "No commits selected"));
    private final JBLabel targetSnapshot = new JBLabel("");
    private final JBTextArea focus = new JBTextArea(4, 60);
    private final JPanel selectionPanel = new JPanel(new BorderLayout());
    private final JBLabel status = new JBLabel(PluginText.text("等待 Git 日志选择", "Waiting for a Git log selection"));
    private final JProgressBar progress = new JProgressBar();
    private final JButton analyze = new JButton(PluginText.text("重新分析当前选择", "Analyze current selection"));
    private final JButton openReport = new JButton(PluginText.text("全局查看", "Open report"));
    private final JButton exportHtml = new JButton(PluginText.text("导出 HTML", "Export HTML"));
    private final JButton exportJson = new JButton(PluginText.text("导出 JSON", "Export JSON"));
    private final JToggleButton businessMode = new JToggleButton(PluginText.text("业务理解", "Business logic"));
    private final JToggleButton changeMode = new JToggleButton(PluginText.text("提交改动", "Commit changes"));
    private final GitEvidenceService evidenceService = new GitEvidenceService();
    private final ReportArchive reportArchive = new ReportArchive();
    private final DefaultListModel<ReportArchive.Entry> historyModel = new DefaultListModel<>();
    private final JBList<ReportArchive.Entry> historyList = new JBList<>(historyModel);
    private final JBLabel historyStatus = new JBLabel(PluginText.text("正在读取本地报告...", "Loading local reports..."));
    private final JButton openHistory = new JButton(PluginText.text("打开报告", "Open report"));
    private final JButton deleteHistory = new JButton(PluginText.text("删除", "Delete"));
    private final JButton refreshHistory = new JButton(PluginText.text("刷新", "Refresh"));
    private final JBTabbedPane navigation = new JBTabbedPane();
    private final JBTextArea customPrompt = new JBTextArea(8, 60);
    private final JBTextArea systemPrompt = new JBTextArea(5, 60);
    private final JToggleButton advancedPromptSettings = new JToggleButton(PluginText.text("高级设置", "Advanced settings"));
    private final JButton savePrompts = new JButton(PluginText.text("保存自定义提示词", "Save custom instructions"));
    private final JToggleButton codexProvider = new JToggleButton(PluginText.text("本机 Codex", "Local Codex"));
    private final JToggleButton claudeProvider = new JToggleButton("Claude CLI");
    private final JButton saveModelProvider = new JButton(PluginText.text("保存模型配置", "Save model settings"));
    private final JComboBox<String> outputLanguage = new JComboBox<>(new String[]{
            PluginText.text("中文（简体）", "Chinese (Simplified)"), "English"
    });
    private final JBLabel modelProviderStatus = new JBLabel();
    private final ProjectAnalysisGuidanceStore guidanceStore;
    private final ModelProviderStore modelProviderStore;
    private final OutputLanguageStore outputLanguageStore;

    private Path repositoryRoot;
    private List<CommitInfo> selectedCommits = List.of();
    private String targetCommit;
    private AnalysisResult lastResult;
    private ArchitectureReportVirtualFile reportFile;
    private AnalysisRequest.Mode analysisMode = AnalysisRequest.Mode.BUSINESS_DOMAIN;
    private AnalysisRequest lastRequest;
    private EvidencePack lastEvidence;
    private boolean busy;
    private boolean historyLoading;
    private long historyReloadGeneration;

    ArchitectureToolWindowPanel(Project project) {
        this.project = project;
        this.guidanceStore = new ProjectAnalysisGuidanceStore(project);
        this.modelProviderStore = new ModelProviderStore(project);
        this.outputLanguageStore = new OutputLanguageStore(project);
        this.outputLanguageStore.load();
        project.putUserData(PANEL_KEY, this);
        if (project.getBasePath() != null) repositoryRoot = Path.of(project.getBasePath());
        buildUi();
        loadPromptInputs();
        renderSelection();
        reloadReportHistory(false);
    }

    static void analyzeGitLogSelection(
            Project project,
            Path repositoryRoot,
            List<CommitInfo> commits,
            String targetCommit
    ) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("CodeBecause");
        if (toolWindow == null) {
            Messages.showErrorDialog(project, PluginText.text("无法打开 AI Code Review 工具窗口。",
                    "Could not open the CodeBecause tool window."), "CodeBecause");
            return;
        }
        toolWindow.show(() -> runWhenPanelReady(project, repositoryRoot, commits, targetCommit, 0));
    }

    private static void runWhenPanelReady(
            Project project,
            Path repositoryRoot,
            List<CommitInfo> commits,
            String targetCommit,
            int attempt
    ) {
        ArchitectureToolWindowPanel panel = project.getUserData(PANEL_KEY);
        if (panel != null) {
            panel.startSelection(repositoryRoot, commits, targetCommit);
            return;
        }
        if (attempt >= 2) {
            Messages.showErrorDialog(project, PluginText.text("AI Code Review 工具窗口尚未初始化。",
                    "The CodeBecause tool window is not initialized yet."), "CodeBecause");
            return;
        }
        ApplicationManager.getApplication().invokeLater(
                () -> runWhenPanelReady(project, repositoryRoot, commits, targetCommit, attempt + 1)
        );
    }

    JComponent component() {
        return root;
    }

    private void buildUi() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(JBUI.Borders.empty(8, 10));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(openReport);
        actions.add(exportJson);
        actions.add(exportHtml);
        header.add(actions, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        JPanel selectionHeader = new JPanel(new BorderLayout(8, 0));
        selectionHeader.setBorder(JBUI.Borders.emptyBottom(6));
        selectionTitle.setFont(selectionTitle.getFont().deriveFont(Font.BOLD));
        selectionHeader.add(selectionTitle, BorderLayout.WEST);
        selectionHeader.add(targetSnapshot, BorderLayout.EAST);
        selectedCommitList.setFocusable(false);
        selectedCommitList.setVisibleRowCount(8);

        selectionPanel.setBorder(JBUI.Borders.empty(8, 10));
        selectionPanel.add(selectionHeader, BorderLayout.NORTH);
        selectionPanel.add(new JBScrollPane(selectedCommitList), BorderLayout.CENTER);

        focus.setLineWrap(true);
        focus.setWrapStyleWord(true);
        focus.setText("");
        JPanel focusPanel = new JPanel(new BorderLayout(0, 5));
        focusPanel.setBorder(JBUI.Borders.empty(8, 10));
        JPanel focusHeader = new JPanel(new BorderLayout(8, 0));
        focusHeader.add(new JBLabel(PluginText.text("分析主题", "Analysis topic")), BorderLayout.WEST);
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(businessMode);
        modeGroup.add(changeMode);
        businessMode.setSelected(true);
        changeMode.setEnabled(false);
        modes.add(businessMode);
        modes.add(changeMode);
        focusHeader.add(modes, BorderLayout.EAST);
        focusPanel.add(focusHeader, BorderLayout.NORTH);
        focusPanel.add(new JBScrollPane(focus), BorderLayout.CENTER);
        focusPanel.add(new JBLabel(PluginText.text(
                "例如：分析聊天逻辑；分析创作者从创建、审核到发布的完整流程。",
                "For example: analyze chat logic, or the complete creator workflow from creation through review to publication."
        )), BorderLayout.SOUTH);

        JPanel analysisBody = new JPanel(new BorderLayout());
        analysisBody.add(selectionPanel, BorderLayout.NORTH);
        analysisBody.add(focusPanel, BorderLayout.CENTER);

        JPanel runBar = new JPanel(new BorderLayout(8, 0));
        runBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1,
                        0,
                        0,
                        0,
                        JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
                ),
                JBUI.Borders.empty(8, 10)
        ));
        progress.setIndeterminate(false);
        progress.setVisible(false);
        JPanel runStatus = new JPanel(new BorderLayout(8, 0));
        runStatus.add(status, BorderLayout.CENTER);
        runStatus.add(progress, BorderLayout.EAST);
        runBar.add(runStatus, BorderLayout.CENTER);
        runBar.add(analyze, BorderLayout.EAST);

        JPanel analysisContent = new JPanel(new BorderLayout());
        analysisContent.add(analysisBody, BorderLayout.CENTER);
        analysisContent.add(runBar, BorderLayout.SOUTH);
        navigation.addTab(PluginText.text("历史报告", "Report history"), buildHistoryPanel());
        navigation.addTab(PluginText.text("新建分析", "New analysis"), analysisContent);
        navigation.addTab(PluginText.text("自定义提示词", "Custom instructions"), buildPromptPanel());
        navigation.addTab(PluginText.text("模型配置", "Model settings"), buildModelPanel());
        navigation.setSelectedIndex(0);
        root.add(navigation, BorderLayout.CENTER);

        analyze.addActionListener(event -> collectEvidence());
        openReport.addActionListener(event -> openReportInEditor(false));
        exportHtml.addActionListener(event -> export("html"));
        exportJson.addActionListener(event -> export("json"));
        businessMode.addActionListener(event -> switchMode(AnalysisRequest.Mode.BUSINESS_DOMAIN));
        changeMode.addActionListener(event -> switchMode(AnalysisRequest.Mode.SELECTED_CHANGES));
        openHistory.addActionListener(event -> openSelectedArchivedReport());
        deleteHistory.addActionListener(event -> deleteSelectedArchivedReport());
        refreshHistory.addActionListener(event -> reloadReportHistory(true));
        savePrompts.addActionListener(event -> savePromptInputs());
        saveModelProvider.addActionListener(event -> saveModelProvider());
        focus.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateActions();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateActions();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateActions();
            }
        });
        updateActions();
        openReport.setEnabled(false);
        exportHtml.setEnabled(false);
        exportJson.setEnabled(false);
    }

    private JComponent buildHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(10));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        JBLabel title = new JBLabel(PluginText.text("本项目的历史分析", "Analysis history for this project"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        header.add(title, BorderLayout.WEST);
        header.add(refreshHistory, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setVisibleRowCount(12);
        historyList.getEmptyText().setText(PluginText.text("当前项目还没有本地历史报告", "This project has no local reports yet"));
        historyList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(
                    @NotNull javax.swing.JList<? extends ReportArchive.Entry> list,
                    ReportArchive.Entry entry,
                    int index,
                    boolean selected,
                    boolean hasFocus
            ) {
                String focusText = entry.focus().isBlank() ? PluginText.text("未命名分析", "Untitled analysis") : entry.focus();
                append(focusText, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + HISTORY_TIME.format(entry.createdAt())
                                + " · " + modeName(entry.mode())
                                + " · " + formatElapsed(entry.elapsedMs())
                                + " · " + shortHash(entry.targetCommit()),
                        SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                setBorder(JBUI.Borders.empty(7, 5));
            }
        });
        historyList.addListSelectionListener(event -> updateHistoryActions());
        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) openSelectedArchivedReport();
            }
        });
        historyList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER) openSelectedArchivedReport();
            }
        });
        panel.add(new JBScrollPane(historyList), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(historyStatus, BorderLayout.CENTER);
        JPanel historyActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        historyActions.add(deleteHistory);
        historyActions.add(openHistory);
        footer.add(historyActions, BorderLayout.EAST);
        openHistory.setEnabled(false);
        deleteHistory.setEnabled(false);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildPromptPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(JBUI.Borders.empty(10));
        int row = 0;
        row = addPromptField(form, row, PluginText.text("自定义提示词", "Custom instructions"), customPrompt,
                PluginText.text("例如：创作者数据由 OMS 导入；沿入口、数据来源、状态变化、落库和消费者展开。",
                        "For example: creator data is imported from OMS; follow its entry, origin, state changes, persistence, and consumers."));

        JPanel advancedPanel = new JPanel(new BorderLayout(0, 4));
        advancedPanel.add(new JBLabel(PluginText.text("附加系统提示词", "Additional system instructions")), BorderLayout.NORTH);
        systemPrompt.setLineWrap(true);
        systemPrompt.setWrapStyleWord(true);
        systemPrompt.getEmptyText().setText(PluginText.text("仅用于需要影响所有分析阶段的高级约束。",
                "Use only for advanced constraints that must apply to every analysis stage."));
        advancedPanel.add(new JBScrollPane(systemPrompt), BorderLayout.CENTER);
        advancedPanel.setVisible(false);

        JPanel advancedActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        advancedActions.add(advancedPromptSettings);
        GridBagConstraints advancedAction = constraints(row++, 0, 2, 1.0, 0.0);
        advancedAction.insets = new Insets(10, 0, 0, 0);
        form.add(advancedActions, advancedAction);
        GridBagConstraints advanced = constraints(row++, 0, 2, 1.0, 0.35);
        advanced.insets = new Insets(6, 0, 0, 0);
        advanced.fill = GridBagConstraints.BOTH;
        form.add(advancedPanel, advanced);
        advancedPromptSettings.addActionListener(event -> {
            boolean visible = advancedPromptSettings.isSelected();
            advancedPromptSettings.setText(visible
                    ? PluginText.text("收起高级设置", "Hide advanced settings")
                    : PluginText.text("高级设置", "Advanced settings"));
            advancedPanel.setVisible(visible);
            form.revalidate();
            form.repaint();
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(savePrompts);
        GridBagConstraints action = constraints(row++, 0, 2, 1.0, 0.0);
        action.insets = new Insets(12, 0, 0, 0);
        form.add(actions, action);
        GridBagConstraints spacer = constraints(row, 0, 2, 1.0, 1.0);
        form.add(new JPanel(), spacer);
        return new JBScrollPane(form);
    }

    private JComponent buildModelPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(JBUI.Borders.empty(10));
        JPanel body = new JPanel(new GridBagLayout());
        GridBagConstraints title = constraints(0, 0, 2, 1.0, 0.0);
        body.add(new JBLabel(PluginText.text("模型提供方", "Model provider")), title);
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        ButtonGroup providers = new ButtonGroup();
        providers.add(codexProvider);
        providers.add(claudeProvider);
        choices.add(codexProvider);
        choices.add(claudeProvider);
        GridBagConstraints choice = constraints(1, 0, 2, 1.0, 0.0);
        choice.insets = new Insets(6, 0, 0, 0);
        body.add(choices, choice);
        GridBagConstraints providerStatus = constraints(2, 0, 2, 1.0, 0.0);
        providerStatus.insets = new Insets(8, 0, 0, 0);
        body.add(modelProviderStatus, providerStatus);
        GridBagConstraints languageLabel = constraints(3, 0, 1, 0.0, 0.0);
        languageLabel.insets = new Insets(12, 0, 0, 0);
        body.add(new JBLabel(PluginText.text("插件语言", "Plugin language")), languageLabel);
        GridBagConstraints languageChoice = constraints(3, 1, 1, 1.0, 0.0);
        languageChoice.insets = new Insets(8, 8, 0, 0);
        body.add(outputLanguage, languageChoice);
        GridBagConstraints requirement = constraints(4, 0, 2, 1.0, 0.0);
        requirement.insets = new Insets(5, 0, 0, 0);
        body.add(new JBLabel(PluginText.text("使用前请确认对应 CLI 已安装并完成登录；语言对整个插件生效。",
                "Make sure the selected CLI is installed and signed in. The language applies to the entire plugin.")), requirement);
        GridBagConstraints spacer = constraints(5, 0, 2, 1.0, 1.0);
        body.add(new JPanel(), spacer);
        panel.add(body, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(saveModelProvider);
        panel.add(actions, BorderLayout.SOUTH);
        loadModelProvider();
        return panel;
    }

    private int addPromptField(JPanel form, int row, String title, JBTextArea input, String placeholder) {
        GridBagConstraints label = constraints(row++, 0, 2, 0.0, 0.0);
        label.insets = new Insets(row == 1 ? 0 : 10, 0, 4, 0);
        form.add(new JBLabel(title), label);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.getEmptyText().setText(placeholder);
        GridBagConstraints field = constraints(row++, 0, 2, 1.0, 0.0);
        field.fill = GridBagConstraints.BOTH;
        field.weighty = 0.2;
        form.add(new JBScrollPane(input), field);
        return row;
    }

    private GridBagConstraints constraints(int row, int column, int width, double weightX, double weightY) {
        GridBagConstraints value = new GridBagConstraints();
        value.gridx = column;
        value.gridy = row;
        value.gridwidth = width;
        value.weightx = weightX;
        value.weighty = weightY;
        value.fill = GridBagConstraints.HORIZONTAL;
        value.anchor = GridBagConstraints.NORTHWEST;
        return value;
    }

    private void startSelection(Path rootPath, List<CommitInfo> commits, String targetHash) {
        if (busy) {
            Messages.showWarningDialog(project,
                    PluginText.text("已有一项提交分析正在运行，请等待当前分析完成。",
                            "An analysis is already running. Wait for it to finish."),
                    PluginText.text("分析正在运行", "Analysis in progress"));
            return;
        }
        repositoryRoot = rootPath;
        selectedCommits = List.copyOf(commits);
        targetCommit = targetHash;
        analysisMode = AnalysisRequest.Mode.SELECTED_CHANGES;
        changeMode.setEnabled(true);
        changeMode.setSelected(true);
        if (focus.getText().isBlank()) focus.setText(defaultFocus());
        navigation.setSelectedIndex(1);
        reloadReportHistory(false);
        renderSelection();
        collectEvidence();
    }

    private void renderSelection() {
        selectedCommitModel.clear();
        if (analysisMode == AnalysisRequest.Mode.BUSINESS_DOMAIN) {
            selectedCommitList.setVisibleRowCount(1);
            selectedCommitModel.addElement(repositoryRoot == null
                    ? PluginText.text("未找到当前项目路径", "Project path not found") : repositoryRoot.toString());
        } else {
            selectedCommitList.setVisibleRowCount(Math.min(8, Math.max(3, selectedCommits.size())));
            for (CommitInfo commit : selectedCommits) {
                String marker = commit.hash().equals(targetCommit) ? PluginText.text("目标  ", "Target  ") : "      ";
                selectedCommitModel.addElement(marker + commit.shortHash() + "  " + commit.subject());
            }
        }
        selectionTitle.setText(analysisMode == AnalysisRequest.Mode.BUSINESS_DOMAIN
                ? PluginText.text("当前项目业务分析", "Current project business analysis")
                : selectedCommits.isEmpty() ? PluginText.text("尚未选择提交", "No commits selected")
                : PluginText.text("Git 日志选择 · ", "Git log selection · ") + selectedCommits.size()
                        + PluginText.text(" 个提交", " commits"));
        targetSnapshot.setText(analysisMode == AnalysisRequest.Mode.BUSINESS_DOMAIN
                ? PluginText.text("当前 HEAD", "Current HEAD")
                : targetCommit == null ? "" : PluginText.text("目标快照 ", "Target snapshot ") + shortHash(targetCommit));
        selectionPanel.revalidate();
        updateActions();
    }

    private void collectEvidence() {
        if (repositoryRoot == null) {
            Messages.showWarningDialog(project,
                    PluginText.text("当前项目不在可分析的 Git 仓库中。", "The current project is not in an analyzable Git repository."),
                    PluginText.text("无法开始分析", "Cannot start analysis"));
            return;
        }
        String analysisFocus = focus.getText().trim();
        if (analysisFocus.isEmpty()) {
            Messages.showWarningDialog(project,
                    PluginText.text("请输入要理解的业务主题。", "Enter the business topic to analyze."),
                    PluginText.text("缺少分析主题", "Missing analysis topic"));
            return;
        }
        if (analysisMode == AnalysisRequest.Mode.SELECTED_CHANGES
                && (selectedCommits.isEmpty() || targetCommit == null)) {
            Messages.showWarningDialog(project,
                    PluginText.text("请先在 Git 日志中选择提交。", "Select commits in the Git log first."),
                    PluginText.text("无法开始分析", "Cannot start analysis"));
            return;
        }
        savePromptInputs(false);
        AnalysisGuidance guidance = guidanceStore.load(repositoryRoot);
        AnalysisRequest.OutputLanguage language = outputLanguageStore.load();
        AnalysisRequest request = analysisMode == AnalysisRequest.Mode.BUSINESS_DOMAIN
                ? AnalysisRequest.businessDomain(repositoryRoot, analysisFocus, guidance, language)
                : new AnalysisRequest(
                        repositoryRoot, selectedCommits, targetCommit, analysisFocus,
                        AnalysisRequest.Mode.SELECTED_CHANGES, guidance, language
                );
        setBusy(true, PluginText.text("正在锁定所选提交并收集证据...", "Locking the selected commits and collecting evidence..."));
        new Task.Backgroundable(project, PluginText.text("收集 Git 架构证据", "Collect Git architecture evidence"), true) {
            private EvidencePack evidence;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    evidence = request.isBusinessDomain()
                            ? evidenceService.collectSnapshot(request, indicator)
                            : evidenceService.collect(request, indicator);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public void onSuccess() {
                targetCommit = evidence.targetCommit();
                repositoryRoot = evidence.repositoryRoot();
                renderSelection();
                setBusy(false, PluginText.text("Git 范围已锁定，正在准备 ", "Git scope locked; preparing ") + selectedModelName());
                runModel(request, evidence);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                setBusy(false, PluginText.text("证据收集失败", "Evidence collection failed"));
                showError(error);
            }

            @Override
            public void onCancel() {
                setBusy(false, PluginText.text("已取消证据收集", "Evidence collection canceled"));
            }
        }.queue();
    }

    private void runModel(AnalysisRequest request, EvidencePack evidence) {
        ArchitectureAnalysisService analysisService = new ArchitectureAnalysisService(
                ModelClientRegistry.selected(modelProviderStore.load())
        );
        setBusy(true, PluginText.text("正在调用模型并校验架构报告...", "Calling the model and validating the report..."));
        new Task.Backgroundable(project, PluginText.text("生成代码审核与架构报告", "Generate code review and architecture report"), true) {
            private AnalysisResult result;
            private String archiveWarning;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = analysisService.analyze(request, evidence, indicator, message ->
                            ApplicationManager.getApplication().invokeLater(() -> status.setText(PluginText.userMessage(message)))
                    );
                    try {
                        reportArchive.save(request.repositoryRoot(), request, result);
                    } catch (IOException exception) {
                        archiveWarning = exception.getMessage();
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public void onSuccess() {
                lastResult = result;
                lastRequest = request;
                lastEvidence = evidence;
                openReport.setEnabled(true);
                exportHtml.setEnabled(true);
                exportJson.setEnabled(true);
                setBusy(false, archiveWarning == null
                        ? PluginText.text("报告已生成并保存 · ", "Report generated and saved · ") + shortHash(result.targetCommit())
                        : PluginText.text("报告已生成，但本地保存失败 · ", "Report generated, but local save failed · ") + archiveWarning);
                reloadReportHistory(false);
                openReportInEditor(true);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                setBusy(false, PluginText.text("报告生成失败", "Report generation failed"));
                showError(error);
            }

            @Override
            public void onCancel() {
                setBusy(false, PluginText.text("已取消模型分析", "Model analysis canceled"));
            }
        }.queue();
    }

    private void openReportInEditor(boolean replaceCurrent) {
        if (lastResult == null || repositoryRoot == null) return;
        FileEditorManager editors = FileEditorManager.getInstance(project);
        if (replaceCurrent && reportFile != null) {
            editors.closeFile(reportFile);
            reportFile.setValid(false);
            reportFile = null;
        }
        if (reportFile == null || !reportFile.isValid()) {
            reportFile = new ArchitectureReportVirtualFile(lastResult, repositoryRoot, lastRequest, lastEvidence);
        }
        editors.openFile(reportFile, true);
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("CodeBecause");
        if (toolWindow != null && toolWindow.isVisible()) toolWindow.hide();
    }

    private void reloadReportHistory(boolean announce) {
        long generation = ++historyReloadGeneration;
        Path currentRoot = repositoryRoot;
        AnalysisRequest.OutputLanguage visibleLanguage = outputLanguageStore.load();
        if (currentRoot == null) {
            historyLoading = false;
            historyModel.clear();
            historyStatus.setText(PluginText.text("当前项目没有可用路径", "The current project has no usable path"));
            updateHistoryActions();
            return;
        }
        historyLoading = true;
        updateHistoryActions();
        historyStatus.setText(PluginText.text("正在读取本地报告...", "Loading local reports..."));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<ReportArchive.Entry> entries = reportArchive.list(currentRoot).stream()
                        .filter(entry -> entry.outputLanguage() == visibleLanguage)
                        .toList();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (generation != historyReloadGeneration || !currentRoot.equals(repositoryRoot)) return;
                    historyLoading = false;
                    historyModel.clear();
                    entries.forEach(historyModel::addElement);
                    if (!entries.isEmpty()) historyList.setSelectedIndex(0);
                    historyStatus.setText(entries.isEmpty()
                            ? PluginText.text("当前项目还没有本地历史报告", "This project has no local reports yet")
                            : PluginText.text("共 ", "") + entries.size() + PluginText.text(" 份本地报告", " local reports"));
                    if (announce) status.setText(PluginText.text("历史报告列表已刷新", "Report history refreshed"));
                    updateHistoryActions();
                });
            } catch (IOException exception) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (generation != historyReloadGeneration || !currentRoot.equals(repositoryRoot)) return;
                    historyLoading = false;
                    historyStatus.setText(PluginText.text("读取历史报告失败", "Failed to load report history"));
                    updateHistoryActions();
                    Messages.showErrorDialog(project, PluginText.userMessage(exception.getMessage()),
                            PluginText.text("读取历史报告失败", "Failed to load report history"));
                });
            }
        });
    }

    private void openSelectedArchivedReport() {
        if (busy || historyLoading) return;
        ReportArchive.Entry selected = historyList.getSelectedValue();
        if (selected != null) openArchivedReport(selected);
    }

    private void deleteSelectedArchivedReport() {
        if (busy || historyLoading) return;
        ReportArchive.Entry selected = historyList.getSelectedValue();
        if (selected == null) return;
        String reportName = selected.focus().isBlank() ? PluginText.text("未命名分析", "Untitled analysis") : selected.focus();
        int answer = Messages.showYesNoDialog(
                project,
                PluginText.text("确定删除本地报告“" + reportName + "”吗？此操作无法撤销。",
                        "Delete the local report \"" + reportName + "\"? This cannot be undone."),
                PluginText.text("删除历史报告", "Delete report"),
                Messages.getWarningIcon()
        );
        if (answer != Messages.YES) return;

        historyLoading = true;
        historyStatus.setText(PluginText.text("正在删除本地报告...", "Deleting local report..."));
        updateHistoryActions();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                reportArchive.delete(selected);
                ApplicationManager.getApplication().invokeLater(() -> {
                    historyLoading = false;
                    historyModel.removeElement(selected);
                    status.setText(PluginText.text("本地报告已删除", "Local report deleted"));
                    reloadReportHistory(false);
                });
            } catch (IOException exception) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    historyLoading = false;
                    historyStatus.setText(PluginText.text("删除历史报告失败", "Failed to delete report"));
                    updateHistoryActions();
                    Messages.showErrorDialog(project, PluginText.userMessage(exception.getMessage()),
                            PluginText.text("删除历史报告失败", "Failed to delete report"));
                });
            }
        });
    }

    private void openArchivedReport(ReportArchive.Entry entry) {
        historyLoading = true;
        status.setText(PluginText.text("正在打开本地报告...", "Opening local report..."));
        historyStatus.setText(PluginText.text("正在打开本地报告...", "Opening local report..."));
        updateHistoryActions();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                AnalysisResult archived = reportArchive.load(entry);
                ApplicationManager.getApplication().invokeLater(() -> {
                    historyLoading = false;
                    repositoryRoot = entry.repositoryRoot();
                    lastResult = archived;
                    lastRequest = entry.mode() == AnalysisRequest.Mode.BUSINESS_DOMAIN
                            ? AnalysisRequest.businessDomain(
                                    entry.repositoryRoot(), entry.focus(), guidanceStore.load(entry.repositoryRoot()),
                                    entry.outputLanguage())
                            : null;
                    lastEvidence = null;
                    openReportInEditor(true);
                    status.setText(PluginText.text("已打开本地报告 · ", "Opened local report · ") + HISTORY_TIME.format(entry.createdAt()));
                    updateHistoryActions();
                });
            } catch (IOException exception) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    historyLoading = false;
                    historyStatus.setText(PluginText.text("打开历史报告失败", "Failed to open report"));
                    updateHistoryActions();
                    Messages.showErrorDialog(project, PluginText.userMessage(exception.getMessage()),
                            PluginText.text("打开历史报告失败", "Failed to open report"));
                });
            }
        });
    }

    private void loadPromptInputs() {
        AnalysisGuidance guidance = guidanceStore.load(repositoryRoot);
        customPrompt.setText(guidance.customInstructions());
        systemPrompt.setText(guidance.additionalSystemPrompt());
    }

    private void savePromptInputs() {
        savePromptInputs(true);
    }

    private void savePromptInputs(boolean announce) {
        guidanceStore.save(
                customPrompt.getText().strip(),
                systemPrompt.getText().strip()
        );
        if (announce) status.setText(PluginText.text("自定义提示词已保存，下一次分析生效",
                "Custom instructions saved; they apply to the next analysis"));
    }

    private void loadModelProvider() {
        String selected = modelProviderStore.load();
        codexProvider.setSelected(!"claude-local".equals(selected));
        claudeProvider.setSelected("claude-local".equals(selected));
        outputLanguage.setSelectedIndex(outputLanguageStore.load().isEnglish() ? 1 : 0);
        modelProviderStatus.setText("claude-local".equals(selected)
                ? PluginText.text("当前使用 Claude CLI", "Using Claude CLI")
                : PluginText.text("当前使用本机 Codex", "Using local Codex"));
    }

    private void saveModelProvider() {
        String selected = claudeProvider.isSelected() ? "claude-local" : "codex-local";
        modelProviderStore.save(selected);
        AnalysisRequest.OutputLanguage selectedLanguage = outputLanguage.getSelectedIndex() == 1
                ? AnalysisRequest.OutputLanguage.ENGLISH
                : AnalysisRequest.OutputLanguage.CHINESE;
        outputLanguageStore.save(selectedLanguage);
        closeReportInAnotherLanguage(selectedLanguage);
        refreshLanguage();
        modelProviderStatus.setText("claude-local".equals(selected)
                ? PluginText.text("当前使用 Claude CLI", "Using Claude CLI")
                : PluginText.text("当前使用本机 Codex", "Using local Codex"));
        status.setText(PluginText.text("插件语言已立即更新；模型选择将用于下一次分析",
                "Plugin language updated immediately; the model selection applies to the next analysis"));
        reloadReportHistory(false);
    }

    private void closeReportInAnotherLanguage(AnalysisRequest.OutputLanguage language) {
        if (reportFile == null || !reportFile.isValid()) return;
        try {
            String reportLanguage = JsonParser.parseString(reportFile.currentResult().reportJson())
                    .getAsJsonObject().get("output_language").getAsString();
            if (language.code().equals(reportLanguage)) return;
        } catch (RuntimeException ignored) {
            // Reports without language metadata predate language switching and are closed.
        }
        FileEditorManager.getInstance(project).closeFile(reportFile);
        reportFile.setValid(false);
        reportFile = null;
        lastResult = null;
        lastRequest = null;
        lastEvidence = null;
        openReport.setEnabled(false);
        exportHtml.setEnabled(false);
        exportJson.setEnabled(false);
    }

    private void refreshLanguage() {
        int selectedLanguage = outputLanguage.getSelectedIndex();
        outputLanguage.removeAllItems();
        outputLanguage.addItem(PluginText.text("中文（简体）", "Chinese (Simplified)"));
        outputLanguage.addItem("English");
        outputLanguage.setSelectedIndex(selectedLanguage);
        selectedCommitList.getEmptyText().setText(PluginText.text("尚未选择提交", "No commits selected"));
        historyList.getEmptyText().setText(PluginText.text("当前项目还没有本地历史报告", "This project has no local reports yet"));
        systemPrompt.getEmptyText().setText(PluginText.text("仅用于需要影响所有分析阶段的高级约束。",
                "Use only for advanced constraints that must apply to every analysis stage."));
        customPrompt.getEmptyText().setText(PluginText.text(
                "例如：创作者数据由 OMS 导入；沿入口、数据来源、状态变化、落库和消费者展开。",
                "For example: creator data is imported from OMS; follow its entry, origin, state changes, persistence, and consumers."));
        PluginText.refresh(root);
        renderSelection();
        historyList.repaint();
        root.revalidate();
        root.repaint();
    }

    private String selectedModelName() {
        return "claude-local".equals(modelProviderStore.load())
                ? "Claude CLI" : PluginText.text("本机 Codex", "local Codex");
    }

    private static String modeName(AnalysisRequest.Mode mode) {
        return mode == AnalysisRequest.Mode.SELECTED_CHANGES
                ? PluginText.text("提交改动", "Commit changes")
                : PluginText.text("业务理解", "Business logic");
    }

    private static String formatElapsed(long elapsedMs) {
        if (elapsedMs <= 0L) return PluginText.text("耗时未知", "duration unknown");
        long seconds = Math.max(1L, Math.round(elapsedMs / 1000.0));
        if (seconds < 60L) return PluginText.text("耗时 ", "duration ") + seconds + PluginText.text("秒", "s");
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes < 60L) return PluginText.text("耗时 ", "duration ") + minutes
                + PluginText.text("分", "m ") + remainingSeconds + PluginText.text("秒", "s");
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        return PluginText.text("耗时 ", "duration ") + hours + PluginText.text("小时", "h ")
                + remainingMinutes + PluginText.text("分", "m");
    }

    private void export(String extension) {
        AnalysisResult current = reportFile != null && reportFile.isValid() ? reportFile.currentResult() : lastResult;
        if (current == null || repositoryRoot == null) return;
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                PluginText.text("导出 CodeBecause 报告", "Export CodeBecause report"),
                PluginText.text("报告包含锁定提交指纹和源码位置。", "The report includes the locked commit fingerprint and source locations."),
                extension
        );
        FileSaverDialog dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        VirtualFileWrapper target = dialog.save(
                repositoryRoot,
                "ai-code-review-" + shortHash(current.targetCommit()) + "." + extension
        );
        if (target == null) return;
        String content = "html".equals(extension) ? current.reportHtml() : current.reportJson();
        try {
            Files.writeString(target.getFile().toPath(), content, StandardCharsets.UTF_8);
            status.setText(PluginText.text("已导出 ", "Exported ") + target.getFile().getAbsolutePath());
        } catch (IOException exception) {
            Messages.showErrorDialog(project, PluginText.userMessage(exception.getMessage()),
                    PluginText.text("导出失败", "Export failed"));
        }
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        updateActions();
        updateHistoryActions();
        progress.setVisible(busy);
        progress.setIndeterminate(busy);
        if (message != null) status.setText(PluginText.userMessage(message));
    }

    private void switchMode(AnalysisRequest.Mode nextMode) {
        if (busy) return;
        if (nextMode == AnalysisRequest.Mode.SELECTED_CHANGES && selectedCommits.isEmpty()) {
            businessMode.setSelected(true);
            Messages.showInfoMessage(project,
                    PluginText.text("请先在 Git 日志中选择一个或多个连续提交。",
                            "Select one or more contiguous commits in the Git log first."),
                    PluginText.text("尚未选择提交", "No commits selected"));
            return;
        }
        analysisMode = nextMode;
        if (nextMode == AnalysisRequest.Mode.BUSINESS_DOMAIN && isDefaultFocus(focus.getText().trim())) {
            focus.setText("");
        } else if (nextMode == AnalysisRequest.Mode.SELECTED_CHANGES && focus.getText().isBlank()) {
            focus.setText(defaultFocus());
        }
        renderSelection();
    }

    private void updateActions() {
        boolean hasScope = analysisMode == AnalysisRequest.Mode.BUSINESS_DOMAIN
                ? repositoryRoot != null
                : !selectedCommits.isEmpty() && targetCommit != null;
        analyze.setText(analysisMode == AnalysisRequest.Mode.BUSINESS_DOMAIN
                ? PluginText.text("分析当前项目", "Analyze current project")
                : PluginText.text("分析所选提交", "Analyze selected commits"));
        analyze.setEnabled(!busy && hasScope && !focus.getText().trim().isEmpty());
        businessMode.setEnabled(!busy);
        changeMode.setEnabled(!busy && !selectedCommits.isEmpty());
    }

    private void updateHistoryActions() {
        boolean available = !busy && !historyLoading;
        boolean selected = historyList.getSelectedValue() != null;
        openHistory.setEnabled(available && selected);
        deleteHistory.setEnabled(available && selected);
        refreshHistory.setEnabled(available && repositoryRoot != null);
    }

    private void showError(Throwable error) {
        Throwable cause = error;
        while (cause instanceof RuntimeException && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        Messages.showErrorDialog(
                project,
                PluginText.userMessage(cause.getMessage() == null ? cause.toString() : cause.getMessage()),
                "CodeBecause"
        );
    }

    private static String defaultFocus() {
        return PluginText.text(
                "完整解释所选提交改了什么、改动位于业务流程哪里，以及最终改变了什么行为。",
                "Explain what the selected commits changed, where each change sits in the business flow, and its resulting behavior."
        );
    }

    private static boolean isDefaultFocus(String value) {
        return value.equals("完整解释所选提交改了什么、改动位于业务流程哪里，以及最终改变了什么行为。")
                || value.equals("Explain what the selected commits changed, where each change sits in the business flow, and its resulting behavior.");
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    @Override
    public void dispose() {
        if (project.getUserData(PANEL_KEY) == this) project.putUserData(PANEL_KEY, null);
    }
}
