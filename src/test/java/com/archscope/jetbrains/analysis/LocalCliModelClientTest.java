package com.archscope.jetbrains.analysis;

import org.junit.jupiter.api.Test;

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
                ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY
        );
        assertTrue(repositoryCommand.containsAll(List.of("--sandbox", "read-only")));
    }

    @Test
    void removesIdeNativeLibraryPathFromCodexEnvironment() {
        ProcessBuilder builder = client.processBuilder(client.command(), Path.of("."));
        assertFalse(builder.environment().containsKey("LD_LIBRARY_PATH"));
    }
}
