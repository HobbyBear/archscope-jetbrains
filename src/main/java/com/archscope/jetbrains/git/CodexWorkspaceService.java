package com.archscope.jetbrains.git;

import com.archscope.jetbrains.model.EvidencePack;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CodexWorkspaceService {
    private static final Logger LOG = Logger.getInstance(CodexWorkspaceService.class);

    public Workspace create(EvidencePack evidence, ProgressIndicator indicator) throws GitCommandException {
        Path root = null;
        long startedAt = System.nanoTime();
        try {
            root = Files.createTempDirectory("ai-code-review-codex-");
            Path repository = Files.createDirectories(root.resolve("repository"));
            GitCli git = new GitCli(evidence.repositoryRoot());

            Path diffDirectory = Files.createDirectories(root.resolve("evidence"));
            indicator.setText("准备所选提交的聚合差异");
            List<String> aggregatePaths = safePaths(evidence.aggregateNameStatus());
            String aggregatePatch = readPatch(
                    git,
                    indicator,
                    evidence.baseCommit(),
                    evidence.targetCommit(),
                    aggregatePaths
            );
            Files.writeString(diffDirectory.resolve("aggregate.diff"), aggregatePatch, StandardCharsets.UTF_8);
            Files.writeString(
                    diffDirectory.resolve("aggregate.name-status.txt"),
                    evidence.aggregateNameStatus(),
                    StandardCharsets.UTF_8
            );

            Workspace workspace = new Workspace(root, repository, git, evidence.targetCommit(), evidence.targetManifest());
            indicator.setText("物化聚合差异涉及的目标源码");
            workspace.materialize(aggregatePaths, indicator);

            LOG.info("Codex workspace prepared: files=" + countFiles(repository)
                    + ", commits=" + evidence.commits().size()
                    + ", elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000));
            return workspace;
        } catch (GitCommandException exception) {
            deleteRecursively(root);
            throw exception;
        } catch (IOException exception) {
            deleteRecursively(root);
            throw new GitCommandException("无法准备本机 Codex 源码快照：" + exception.getMessage());
        }
    }

    public Workspace createSnapshot(EvidencePack evidence, ProgressIndicator indicator) throws GitCommandException {
        Path root = null;
        try {
            root = Files.createTempDirectory("ai-code-review-domain-");
            Path repository = Files.createDirectories(root.resolve("repository"));
            Files.createDirectories(root.resolve("evidence"));
            indicator.setText("准备受限业务源码快照");
            return new Workspace(
                    root,
                    repository,
                    new GitCli(evidence.repositoryRoot()),
                    evidence.targetCommit(),
                    evidence.targetManifest()
            );
        } catch (IOException exception) {
            deleteRecursively(root);
            throw new GitCommandException("无法准备业务源码快照：" + exception.getMessage());
        }
    }

    private String readPatch(
            GitCli git,
            ProgressIndicator indicator,
            String base,
            String target,
            List<String> safePaths
    ) throws GitCommandException {
        if (safePaths.isEmpty()) return "";
        List<String> arguments = new ArrayList<>(List.of(
                "diff", "--find-renames", "--find-copies", "--no-ext-diff", "--unified=5",
                base, target, "--"
        ));
        arguments.addAll(safePaths);
        return SensitiveTextSanitizer.redact(git.run(indicator, arguments.toArray(String[]::new)));
    }

    private List<String> safePaths(String nameStatus) {
        LinkedHashSet<String> safe = new LinkedHashSet<>();
        for (String line : nameStatus.lines().toList()) {
            String[] fields = line.split("\\t");
            if (fields.length < 2) continue;
            List<String> paths = List.of(fields).subList(1, fields.length);
            if (paths.stream().anyMatch(SensitiveTextSanitizer::isSensitivePath)) continue;
            safe.addAll(paths);
        }
        List<String> focused = safe.stream().filter(path -> !isGeneratedKnowledge(path)).toList();
        return focused.isEmpty() ? List.copyOf(safe) : focused;
    }

    private static boolean isGeneratedKnowledge(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith(".repomind/") || normalized.contains("/.repomind/");
    }

    private long countFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    LOG.warn("Could not delete temporary Codex workspace path " + path);
                }
            }
        } catch (IOException exception) {
            LOG.warn("Could not traverse temporary Codex workspace " + root, exception);
        }
    }

    public static final class Workspace implements AutoCloseable {
        private final Path root;
        private final Path repository;
        private final GitCli git;
        private final String targetCommit;
        private final Set<String> manifest;
        private final Set<String> materialized = new LinkedHashSet<>();

        private Workspace(Path root, Path repository, GitCli git, String targetCommit, List<String> manifest) {
            this.root = root;
            this.repository = repository;
            this.git = git;
            this.targetCommit = targetCommit;
            this.manifest = Set.copyOf(manifest);
        }

        public Path root() {
            return root;
        }

        public Path repository() {
            return repository;
        }

        public void materialize(Collection<String> paths, ProgressIndicator indicator) throws GitCommandException, IOException {
            for (String path : paths) {
                indicator.checkCanceled();
                String normalized = path.replace('\\', '/');
                if (!manifest.contains(normalized)
                        || SensitiveTextSanitizer.isSensitivePath(normalized)
                        || !materialized.add(normalized)) {
                    continue;
                }
                String content = git.run(indicator, "show", targetCommit + ":" + normalized);
                if (content.indexOf('\0') >= 0) continue;
                Path destination = repository.resolve(normalized).normalize();
                if (!destination.startsWith(repository)) continue;
                Path parent = destination.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(destination, SensitiveTextSanitizer.redact(content), StandardCharsets.UTF_8);
            }
        }

        public String readEvidence(String filename) throws IOException {
            Path path = root.resolve("evidence").resolve(filename).normalize();
            if (!path.startsWith(root.resolve("evidence"))) throw new IOException("Unsafe evidence path");
            return Files.readString(path, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            deleteRecursively(root);
        }
    }
}
