package com.archscope.jetbrains.git;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.CommitInfo;
import com.archscope.jetbrains.model.EvidencePack;
import com.intellij.openapi.progress.ProgressIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GitEvidenceServiceTest {
    @TempDir
    Path repository;

    @BeforeEach
    void initializeRepository() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
    }

    @Test
    void resolvesAggregateRangeByTopologyInsteadOfAuthorTime() throws Exception {
        commit("base.txt", "base\n", "Base", "2026-08-14T10:00:00+08:00");
        String base = git("rev-parse", "HEAD").strip();
        commit("first.go", "package sample\n", "First", "2026-08-14T12:00:00+08:00");
        String first = git("rev-parse", "HEAD").strip();
        commit("second.go", "package sample\n", "Second", "2026-08-14T11:00:00+08:00");
        String second = git("rev-parse", "HEAD").strip();

        AnalysisRequest request = new AnalysisRequest(
                repository,
                List.of(info(first, "2026-08-14T12:00:00+08:00"), info(second, "2026-08-14T11:00:00+08:00")),
                first,
                "Explain selected changes"
        );
        EvidencePack evidence = new GitEvidenceService().collect(request, indicator());

        assertEquals(base, evidence.baseCommit());
        assertEquals(second, evidence.targetCommit());
        assertEquals(List.of(first, second), evidence.commits().stream().map(item -> item.commit().hash()).toList());
        assertEquals(List.of("first.go", "second.go"), evidence.aggregateChangedPaths());
        assertTrue(evidence.aggregateNameStatus().contains("first.go"));
        assertTrue(evidence.aggregateNameStatus().contains("second.go"));
    }

    @Test
    void rejectsASelectionWithAnUnselectedCommitInTheMiddle() throws Exception {
        commit("base.txt", "base\n", "Base", "2026-08-14T10:00:00+08:00");
        commit("first.go", "one\n", "First", "2026-08-14T11:00:00+08:00");
        String first = git("rev-parse", "HEAD").strip();
        commit("middle.go", "middle\n", "Middle", "2026-08-14T12:00:00+08:00");
        commit("last.go", "last\n", "Last", "2026-08-14T13:00:00+08:00");
        String last = git("rev-parse", "HEAD").strip();

        AnalysisRequest request = new AnalysisRequest(
                repository,
                List.of(info(first, "2026-08-14T11:00:00+08:00"), info(last, "2026-08-14T13:00:00+08:00")),
                last,
                ""
        );

        GitCommandException error = assertThrows(
                GitCommandException.class,
                () -> new GitEvidenceService().collect(request, indicator())
        );
        assertTrue(error.getMessage().contains("连续"));
    }

    @Test
    void locksCurrentSnapshotForAUserDefinedBusinessTopic() throws Exception {
        commit("chat/service.go", "package chat\n", "Add chat", "2026-08-14T10:00:00+08:00");
        AnalysisRequest request = AnalysisRequest.businessDomain(repository.resolve("chat"), "分析聊天逻辑");

        EvidencePack evidence = new GitEvidenceService().collectSnapshot(request, indicator());

        assertEquals(repository.toAbsolutePath().normalize(), evidence.repositoryRoot());
        assertEquals(evidence.headCommit(), evidence.targetCommit());
        assertEquals(evidence.headCommit(), evidence.baseCommit());
        assertEquals(List.of(), evidence.commits());
        assertTrue(evidence.targetManifest().contains("chat/service.go"));
        assertTrue(evidence.aggregateChangedPaths().isEmpty());
    }

    private CommitInfo info(String hash, String authoredAt) {
        return new CommitInfo(hash, List.of(), "Test", authoredAt, hash.substring(0, 8));
    }

    private void commit(String path, String content, String subject, String authoredAt) throws Exception {
        Path target = repository.resolve(path);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        git("add", path);
        ProcessBuilder builder = gitBuilder("commit", "-m", subject);
        builder.environment().put("GIT_AUTHOR_DATE", authoredAt);
        builder.environment().put("GIT_COMMITTER_DATE", authoredAt);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }

    private String git(String... arguments) throws Exception {
        Process process = gitBuilder(arguments).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }

    private ProcessBuilder gitBuilder(String... arguments) {
        String[] full = new String[arguments.length + 3];
        full[0] = "git";
        full[1] = "-C";
        full[2] = repository.toString();
        System.arraycopy(arguments, 0, full, 3, arguments.length);
        return new ProcessBuilder(full).directory(repository.toFile()).redirectErrorStream(true);
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
