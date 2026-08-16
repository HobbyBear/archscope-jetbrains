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
    void reportsStructuredFailureAfterVerboseHookOutput() {
        String output = "hook".repeat(3000) + "\n"
                + "{\"type\":\"result\",\"subtype\":\"error_during_execution\","
                + "\"is_error\":true,\"result\":\"Prompt is too long\"}\n";

        assertEquals("Prompt is too long", client.failureMessage(output));
    }

    @Test
    void fallsBackToTheEndOfUnstructuredFailureOutput() {
        String output = "startup hook\n" + "x".repeat(3000) + "\nactual failure";

        String message = client.failureMessage(output);

        assertTrue(message.startsWith("... output truncated ..."));
        assertTrue(message.endsWith("actual failure"));
    }

    @Test
    void preservesLocalClaudeRuntimeConfigurationForEveryWorkspaceMode() {
        List<String> closed = client.command(ModelClient.WorkspaceAccess.CLOSED_EVIDENCE);
        List<String> repository = client.command(ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY);

        assertEquals(closed, repository);
        assertTrue(closed.containsAll(List.of("-p", "--output-format", "stream-json", "--no-session-persistence")));
        assertFalse(closed.contains("--setting-sources"));
        assertFalse(closed.contains("--permission-mode"));
        assertFalse(closed.contains("--strict-mcp-config"));
        assertFalse(closed.contains("--mcp-config"));
        assertFalse(closed.contains("--disable-slash-commands"));
        assertFalse(closed.stream().anyMatch(argument -> argument.startsWith("--tools")));
    }
}
