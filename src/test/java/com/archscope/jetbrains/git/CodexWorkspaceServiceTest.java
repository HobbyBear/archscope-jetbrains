package com.archscope.jetbrains.git;

import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CodexWorkspaceServiceTest {
    @TempDir
    Path repository;

    @Test
    void compactsOnlyOversizedDiffLinesAndPreservesTheirDiffPrefix() {
        String patch = "@@ -1 +1 @@\n-" + "a".repeat(20_001) + "\n+" + "b".repeat(20_001) + "\n context\n";

        String compacted = CodexWorkspaceService.compactOversizedDiffLines(patch);

        assertTrue(compacted.startsWith("@@ -1 +1 @@\n-[oversized diff line omitted: 20001 characters]\n"));
        assertTrue(compacted.contains("+[oversized diff line omitted: 20001 characters]\n"));
        assertTrue(compacted.endsWith(" context\n"));
        assertFalse(compacted.contains("a".repeat(100)));
        assertFalse(compacted.contains("b".repeat(100)));
    }

    @Test
    void exportsCompleteLockedCommitWithoutWorkingTreeOrSensitivePaths() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        write("src/App.go", "package src\n// locked source\n");
        write("src/related.go", "package src\n// unchanged related source\n");
        write("docs/design.md", "unrelated design notes\n");
        write("openspec/change/walkthrough.html", "<html>unrelated walkthrough</html>\n");
        write("go.sum", "unrelated dependency lock\n");
        write(".repomind/modules/generated.md", "generated knowledge\n");
        write(".env", "API_KEY=must-not-be-exported\n");
        write("certs/private.pem", "must-not-be-exported\n");
        git("add", ".");
        git("commit", "-m", "Initial source");
        String commitHash = git("rev-parse", "HEAD").strip();
        String treeHash = git("rev-parse", "HEAD^{tree}").strip();
        String emptyTree = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

        write("src/App.go", "package src\n// uncommitted source\n");
        CommitInfo commit = new CommitInfo(
                commitHash, List.of(), "Test", "2026-08-13T10:00:00+08:00", "Initial source"
        );
        String nameStatus = git("diff-tree", "--root", "--no-commit-id", "--name-status", "-r", commitHash);
        EvidencePack evidence = new EvidencePack(
                repository,
                commitHash,
                commitHash,
                treeHash,
                "fingerprint",
                List.of(new EvidencePack.CommitEvidence(
                        commit,
                        emptyTree,
                        nameStatus,
                        List.of(".env", ".repomind/modules/generated.md", "certs/private.pem", "docs/design.md", "go.sum", "openspec/change/walkthrough.html", "src/App.go", "src/related.go")
                )),
                List.of(".env", ".repomind/modules/generated.md", "certs/private.pem", "docs/design.md", "go.sum", "openspec/change/walkthrough.html", "src/App.go", "src/related.go")
        );

        Path workspaceRoot;
        try (CodexWorkspaceService.Workspace workspace = new CodexWorkspaceService().create(evidence, indicator())) {
            workspaceRoot = workspace.root();
            assertTrue(Files.readString(workspace.repository().resolve("src/App.go")).contains("locked source"));
            assertTrue(Files.readString(workspace.repository().resolve("src/related.go")).contains("unchanged related source"));
            assertFalse(Files.exists(workspace.repository().resolve(".env")));
            assertFalse(Files.exists(workspace.repository().resolve("certs/private.pem")));
            assertFalse(Files.exists(workspace.repository().resolve(".repomind/modules/generated.md")));
            assertFalse(Files.exists(workspace.repository().resolve("docs/design.md")));
            assertFalse(Files.exists(workspace.repository().resolve("openspec/change/walkthrough.html")));
            assertFalse(Files.exists(workspace.repository().resolve("go.sum")));

            Path diff;
            try (var files = Files.list(workspace.root().resolve("evidence"))) {
                diff = files.filter(path -> path.getFileName().toString().endsWith(".diff"))
                        .findFirst()
                        .orElseThrow();
            }
            String patch = Files.readString(diff);
            assertTrue(patch.contains("locked source"));
            assertFalse(patch.contains("must-not-be-exported"));
            assertFalse(patch.contains("generated knowledge"));
            assertFalse(patch.contains("unrelated design notes"));
            assertFalse(patch.contains("unrelated walkthrough"));
            assertFalse(patch.contains("unrelated dependency lock"));
        }
        assertFalse(Files.exists(workspaceRoot));
    }

    @Test
    void classifiesCodeAndRuntimeDefinitionsWithoutDocumentationOrGeneratedArtifacts() {
        assertTrue(CodexWorkspaceService.isCodeEvidencePath("src/chat.go"));
        assertTrue(CodexWorkspaceService.isCodeEvidencePath("src/chat_test.go"));
        assertTrue(CodexWorkspaceService.isCodeEvidencePath("config/app.json"));
        assertTrue(CodexWorkspaceService.isCodeEvidencePath("migrations/001.sql"));
        assertTrue(CodexWorkspaceService.isCodeEvidencePath("Dockerfile"));
        assertTrue(CodexWorkspaceService.isCodeEvidencePath("go.mod"));
        assertFalse(CodexWorkspaceService.isCodeEvidencePath("go.sum"));
        assertFalse(CodexWorkspaceService.isCodeEvidencePath("docs/design.md"));
        assertFalse(CodexWorkspaceService.isCodeEvidencePath("openspec/change/walkthrough.html"));
        assertFalse(CodexWorkspaceService.isCodeEvidencePath(".repomind/modules/chat.md"));
        assertFalse(CodexWorkspaceService.isCodeEvidencePath("vendor/example/code.go"));
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = repository.resolve(relativePath);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private String git(String... arguments) throws Exception {
        String[] full = new String[arguments.length + 3];
        full[0] = "git";
        full[1] = "-C";
        full[2] = repository.toString();
        System.arraycopy(arguments, 0, full, 3, arguments.length);
        Process process = new ProcessBuilder(full).directory(repository.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }

    private ProgressIndicator indicator() {
        return (ProgressIndicator) Proxy.newProxyInstance(
                ProgressIndicator.class.getClassLoader(),
                new Class<?>[]{ProgressIndicator.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == double.class ? 0.0
                        : null
        );
    }
}
