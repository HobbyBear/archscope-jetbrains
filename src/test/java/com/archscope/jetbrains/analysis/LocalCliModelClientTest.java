package com.archscope.jetbrains.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalCliModelClientTest {
    private final LocalCliModelClient client = new LocalCliModelClient();

    @Test
    void extractsCodexFinalAgentMessage() throws Exception {
        String output = """
                {"type":"thread.started","thread_id":"test"}
                {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"{\\"schema\\":\\"ok\\"}"}}
                {"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1}}
                """;
        assertEquals("{\"schema\":\"ok\"}", client.extractResult(output));
    }

    @Test
    void keepsTheConfiguredModelButBoundsReasoningForClosedAnalysis() {
        List<String> codex = client.command();
        List<String> invocation = List.of(
                "exec", "--skip-git-repo-check", "--ephemeral", "-c",
                "model_reasoning_effort=\"medium\"", "--json", "-"
        );
        assertEquals(invocation, codex.subList(codex.size() - invocation.size(), codex.size()));
        assertTrue(codex.contains("-c"));
        assertFalse(codex.contains("--sandbox"));
        assertFalse(codex.contains("--model"));
        assertTrue(client.command("识别独立业务流程").contains("model_reasoning_effort=\"low\""));
        assertTrue(client.command("补充业务报告").contains("model_reasoning_effort=\"low\""));
        assertTrue(client.command("生成业务理解报告").contains("model_reasoning_effort=\"low\""));
        assertTrue(client.command("收敛业务理解报告").contains("model_reasoning_effort=\"low\""));
        assertTrue(client.command("收敛待确认项").contains("model_reasoning_effort=\"low\""));
        assertTrue(client.command("生成多流程报告").contains("model_reasoning_effort=\"medium\""));
        List<String> repositoryCommand = client.command(
                "分析业务域与完整流程",
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY
        );
        assertFalse(repositoryCommand.contains("--sandbox"));
        List<String> readOnlyCommand = client.command(
                "执行函数业务流程分析",
                ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY
        );
        assertEquals("read-only", readOnlyCommand.get(readOnlyCommand.indexOf("--sandbox") + 1));
        assertTrue(readOnlyCommand.contains("model_reasoning_effort=\"low\""));
        List<String> withSystemRole = client.command(
                "分析业务域与完整流程",
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                "第一行\n严格遵守 \"项目约束\""
        );
        assertTrue(withSystemRole.stream().anyMatch(value -> value.startsWith("developer_instructions=")));
        assertTrue(withSystemRole.stream().anyMatch(value -> value.contains("第一行\\n严格遵守 \\\"项目约束\\\"")));
        Path schema = Path.of("/tmp/business-schema.json");
        Path result = Path.of("/tmp/business-result.json");
        List<String> structured = client.command(
                "执行业务分析 SOP",
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                "严格完成 SOP",
                schema,
                result
        );
        assertEquals(schema.toString(), structured.get(structured.indexOf("--output-schema") + 1));
        assertEquals(result.toString(), structured.get(structured.indexOf("--output-last-message") + 1));
        assertTrue(structured.stream().anyMatch(value -> value.contains("严格完成 SOP")));
    }

    @Test
    void removesIdeNativeLibraryPathFromCodexEnvironment() {
        ProcessBuilder builder = client.processBuilder(client.command(), Path.of("."));
        assertFalse(builder.environment().containsKey("LD_LIBRARY_PATH"));
        assertFalse(builder.environment().containsKey("DYLD_LIBRARY_PATH"));
        assertTrue(builder.environment().get("PATH").contains("/usr/bin"));
        assertEquals(Path.of(".").toFile(), builder.directory());
    }

    @Test
    void resolvesTheConfiguredCodexExecutableForFunctionAnalysis() throws Exception {
        Path executable = Files.createTempFile("codex-function-analysis-", "");
        assertTrue(executable.toFile().setExecutable(true));
        System.setProperty("archscope.codexCliPath", executable.toString());
        try {
            assertEquals(executable.toAbsolutePath().normalize().toString(),
                    client.command("执行函数业务流程分析", ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY).get(0));
        } finally {
            System.clearProperty("archscope.codexCliPath");
            Files.deleteIfExists(executable);
        }
    }
}
