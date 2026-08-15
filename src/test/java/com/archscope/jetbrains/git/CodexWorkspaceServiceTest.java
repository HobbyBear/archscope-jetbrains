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
    void exportsCompleteLockedCommitWithoutWorkingTreeOrSensitivePaths() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        write("src/App.txt", "locked source\n");
        write("docs/related.txt", "unchanged related source\n");
        write(".repomind/modules/generated.md", "generated knowledge\n");
        write(".env", "API_KEY=must-not-be-exported\n");
        write("certs/private.pem", "must-not-be-exported\n");
        git("add", ".");
        git("commit", "-m", "Initial source");
        String commitHash = git("rev-parse", "HEAD").strip();
        String treeHash = git("rev-parse", "HEAD^{tree}").strip();
        String emptyTree = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

        write("src/App.txt", "uncommitted source\n");
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
                        List.of(".env", ".repomind/modules/generated.md", "certs/private.pem", "docs/related.txt", "src/App.txt")
                )),
                List.of(".env", ".repomind/modules/generated.md", "certs/private.pem", "docs/related.txt", "src/App.txt")
        );

        Path workspaceRoot;
        try (CodexWorkspaceService.Workspace workspace = new CodexWorkspaceService().create(evidence, indicator())) {
            workspaceRoot = workspace.root();
            assertEquals("locked source", Files.readString(workspace.repository().resolve("src/App.txt")).strip());
            assertEquals("unchanged related source", Files.readString(workspace.repository().resolve("docs/related.txt")).strip());
            assertFalse(Files.exists(workspace.repository().resolve(".env")));
            assertFalse(Files.exists(workspace.repository().resolve("certs/private.pem")));
            assertFalse(Files.exists(workspace.repository().resolve(".repomind/modules/generated.md")));

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
        }
        assertFalse(Files.exists(workspaceRoot));
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
