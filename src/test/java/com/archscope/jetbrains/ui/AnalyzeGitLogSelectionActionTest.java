package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.CommitInfo;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.vcs.log.CommitId;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.VcsCommitMetadata;
import com.intellij.vcs.log.VcsUser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AnalyzeGitLogSelectionActionTest {
    @Test
    void mapsNativeGitMetadataIntoTheExistingAnalysisRequestModel() {
        Hash commitHash = hash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Hash parentHash = hash("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        CommitId commit = new CommitId(commitHash, new LightVirtualFile("repo"));
        VcsUser author = proxy(VcsUser.class, (method, args) -> switch (method) {
            case "getName" -> "A. Developer";
            case "getEmail" -> "dev@example.test";
            default -> null;
        });
        VcsCommitMetadata metadata = proxy(VcsCommitMetadata.class, (method, args) -> switch (method) {
            case "getId" -> commitHash;
            case "getParents" -> List.of(parentHash);
            case "getAuthor" -> author;
            case "getAuthorTime" -> 1_786_659_600_000L;
            case "getSubject" -> "Change behavior";
            default -> null;
        });

        CommitInfo mapped = AnalyzeGitLogSelectionAction.toCommitInfo(commit, metadata);

        assertEquals(commitHash.asString(), mapped.hash());
        assertEquals(List.of(parentHash.asString()), mapped.parents());
        assertEquals("A. Developer", mapped.author());
        assertEquals("Change behavior", mapped.subject());
        assertNotEquals("1970-01-01T00:00:00Z", mapped.authoredAt());
        assertTrue(mapped.parsedAuthoredAt().getYear() >= 2026);
    }

    @Test
    void fallsBackToTheNativeCommitIdWhenMetadataIsNotCached() {
        Hash hash = hash("cccccccccccccccccccccccccccccccccccccccc");
        CommitInfo mapped = AnalyzeGitLogSelectionAction.toCommitInfo(
                new CommitId(hash, new LightVirtualFile("repo")),
                null
        );

        assertEquals(hash.asString(), mapped.hash());
        assertEquals(hash.toShortString(), mapped.subject());
        assertEquals(List.of(), mapped.parents());
    }

    @Test
    void registersTheActionInTheNativeVcsLogContextMenu() throws Exception {
        String pluginXml;
        try (var input = getClass().getResourceAsStream("/META-INF/plugin.xml")) {
            pluginXml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertTrue(pluginXml.contains("ArchScope.AnalyzeGitLogSelection"));
        assertTrue(pluginXml.contains("group-id=\"Vcs.Log.ContextMenu\""));
    }

    private static Hash hash(String value) {
        return proxy(Hash.class, (method, args) -> switch (method) {
            case "asString" -> value;
            case "toShortString" -> value.substring(0, 8);
            case "toString" -> value;
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args)
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
