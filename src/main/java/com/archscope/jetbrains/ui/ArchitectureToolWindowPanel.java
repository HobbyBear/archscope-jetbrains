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
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SearchTextField;
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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

final class ArchitectureToolWindowPanel implements Disposable {
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
    private final JBLabel status = new JBLabel(PluginText.text("等待新建分析", "Ready for a new analysis"));
    private final JProgressBar progress = new JProgressBar();
    private final JButton analyze = new JButton(PluginText.text("重新分析当前选择", "Analyze current selection"));
    private final JButton showLiveAnalysis = new JButton(PluginText.text("查看实时过程", "Show live output"));
    private final JPanel liveAnalysisBar = new JPanel(new BorderLayout(8, 0));
    private final JBLabel liveAnalysisLabel = new JBLabel();
    private final JButton openReport = new JButton(PluginText.text("全局查看", "Open report"));
    private final JButton exportHtml = new JButton(PluginText.text("导出 HTML", "Export HTML"));
    private final JButton exportJson = new JButton(PluginText.text("导出 JSON", "Export JSON"));
    private final JToggleButton businessMode = new JToggleButton(PluginText.text("业务理解", "Business logic"));
    private final JToggleButton changeMode = new JToggleButton(PluginText.text("提交改动", "Commit changes"));
    private final GitEvidenceService evidenceService = new GitEvidenceService();
    private final ReportArchive reportArchive = new ReportArchive();
    private final DefaultListModel<ReportArchive.Entry> historyModel = new DefaultListModel<>();
    private final JBList<ReportArchive.Entry> historyList = new JBList<>(historyModel);
    private final SearchTextField historySearch = new SearchTextField(false);
    private final JBLabel historyStatus = new JBLabel(PluginText.text("正在读取本地报告...", "Loading local reports..."));
    private final JButton openHistory = new JButton(PluginText.text("打开报告", "Open report"));
    private final JButton deleteHistory = new JButton(PluginText.text("删除所选", "Delete selected"));
    private final JButton selectAllHistory = new JButton(PluginText.text("全选", "Select all"));
    private final JButton refreshHistory = new JButton(PluginText.text("刷新", "Refresh"));
    private final JToggleButton allHistory = new JToggleButton(PluginText.text("全部", "All"));
    private final JToggleButton functionHistory = new JToggleButton(PluginText.text("函数流程", "Functions"));
    private final JToggleButton businessHistory = new JToggleButton(PluginText.text("业务分析", "Business"));
    private final JBTabbedPane navigation = new JBTabbedPane();
    private final JBTextArea systemPrompt = new JBTextArea(14, 60);
    private final JButton savePrompts = new JButton(PluginText.text("保存 System Prompt", "Save System Prompt"));
    private final JToggleButton codexProvider = new JToggleButton(PluginText.text("本机 Codex", "Local Codex"));
    private final JToggleButton claudeProvider = new JToggleButton("Claude CLI");
    private final JButton saveModelProvider = new JButton(PluginText.text("保存模型配置", "Save model settings"));
    private final TextFieldWithBrowseButton cliWorkingDirectory = new TextFieldWithBrowseButton();
    private final JComboBox<String> outputLanguage = new JComboBox<>(new String[]{
            PluginText.text("中文（简体）", "Chinese (Simplified)"), "English"
    });
    private final JBLabel modelProviderStatus = new JBLabel();
    private final ProjectAnalysisGuidanceStore guidanceStore;
    private final ModelProviderStore modelProviderStore;
    private final OutputLanguageStore outputLanguageStore;
    private final CliWorkingDirectoryStore cliWorkingDirectoryStore;

    private Path repositoryRoot;
    private List<CommitInfo> selectedCommits = List.of();
    private String targetCommit;
    private AnalysisResult lastResult;
    private ArchitectureReportVirtualFile reportFile;
    private AnalysisRequest.Mode analysisMode = AnalysisRequest.Mode.BUSINESS_DOMAIN;
    private AnalysisRequest lastRequest;
    private EvidencePack lastEvidence;
    private boolean busy;
    private int activeOperations;
    private boolean historyLoading;
    private List<ReportArchive.Entry> historyEntries = List.of();
    private long historyReloadGeneration;

