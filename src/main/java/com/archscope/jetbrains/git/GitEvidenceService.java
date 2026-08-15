package com.archscope.jetbrains.git;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GitEvidenceService {
    private static final Logger LOG = Logger.getInstance(GitEvidenceService.class);
    private static final int MAX_COMMITS = 40;

    public EvidencePack collectSnapshot(AnalysisRequest request, ProgressIndicator indicator) throws GitCommandException {
        long startedAt = System.nanoTime();
        indicator.setIndeterminate(false);
        indicator.setText("锁定当前工作区快照");
        GitCli requestedGit = new GitCli(request.repositoryRoot());
        Path repositoryRoot = requestedGit.findRepositoryRoot(indicator);
        GitCli git = new GitCli(repositoryRoot);
        String head = git.run(indicator, "rev-parse", "HEAD").trim();
        String tree = git.run(indicator, "rev-parse", "HEAD^{tree}").trim();

        indicator.setText("读取当前快照文件清单");
        List<String> manifest = git.run(indicator, "ls-tree", "-r", "--name-only", "HEAD")
                .lines()
                .map(String::strip)
                .filter(path -> !path.isBlank() && !SensitiveTextSanitizer.isSensitivePath(path))
                .toList();

        indicator.setText("计算业务分析指纹");
        String fingerprint = sha256(String.join("\n",
                "business-domain/v1",
                head,
                tree,
                request.focus()
        ));
        indicator.setFraction(1.0);
        EvidencePack result = new EvidencePack(
                repositoryRoot,
                head,
                head,
                head,
                tree,
                fingerprint,
                List.of(),
                "",
                List.of(),
                List.copyOf(manifest)
        );
        LOG.info("Business snapshot collected: target=" + shortHash(head)
                + ", manifestFiles=" + manifest.size()
                + ", elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000));
        return result;
    }

    public EvidencePack collect(AnalysisRequest request, ProgressIndicator indicator) throws GitCommandException {
        long startedAt = System.nanoTime();
        indicator.setIndeterminate(false);
        if (request.selectedCommits().isEmpty()) {
            throw new GitCommandException("请至少选择一个提交");
        }
        if (request.selectedCommits().size() > MAX_COMMITS) {
            throw new GitCommandException("一次最多分析 " + MAX_COMMITS + " 个提交，请缩小范围");
        }

        GitCli git = new GitCli(request.repositoryRoot());
        indicator.setText("锁定 Git 快照");
        String head = git.run(indicator, "rev-parse", "HEAD").trim();
        Map<String, CommitInfo> lockedByHash = new HashMap<>();
        for (CommitInfo selected : request.selectedCommits()) {
            indicator.checkCanceled();
            String commit = git.run(indicator, "rev-parse", selected.hash() + "^{commit}").trim();
            lockedByHash.put(commit, loadCommit(git, indicator, commit));
        }

        SelectionRange range = resolveSelectionRange(git, indicator, lockedByHash);
        String target = range.targetCommit();
        String targetTree = git.run(indicator, "rev-parse", target + "^{tree}").trim();

        List<EvidencePack.CommitEvidence> commitEvidence = new ArrayList<>();
        int commitIndex = 0;
        for (String hash : range.orderedCommits()) {
            indicator.checkCanceled();
            indicator.setFraction(0.05 + (0.35 * commitIndex / request.selectedCommits().size()));
            indicator.setText("提取提交 " + shortHash(hash) + " 的变化");
            CommitInfo locked = lockedByHash.get(hash);
            String base = locked.parents().isEmpty() ? emptyTree(git, indicator) : locked.parents().get(0);
            String nameStatus = git.run(
                    indicator,
                    "diff",
                    "--find-renames",
                    "--find-copies",
                    "--name-status",
                    base,
                    hash,
                    "--"
            );
            nameStatus = withoutSensitivePaths(nameStatus);
            List<String> paths = parseChangedPaths(nameStatus);
            commitEvidence.add(new EvidencePack.CommitEvidence(
                    locked,
                    base,
                    nameStatus,
                    paths
            ));
            commitIndex++;
        }

        indicator.setText("合并所选提交的最终变化");
        String aggregateNameStatus = withoutSensitivePaths(git.run(
                indicator,
                "diff", "--find-renames", "--find-copies", "--name-status",
                range.baseCommit(), target, "--"
        ));
        List<String> aggregateChangedPaths = parseChangedPaths(aggregateNameStatus);

        indicator.setText("读取目标提交文件清单");
        List<String> manifest = git.run(indicator, "ls-tree", "-r", "--name-only", target)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();

        indicator.setText("计算分析指纹");
        String fingerprint = sha256(String.join("\n",
                target,
                targetTree,
                range.baseCommit(),
                request.focus(),
                range.orderedCommits().toString()
        ));
        indicator.setFraction(0.95);
        EvidencePack result = new EvidencePack(
                request.repositoryRoot(),
                head,
                range.baseCommit(),
                target,
                targetTree,
                fingerprint,
                List.copyOf(commitEvidence),
                aggregateNameStatus,
                aggregateChangedPaths,
                List.copyOf(manifest)
        );
        LOG.info("Git evidence collected: commits=" + result.commits().size()
                + ", aggregateChangedPaths=" + result.aggregateChangedPaths().size()
                + ", base=" + shortHash(result.baseCommit())
                + ", target=" + shortHash(result.targetCommit())
                + ", manifestFiles=" + result.targetManifest().size()
                + ", elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000));
        return result;
    }

    private SelectionRange resolveSelectionRange(
            GitCli git,
            ProgressIndicator indicator,
            Map<String, CommitInfo> selected
    ) throws GitCommandException {
        Set<String> hashes = selected.keySet();
        String target = uniqueBoundary(git, indicator, hashes, false);
        String oldest = uniqueBoundary(git, indicator, hashes, true);
        CommitInfo oldestCommit = selected.get(oldest);
        String base = oldestCommit.parents().isEmpty() ? emptyTree(git, indicator) : oldestCommit.parents().get(0);

        List<String> ordered;
        if (oldestCommit.parents().isEmpty()) {
            ordered = git.run(indicator, "rev-list", "--first-parent", "--reverse", target)
                    .lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        } else {
            ordered = git.run(indicator, "rev-list", "--first-parent", "--reverse", base + ".." + target)
                    .lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        }
        if (ordered.size() != hashes.size() || !new LinkedHashSet<>(ordered).equals(new LinkedHashSet<>(hashes))) {
            throw new GitCommandException(
                    "多选提交必须是连续的 first-parent 提交链；当前选择包含间隔提交或跨分支提交，请在 Git 日志中选择连续范围。"
            );
        }
        return new SelectionRange(base, target, List.copyOf(ordered));
    }

    private String uniqueBoundary(
            GitCli git,
            ProgressIndicator indicator,
            Set<String> hashes,
            boolean oldest
    ) throws GitCommandException {
        List<String> candidates = new ArrayList<>();
        for (String candidate : hashes) {
            boolean boundary = true;
            for (String other : hashes) {
                if (candidate.equals(other)) continue;
                boolean related = oldest
                        ? git.isAncestor(indicator, candidate, other)
                        : git.isAncestor(indicator, other, candidate);
                if (!related) {
                    boundary = false;
                    break;
                }
            }
            if (boundary) candidates.add(candidate);
        }
        if (candidates.size() != 1) {
            throw new GitCommandException("无法把所选提交合并成单一前后快照：请选择同一提交链上的连续提交。");
        }
        return candidates.get(0);
    }

    private String shortHash(String hash) {
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
    }

    private record SelectionRange(String baseCommit, String targetCommit, List<String> orderedCommits) {
    }

    private CommitInfo loadCommit(GitCli git, ProgressIndicator indicator, String hash) throws GitCommandException {
        String record = git.run(
                indicator,
                "show",
                "-s",
                "--date=iso-strict",
                "--format=%H%x1f%P%x1f%an%x1f%aI%x1f%s",
                hash
        ).strip();
        String[] fields = record.split("\\u001f", -1);
        if (fields.length < 5) {
            throw new GitCommandException("无法解析提交元数据：" + hash);
        }
        List<String> parents = fields[1].isBlank() ? List.of() : List.of(fields[1].trim().split(" +"));
        return new CommitInfo(fields[0], parents, fields[2], fields[3], fields[4]);
    }

    private String emptyTree(GitCli git, ProgressIndicator indicator) throws GitCommandException {
        return "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
    }

    private List<String> parseChangedPaths(String nameStatus) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String line : nameStatus.lines().toList()) {
            String[] fields = line.split("\\t");
            if (fields.length < 2) {
                continue;
            }
            String status = fields[0];
            if ((status.startsWith("R") || status.startsWith("C")) && fields.length >= 3) {
                paths.add(fields[1]);
                paths.add(fields[2]);
            } else {
                paths.add(fields[1]);
            }
        }
        return List.copyOf(paths);
    }

    private String withoutSensitivePaths(String nameStatus) {
        return nameStatus.lines()
                .filter(line -> {
                    String[] fields = line.split("\\t");
                    if (fields.length < 2) return false;
                    return List.of(fields).subList(1, fields.length).stream()
                            .noneMatch(SensitiveTextSanitizer::isSensitivePath);
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
