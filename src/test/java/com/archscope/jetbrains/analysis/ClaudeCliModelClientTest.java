package com.archscope.jetbrains.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaudeCliModelClientTest {
    private final ClaudeCliModelClient client = new ClaudeCliModelClient();

    @Test
    void extractsClaudeFinalResult() throws Exception {
        String output = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"draft"}]}}
                {"type":"result","subtype":"success","is_error":false,"result":"{\\"schema\\":\\"ok\\"}"}
                """;

        assertEquals("{\"schema\":\"ok\"}", client.extractResult(output));
    }

    @Test
    void disablesToolsForClosedEvidenceAndAllowsOnlyReadToolsForRepositoryExploration() {
        List<String> closed = client.command(ModelClient.WorkspaceAccess.CLOSED_EVIDENCE);
        List<String> repository = client.command(ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY);

        assertTrue(closed.containsAll(List.of("--no-session-persistence", "--strict-mcp-config")));
        assertFalse(closed.contains("--setting-sources"));
        assertEquals("", closed.get(closed.indexOf("--tools") + 1));
        assertEquals("Read,Glob,Grep", repository.get(repository.indexOf("--tools") + 1));
    }
}