    ArchitectureToolWindowPanel(Project project) {
        this.project = project;
        this.guidanceStore = new ProjectAnalysisGuidanceStore(project);
        this.modelProviderStore = new ModelProviderStore(project);
        this.outputLanguageStore = new OutputLanguageStore(project);
        this.cliWorkingDirectoryStore = new CliWorkingDirectoryStore(project);
        this.outputLanguageStore.load();
        if (project.getBasePath() != null) repositoryRoot = Path.of(project.getBasePath());
        cliWorkingDirectory.setText(cliWorkingDirectoryStore.load(repositoryRoot));
        project.getMessageBus().connect(this).subscribe(ReportHistoryListener.TOPIC, changedRoot -> {
            if (repositoryRoot != null && repositoryRoot.toAbsolutePath().normalize()
                    .equals(changedRoot.toAbsolutePath().normalize())) reloadReportHistory(false);
        });
        project.getMessageBus().connect(this).subscribe(AnalysisRunListener.TOPIC, () ->
                ApplicationManager.getApplication().invokeLater(this::updateLiveAnalysisAction));
        buildUi();
        loadPromptInputs();
        renderSelection();
        reloadReportHistory(false);
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
        JPanel workingDirectoryPanel = new JPanel(new BorderLayout(8, 0));
        workingDirectoryPanel.add(new JBLabel(PluginText.text("工作目录", "Working directory")), BorderLayout.WEST);
        cliWorkingDirectory.addBrowseFolderListener(
                PluginText.text("选择分析工作目录", "Select analysis working directory"),
                PluginText.text("Codex 或 Claude CLI 将从这个目录运行。", "Codex or Claude CLI runs from this directory."),
                project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
        );
        cliWorkingDirectory.setToolTipText(PluginText.text(
                "默认使用当前仓库目录；可在本次分析前更换",
                "Defaults to the current repository; change it before running this analysis"));
        workingDirectoryPanel.add(cliWorkingDirectory, BorderLayout.CENTER);
        selectionPanel.add(workingDirectoryPanel, BorderLayout.CENTER);

        focus.setLineWrap(true);
        focus.setWrapStyleWord(true);
        focus.setText("");
        JPanel focusPanel = new JPanel(new BorderLayout(0, 5));
        focusPanel.setBorder(JBUI.Borders.empty(8, 10));
        JPanel focusHeader = new JPanel(new BorderLayout(8, 0));
        focusHeader.add(new JBLabel(PluginText.text("分析主题", "Analysis topic")), BorderLayout.WEST);
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
        navigation.addTab("System Prompt", buildPromptPanel());
        navigation.addTab(PluginText.text("模型配置", "Model settings"), buildModelPanel());
        navigation.setSelectedIndex(0);
        root.add(navigation, BorderLayout.CENTER);
        liveAnalysisBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(7, 10)
        ));
        liveAnalysisBar.add(liveAnalysisLabel, BorderLayout.CENTER);
        liveAnalysisBar.add(showLiveAnalysis, BorderLayout.EAST);
        liveAnalysisBar.setVisible(false);
        root.add(liveAnalysisBar, BorderLayout.SOUTH);

        analyze.addActionListener(event -> collectEvidence());
        openReport.addActionListener(event -> openReportInEditor(false));
        exportHtml.addActionListener(event -> export("html"));
        exportJson.addActionListener(event -> export("json"));
        businessMode.addActionListener(event -> switchMode(AnalysisRequest.Mode.BUSINESS_DOMAIN));
        changeMode.addActionListener(event -> switchMode(AnalysisRequest.Mode.SELECTED_CHANGES));
        openHistory.addActionListener(event -> openSelectedArchivedReport());
        deleteHistory.addActionListener(event -> deleteSelectedArchivedReport());
        selectAllHistory.addActionListener(event -> toggleAllVisibleHistory());
        refreshHistory.addActionListener(event -> reloadReportHistory(true));
        showLiveAnalysis.addActionListener(event -> FunctionAnalysisRunRegistry.showActive(project, showLiveAnalysis));
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
        cliWorkingDirectory.getTextField().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                saveCliWorkingDirectory();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                saveCliWorkingDirectory();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                saveCliWorkingDirectory();
            }
        });
        updateActions();
        openReport.setEnabled(false);
        exportHtml.setEnabled(false);
        exportJson.setEnabled(false);
        updateLiveAnalysisAction();
    }

    private JComponent buildHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(10));
        JPanel controls = new JPanel(new BorderLayout(0, 8));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        JBLabel title = new JBLabel(PluginText.text("本项目的历史分析", "Analysis history for this project"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        header.add(title, BorderLayout.WEST);
        header.add(refreshHistory, BorderLayout.EAST);
        controls.add(header, BorderLayout.NORTH);
        historySearch.getTextEditor().getEmptyText().setText(PluginText.text(
                "搜索函数名、文件路径或报告标题", "Search function, file, or report title"));
        controls.add(historySearch, BorderLayout.CENTER);
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ButtonGroup historyTypes = new ButtonGroup();
        historyTypes.add(allHistory);
        historyTypes.add(functionHistory);
        historyTypes.add(businessHistory);
        allHistory.setSelected(true);
        filters.add(allHistory);
        filters.add(functionHistory);
        filters.add(businessHistory);
        controls.add(filters, BorderLayout.SOUTH);
        panel.add(controls, BorderLayout.NORTH);

        historyList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
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
                String focusText = entry.mode() == AnalysisRequest.Mode.FUNCTION_FLOW && !entry.functionSymbol().isBlank()
                        ? entry.functionSymbol()
                        : !entry.title().isBlank() ? entry.title()
                        : entry.focus().isBlank() ? PluginText.text("未命名分析", "Untitled analysis") : entry.focus();
                append(focusText, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                String source = entry.mode() == AnalysisRequest.Mode.FUNCTION_FLOW && !entry.functionFile().isBlank()
                        ? " · " + entry.functionFile() : "";
                append(source + "  " + HISTORY_TIME.format(entry.createdAt())
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
                else if (event.getKeyCode() == KeyEvent.VK_DELETE) deleteSelectedArchivedReport();
            }
        });
        historySearch.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyHistoryFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyHistoryFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyHistoryFilter();
            }
        });
        allHistory.addActionListener(event -> applyHistoryFilter());
        functionHistory.addActionListener(event -> applyHistoryFilter());
        businessHistory.addActionListener(event -> applyHistoryFilter());
        panel.add(new JBScrollPane(historyList), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(historyStatus, BorderLayout.CENTER);
        JPanel historyActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        historyActions.add(selectAllHistory);
        historyActions.add(deleteHistory);
        historyActions.add(openHistory);
        footer.add(historyActions, BorderLayout.EAST);
        openHistory.setEnabled(false);
        deleteHistory.setEnabled(false);
        selectAllHistory.setEnabled(false);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildPromptPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(JBUI.Borders.empty(10));
        int row = 0;
        row = addPromptField(form, row, "System Prompt", systemPrompt,
                PluginText.text("项目级系统指令。每个模型阶段都会严格遵守，用于定义代码导航、知识来源和业务分析要求。",
                        "Project-level system instructions. Every model stage follows them for navigation, knowledge sources, and analysis rules."));

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
        Path configuredWorkingDirectory = resolveCliWorkingDirectory();
        if (!cliWorkingDirectory.getText().isBlank() && configuredWorkingDirectory == null) return;
        cliWorkingDirectory.setText(configuredWorkingDirectory.toString());
        cliWorkingDirectoryStore.save(configuredWorkingDirectory.toString());
        AnalysisRequest request = (analysisMode == AnalysisRequest.Mode.BUSINESS_DOMAIN
                ? AnalysisRequest.businessDomain(repositoryRoot, analysisFocus, guidance, language)
                : new AnalysisRequest(
                        repositoryRoot, selectedCommits, targetCommit, analysisFocus,
                        AnalysisRequest.Mode.SELECTED_CHANGES, guidance, language
                )).withCliWorkingDirectory(configuredWorkingDirectory);
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
        FunctionAnalysisRunDialog runDialog = new FunctionAnalysisRunDialog(project, request.focus());
        runDialog.show();
        setBusy(true, PluginText.text("正在调用模型并校验架构报告...", "Calling the model and validating the report..."));
        new Task.Backgroundable(project, PluginText.text("生成代码审核与架构报告", "Generate code review and architecture report"), true) {
            private AnalysisResult result;
            private String archiveWarning;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                runDialog.attach(indicator);
                try {
                    result = analysisService.analyze(request, evidence, indicator, message ->
                                    ApplicationManager.getApplication().invokeLater(() -> {
                                        status.setText(PluginText.userMessage(message));
                                        runDialog.updateStatus(message);
                                    }),
                            runDialog::accept);
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
                runDialog.completed(PluginText.text(
                        "分析完成，报告已生成。", "Analysis completed; the report was generated."));
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
                openCompletedReport(request, evidence, result);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Throwable cause = rootCause(error);
                runDialog.failed(cause.getMessage() == null
                        ? PluginText.text("分析失败，已保留执行过程。", "Analysis failed; execution details were preserved.")
                        : PluginText.userMessage(cause.getMessage()));
                setBusy(false, PluginText.text("报告生成失败", "Report generation failed"));
                showError(error);
            }

            @Override
            public void onCancel() {
                runDialog.cancelled();
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

    private void openCompletedReport(AnalysisRequest request, EvidencePack evidence, AnalysisResult result) {
        ArchitectureReportVirtualFile completed = new ArchitectureReportVirtualFile(
                result, request.repositoryRoot(), request, evidence);
        reportFile = completed;
        FileEditorManager.getInstance(project).openFile(completed, true);
    }

    private void reloadReportHistory(boolean announce) {
        long generation = ++historyReloadGeneration;
        Path currentRoot = repositoryRoot;
        AnalysisRequest.OutputLanguage visibleLanguage = outputLanguageStore.load();
        if (currentRoot == null) {
            historyLoading = false;
            historyEntries = List.of();
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
                    historyEntries = entries;
                    applyHistoryFilter();
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

    private void applyHistoryFilter() {
        Set<String> selectedIds = historyList.getSelectedValuesList().stream()
                .map(ReportArchive.Entry::id)
                .collect(java.util.stream.Collectors.toSet());
        String query = historySearch.getText().strip().toLowerCase(Locale.ROOT);
        List<ReportArchive.Entry> visible = historyEntries.stream()
                .filter(entry -> !functionHistory.isSelected() || entry.mode() == AnalysisRequest.Mode.FUNCTION_FLOW)
                .filter(entry -> !businessHistory.isSelected() || entry.mode() == AnalysisRequest.Mode.BUSINESS_DOMAIN)
                .filter(entry -> matchesHistory(entry, query))
                .toList();
        historyModel.clear();
        visible.forEach(historyModel::addElement);
        int[] selectedIndices = IntStream.range(0, historyModel.size())
                .filter(index -> selectedIds.contains(historyModel.get(index).id()))
                .toArray();
        if (selectedIndices.length > 0) historyList.setSelectedIndices(selectedIndices);
        else if (!visible.isEmpty()) historyList.setSelectedIndex(0);
        boolean filtered = !query.isBlank() || !allHistory.isSelected();
        historyList.getEmptyText().setText(filtered
                ? PluginText.text("没有匹配的历史报告", "No matching reports")
                : PluginText.text("当前项目还没有本地历史报告", "This project has no local reports yet"));
        historyStatus.setText(historyEntries.isEmpty()
                ? PluginText.text("当前项目还没有本地历史报告", "This project has no local reports yet")
                : filtered
                ? PluginText.text("显示 ", "Showing ") + visible.size() + " / " + historyEntries.size()
                : PluginText.text("共 ", "") + historyEntries.size() + PluginText.text(" 份本地报告", " local reports"));
        updateHistoryActions();
    }

    static boolean matchesHistory(ReportArchive.Entry entry, String query) {
        if (query == null || query.isBlank()) return true;
        String searchable = String.join(" ", entry.focus(), entry.title(), entry.functionSymbol(),
                entry.functionFile(), entry.targetCommit()).toLowerCase(Locale.ROOT);
        for (String token : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!searchable.contains(token)) return false;
        }
        return true;
    }

    private void openSelectedArchivedReport() {
        if (busy || historyLoading) return;
        List<ReportArchive.Entry> selected = historyList.getSelectedValuesList();
        if (selected.size() == 1) openArchivedReport(selected.get(0));
    }

    private void deleteSelectedArchivedReport() {
        if (busy || historyLoading) return;
        List<ReportArchive.Entry> selected = List.copyOf(historyList.getSelectedValuesList());
        if (selected.isEmpty()) return;
        String reportName = selected.get(0).focus().isBlank()
                ? PluginText.text("未命名分析", "Untitled analysis") : selected.get(0).focus();
        String message = selected.size() == 1
                ? PluginText.text("确定删除本地报告“" + reportName + "”吗？此操作无法撤销。",
                "Delete the local report \"" + reportName + "\"? This cannot be undone.")
                : PluginText.text("确定删除选中的 " + selected.size() + " 份本地报告吗？此操作无法撤销。",
                "Delete the " + selected.size() + " selected local reports? This cannot be undone.");
        int answer = Messages.showYesNoDialog(
                project,
                message,
                PluginText.text("删除历史报告", "Delete reports"),
                Messages.getWarningIcon()
        );
        if (answer != Messages.YES) return;

        historyLoading = true;
        historyStatus.setText(PluginText.text("正在删除本地报告...", "Deleting local report..."));
        updateHistoryActions();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                reportArchive.deleteAll(selected);
                ApplicationManager.getApplication().invokeLater(() -> {
                    historyLoading = false;
                    selected.forEach(historyModel::removeElement);
                    status.setText(PluginText.text("已删除 " + selected.size() + " 份本地报告",
                            "Deleted " + selected.size() + " local report(s)"));
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

    private void toggleAllVisibleHistory() {
        if (busy || historyLoading || historyModel.isEmpty()) return;
        if (historyList.getSelectedIndices().length == historyModel.size()) historyList.clearSelection();
        else historyList.setSelectionInterval(0, historyModel.size() - 1);
        updateHistoryActions();
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
                    AnalysisRequest archivedRequest;
                    if (entry.mode() == AnalysisRequest.Mode.BUSINESS_DOMAIN) {
                        archivedRequest = AnalysisRequest.businessDomain(
                                entry.repositoryRoot(), entry.focus(), guidanceStore.load(entry.repositoryRoot()),
                                entry.outputLanguage());
                    } else if (entry.mode() == AnalysisRequest.Mode.FUNCTION_FLOW) {
                        archivedRequest = AnalysisRequest.functionFlow(
                                entry.repositoryRoot(),
                                FunctionReportSupport.target(archived.reportJson(), entry.repositoryRoot()),
                                guidanceStore.load(entry.repositoryRoot()),
                                entry.outputLanguage());
                    } else {
                        archivedRequest = null;
                    }
                    Path archivedWorkingDirectory = configuredCliWorkingDirectory(entry.repositoryRoot());
                    lastRequest = archivedRequest == null ? null : archivedRequest.withCliWorkingDirectory(archivedWorkingDirectory);
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
        systemPrompt.setText(guidance.systemPrompt());
    }

    private void savePromptInputs() {
        savePromptInputs(true);
    }

    private void savePromptInputs(boolean announce) {
        guidanceStore.save(systemPrompt.getText().strip());
        if (announce) status.setText(PluginText.text("System Prompt 已保存，下一次分析生效",
                "System Prompt saved; it applies to the next analysis"));
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

    private Path resolveCliWorkingDirectory() {
        String configured = cliWorkingDirectory.getText().strip();
        Path resolved = null;
        try {
            if (!configured.isBlank()) {
                Path candidate = Path.of(configured);
                if (!candidate.isAbsolute() && repositoryRoot != null) candidate = repositoryRoot.resolve(candidate);
                candidate = candidate.toAbsolutePath().normalize();
                if (Files.isDirectory(candidate)) resolved = candidate;
            }
        } catch (InvalidPathException ignored) {
            // The validation message below is shared with missing directories.
        }
        if (!configured.isBlank() && resolved == null) {
            Messages.showWarningDialog(project,
                    PluginText.text("工作目录不存在或不是有效目录。", "The working directory does not exist."),
                    PluginText.text("工作目录无效", "Invalid working directory"));
        }
        return resolved == null && configured.isBlank() ? repositoryRoot : resolved;
    }

    private Path configuredCliWorkingDirectory(Path fallback) {
        String configured = cliWorkingDirectory.getText().strip();
        try {
            if (!configured.isBlank()) {
                Path candidate = Path.of(configured);
                if (!candidate.isAbsolute() && fallback != null) candidate = fallback.resolve(candidate);
                candidate = candidate.toAbsolutePath().normalize();
                if (Files.isDirectory(candidate)) return candidate;
            }
        } catch (InvalidPathException ignored) {
            // Historical reports remain readable even if a saved working directory is no longer available.
        }
        return fallback;
    }

    private void saveCliWorkingDirectory() {
        cliWorkingDirectoryStore.save(cliWorkingDirectory.getText());
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
        historySearch.getTextEditor().getEmptyText().setText(PluginText.text(
                "搜索函数名、文件路径或报告标题", "Search function, file, or report title"));
        systemPrompt.getEmptyText().setText(PluginText.text(
                "项目级系统指令。每个模型阶段都会严格遵守。",
                "Project-level system instructions followed by every model stage."));
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
        if (mode == AnalysisRequest.Mode.SELECTED_CHANGES) return PluginText.text("提交改动", "Commit changes");
        if (mode == AnalysisRequest.Mode.FUNCTION_FLOW) return PluginText.text("函数流程", "Function flow");
        return PluginText.text("业务理解", "Business logic");
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
        if (value) activeOperations++;
        else activeOperations = Math.max(0, activeOperations - 1);
        busy = activeOperations > 0;
        updateActions();
        updateHistoryActions();
        progress.setVisible(busy);
        progress.setIndeterminate(busy);
        if (message != null) status.setText(PluginText.userMessage(message));
    }

    private void switchMode(AnalysisRequest.Mode nextMode) {
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
        analyze.setEnabled(hasScope && !focus.getText().trim().isEmpty());
        businessMode.setEnabled(true);
        changeMode.setEnabled(!selectedCommits.isEmpty());
    }

    private void updateHistoryActions() {
        boolean available = !busy && !historyLoading;
        int selected = historyList.getSelectedIndices().length;
        openHistory.setEnabled(available && selected == 1);
        deleteHistory.setEnabled(available && selected > 0);
        deleteHistory.setText(selected > 1
                ? PluginText.text("删除所选 (" + selected + ")", "Delete selected (" + selected + ")")
                : PluginText.text("删除所选", "Delete selected"));
        boolean allSelected = historyModel.size() > 0 && selected == historyModel.size();
        selectAllHistory.setText(allSelected
                ? PluginText.text("取消全选", "Clear selection")
                : PluginText.text("全选", "Select all"));
        selectAllHistory.setEnabled(available && historyModel.size() > 0);
        refreshHistory.setEnabled(available && repositoryRoot != null);
    }

    private void updateLiveAnalysisAction() {
        int count = FunctionAnalysisRunRegistry.activeCount(project);
        boolean active = count > 0;
        liveAnalysisLabel.setText(PluginText.text(
                count + " 个分析任务正在运行", count + " analysis task(s) are running"));
        showLiveAnalysis.setText(PluginText.text(
                "查看任务 (" + count + ")", "Show tasks (" + count + ")"));
        showLiveAnalysis.setEnabled(active);
        liveAnalysisBar.setVisible(active);
        liveAnalysisBar.revalidate();
        liveAnalysisBar.repaint();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
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
    }
}
