package com.archscope.jetbrains.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaudeCliModelClientTest {
    private final ClaudeCliModelClient client = new ClaudeCliModelClient();

    @TempDir
    Path temporaryDirectory;

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
    void preservesTheUsersNormalCliCapabilities() throws Exception {
        List<String> repository = client.command(ModelClient.WorkspaceAccess.CURRENT_REPOSITORY);

        assertTrue(repository.containsAll(List.of("-p", "--output-format", "stream-json",
                "--include-partial-messages", "--no-session-persistence")));
        assertFalse(repository.contains("--tools"));
        assertFalse(repository.contains("--permission-mode"));
        assertFalse(repository.contains("--allowedTools"));
        assertFalse(repository.contains("--disallowedTools"));
        assertFalse(repository.contains("--disable-slash-commands"));
        assertFalse(repository.contains("--setting-sources"));
        assertFalse(repository.contains("--strict-mcp-config"));
        assertFalse(repository.contains("--mcp-config"));
        assertEquals("medium", repository.get(repository.indexOf("--effort") + 1));

        List<String> bounded = client.command(ModelClient.WorkspaceAccess.BOUNDED_EVIDENCE);
        assertEquals("low", bounded.get(bounded.indexOf("--effort") + 1));
        assertEquals("", bounded.get(bounded.indexOf("--tools") + 1));

        List<String> readOnly = client.command(ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY);
        assertFalse(readOnly.contains("--effort"));
        assertEquals("dontAsk", readOnly.get(readOnly.indexOf("--permission-mode") + 1));
        assertEquals("Read,Grep,Glob,Bash,Skill", readOnly.get(readOnly.indexOf("--tools") + 1));
        assertEquals("Write,Edit,NotebookEdit", readOnly.get(readOnly.indexOf("--disallowedTools") + 1));

        Path systemPromptFile = temporaryDirectory.resolve("system prompt.txt");
        String systemPrompt = """
                Trace the real entry -> decision -> result path.
                - Keep every source-backed branch.
                """;
        Files.writeString(systemPromptFile, systemPrompt);
        List<String> withSystemRole = client.command(
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                systemPromptFile
        );
        int systemIndex = withSystemRole.indexOf("--system-prompt-file");
        assertTrue(systemIndex >= 0);
        assertEquals(systemPromptFile.toString(), withSystemRole.get(systemIndex + 1));
        assertEquals(systemPrompt, Files.readString(Path.of(withSystemRole.get(systemIndex + 1))));
        assertFalse(withSystemRole.contains("->"));

        String outputSchema = "{\"type\":\"object\",\"additionalProperties\":false}";
        List<String> structured = client.command(
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY, systemPromptFile, outputSchema);
        assertEquals(outputSchema, structured.get(structured.indexOf("--json-schema") + 1));
    }

    @Test
    void resolvesTheConfiguredClaudeExecutableForFunctionAnalysis() throws Exception {
        Path executable = Files.createTempFile(temporaryDirectory, "claude-function-analysis-", "");
        assertTrue(executable.toFile().setExecutable(true));
        System.setProperty("archscope.claudeCliPath", executable.toString());
        try {
            assertEquals(executable.toAbsolutePath().normalize().toString(),
                    client.command(ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY).get(0));
        } finally {
            System.clearProperty("archscope.claudeCliPath");
        }
    }
}
