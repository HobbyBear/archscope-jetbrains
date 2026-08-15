package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.CommitInfo;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.vcs.log.CommitId;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.VcsCommitMetadata;
import com.intellij.vcs.log.VcsLogCommitSelection;
import com.intellij.vcs.log.VcsLogDataKeys;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AnalyzeGitLogSelectionAction extends DumbAwareAction {
    private static final int MAX_COMMITS = 40;

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VcsLogCommitSelection selection = event.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION);
        if (project == null || selection == null) return;

        List<CommitId> selected = selection.getCommits();
        if (selected.isEmpty()) return;
        if (selected.size() > MAX_COMMITS) {
            Messages.showWarningDialog(
                    project,
                    "一次最多分析 " + MAX_COMMITS + " 个提交，请缩小选择范围。",
                    "选择的提交过多"
            );
            return;
        }
        String rootPath = selected.get(0).getRoot().getPath();
        if (selected.stream().anyMatch(commit -> !rootPath.equals(commit.getRoot().getPath()))) {
            Messages.showWarningDialog(project, "请选择同一个 Git 仓库中的提交。", "无法跨仓库分析");
            return;
        }

        Map<String, VcsCommitMetadata> metadataByHash = new HashMap<>();
        for (VcsCommitMetadata metadata : selection.getCachedMetadata()) {
            metadataByHash.put(metadata.getId().asString(), metadata);
        }
        List<CommitInfo> commits = selected.stream()
                .map(commit -> toCommitInfo(commit, metadataByHash.get(commit.getHash().asString())))
                .toList();
        String targetCommit = commits.get(0).hash();
        ArchitectureToolWindowPanel.analyzeGitLogSelection(
                project,
                Path.of(rootPath),
                commits,
                targetCommit
        );
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VcsLogCommitSelection selection = event.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION);
        List<CommitId> commits = selection == null ? List.of() : selection.getCommits();
        boolean sameRoot = commits.isEmpty() || commits.stream()
                .map(commit -> commit.getRoot().getPath())
                .distinct()
                .limit(2)
                .count() == 1;
        boolean enabled = event.getProject() != null
                && !commits.isEmpty()
                && commits.size() <= MAX_COMMITS
                && sameRoot;
        event.getPresentation().setEnabledAndVisible(enabled);
        if (commits.size() > 1) {
            event.getPresentation().setText("AI 分析选中的 " + commits.size() + " 个提交");
        } else {
            event.getPresentation().setText("AI 分析此提交");
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    static CommitInfo toCommitInfo(CommitId commit, VcsCommitMetadata metadata) {
        if (metadata == null) {
            return new CommitInfo(
                    commit.getHash().asString(),
                    List.of(),
                    "",
                    "1970-01-01T00:00:00Z",
                    commit.getHash().toShortString()
            );
        }
        List<String> parents = metadata.getParents().stream().map(Hash::asString).toList();
        String authoredAt = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(metadata.getAuthorTime()),
                ZoneId.systemDefault()
        ).toString();
        return new CommitInfo(
                commit.getHash().asString(),
                parents,
                metadata.getAuthor().getName(),
                authoredAt,
                metadata.getSubject()
        );
    }
}
