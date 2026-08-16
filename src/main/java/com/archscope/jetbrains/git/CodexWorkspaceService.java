package com.archscope.jetbrains.git;

import com.archscope.jetbrains.i18n.PluginLanguage;
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
    private static final int MAX_DIFF_LINE_LENGTH = 20_000;
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "go", "java", "kt", "kts", "groovy", "gradle", "scala", "clj", "cljs",
            "js", "jsx", "ts", "tsx", "vue", "svelte", "css", "scss", "less",
            "py", "rb", "php", "rs", "swift", "dart", "lua",
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "sql", "proto", "graphql", "gql", "tf", "hcl",
            "json", "yaml", "yml", "toml", "xml", "ini", "conf", "properties", "mod"
    );
    private static final Set<String> CODE_FILENAMES = Set.of(
            "dockerfile", "makefile", "jenkinsfile", "procfile", "build", "workspace"
    );

    public Workspace create(EvidencePack evidence, ProgressIndicator indicator) throws GitCommandException {
        Path root = null;
        long startedAt = System.nanoTime();
        try {
            root = Files.createTempDirectory("ai-code-review-codex-");
            Path repository = Files.createDirectories(root.resolve("repository"));
            GitCli git = new GitCli(evidence.repositoryRoot());

            Path diffDirectory = Files.createDirectories(root.resolve("evidence"));
            indicator.setText(PluginLanguage.text("准备所选提交的聚合差异", "Preparing the combined diff for selected commits"));
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
            indicator.setText(PluginLanguage.text("物化聚合差异涉及的目标源码", "Materializing target source files referenced by the combined diff"));
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
            indicator.setText(PluginLanguage.text("准备受限业务源码快照", "Preparing the restricted business source snapshot"));
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
        return compactOversizedDiffLines(
                SensitiveTextSanitizer.redact(git.run(indicator, arguments.toArray(String[]::new)))
        );
    }

    static String compactOversizedDiffLines(String patch) {
        StringBuilder compacted = new StringBuilder(Math.min(patch.length(), 512_000));
        int cursor = 0;
        while (cursor < patch.length()) {
            int newline = patch.indexOf('\n', cursor);
            int end = newline < 0 ? patch.length() : newline;
            int contentEnd = end > cursor && patch.charAt(end - 1) == '\r' ? end - 1 : end;
            int lineLength = contentEnd - cursor;
            if (lineLength <= MAX_DIFF_LINE_LENGTH) {
                compacted.append(patch, cursor, newline < 0 ? end : end + 1);
            } else {
                char prefix = patch.charAt(cursor);
                boolean hasDiffPrefix = prefix == '+' || prefix == '-' || prefix == ' ';
                if (hasDiffPrefix) compacted.append(prefix);
                compacted.append("[oversized diff line omitted: ")
                        .append(lineLength - (hasDiffPrefix ? 1 : 0))
                        .append(" characters]");
                if (contentEnd != end) compacted.append('\r');
                if (newline >= 0) compacted.append('\n');
            }
            cursor = newline < 0 ? patch.length() : newline + 1;
        }
        return compacted.toString();
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
        return safe.stream().filter(CodexWorkspaceService::isCodeEvidencePath).toList();
    }

    public static boolean isCodeEvidencePath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith(".repomind/") || normalized.contains("/.repomind/")
                || normalized.startsWith("openspec/") || normalized.contains("/openspec/")
                || normalized.startsWith("docs/") || normalized.contains("/docs/")
                || normalized.startsWith("vendor/") || normalized.contains("/vendor/")
                || normalized.startsWith("node_modules/") || normalized.contains("/node_modules/")
                || normalized.startsWith("dist/") || normalized.contains("/dist/")
                || normalized.startsWith("build/") || normalized.contains("/build/")
                || normalized.startsWith("coverage/") || normalized.contains("/coverage/")) {
            return false;
        }
        int slash = normalized.lastIndexOf('/');
        String filename = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (filename.equals("go.sum") || filename.endsWith(".lock") || filename.endsWith("-lock.json")
                || filename.endsWith(".min.js") || filename.endsWith(".min.css") || filename.endsWith(".map")) {
            return false;
        }
        if (CODE_FILENAMES.contains(filename) || filename.startsWith("dockerfile.")) return true;
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && CODE_EXTENSIONS.contains(filename.substring(dot + 1));
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
