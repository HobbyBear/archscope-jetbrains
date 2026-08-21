package com.archscope.jetbrains.analysis;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelRunAuditLogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsInitialInputAndClaudeRepoMindToolEvidenceWithoutToolOutput() throws Exception {
        ModelRunAuditLog log = new ModelRunAuditLog(temporaryDirectory);
        ModelRunAuditLog.Run run = log.start(
                "claude-local",
                "执行业务分析 SOP",
                temporaryDirectory,
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                "必须使用 repomind-query，api_key=secret-value-123456",
                "分析周常任务"
        );
        run.recordClaudeEvent(JsonParser.parseString("""
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","name":"Skill","input":{"skill":"repomind-query"}},
                  {"type":"tool_use","name":"Read","input":{"file_path":".claude/skills/repomind-query/SKILL.md"}},
                  {"type":"tool_use","name":"Bash","input":{"command":"repomind kb-migrate && repomind kb-metadata","authorization":"Bearer abcdefghijklmnop"}},
                  {"type":"tool_use","name":"Read","input":{"file_path":"apps/chat/.repomind/concepts/weekly-creator-activity.md"}}
                ]},"tool_output":"source code must not be persisted"}
                """).getAsJsonObject());
        run.recordResult("""
                {"summary":"safe","api_key":"secret-value-123456"}
                """);
        run.finish("completed", 1234, 42);

        JsonObject request = JsonParser.parseString(
                Files.readString(run.directory().resolve("request.json"))).getAsJsonObject();
        JsonObject summary = JsonParser.parseString(
                Files.readString(run.directory().resolve("summary.json"))).getAsJsonObject();
        String events = Files.readString(run.directory().resolve("events.jsonl"));

        assertTrue(request.get("system_prompt").getAsString().contains("[REDACTED_SECRET]"));
        assertFalse(request.toString().contains("secret-value-123456"));
        assertTrue(summary.get("repomind_mentioned_in_initial_input").getAsBoolean());
        assertTrue(summary.get("repomind_runtime_evidence").getAsBoolean());
        assertTrue(summary.get("repomind_skill_loaded").getAsBoolean());
        assertTrue(summary.get("repomind_kb_migrate_executed").getAsBoolean());
        assertTrue(summary.get("repomind_kb_metadata_executed").getAsBoolean());
        assertEquals(4, summary.get("tool_call_count").getAsInt());
        assertTrue(summary.getAsJsonArray("knowledge_files_read").toString()
                .contains("weekly-creator-activity.md"));
        assertTrue(events.contains("repomind kb-metadata"));
        assertFalse(events.contains("abcdefghijklmnop"));
        assertFalse(events.contains("source code must not be persisted"));
        String result = Files.readString(run.directory().resolve("result.json"));
        assertTrue(result.contains("safe"));
        assertFalse(result.contains("secret-value-123456"));
        assertTrue(Files.readString(temporaryDirectory.resolve("latest-claude-local.json"))
                .contains(run.directory().toString().replace("\\", "\\\\")));
    }

    @Test
    void distinguishesMentionedRepoMindFromRuntimeEvidenceAndRecordsCodexInputs() throws Exception {
        ModelRunAuditLog log = new ModelRunAuditLog(temporaryDirectory);
        ModelRunAuditLog.Run mentionedOnly = log.start(
                "codex-local", "stage", temporaryDirectory,
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                "Use RepoMind", "question");
        mentionedOnly.recordCodexEvent(JsonParser.parseString("""
                {"type":"item.completed","item":{"type":"command_execution","command":"rg -n weekly service"}}
                """).getAsJsonObject());
        mentionedOnly.finish("completed", 10, 2);
        JsonObject firstSummary = JsonParser.parseString(
                Files.readString(mentionedOnly.directory().resolve("summary.json"))).getAsJsonObject();
        assertTrue(firstSummary.get("repomind_mentioned_in_initial_input").getAsBoolean());
        assertFalse(firstSummary.get("repomind_runtime_evidence").getAsBoolean());

        ModelRunAuditLog.Run executed = log.start(
                "codex-local", "stage", temporaryDirectory,
                ModelClient.WorkspaceAccess.CURRENT_REPOSITORY,
                "Use RepoMind", "question");
        executed.recordCodexEvent(JsonParser.parseString("""
                {"type":"item.completed","item":{"type":"command_execution",
                 "command":"sed -n '1,120p' apps/chat/.repomind/modules/activity.md"}}
                """).getAsJsonObject());
        executed.recordCodexEvent(JsonParser.parseString("""
                {"type":"item.completed","item":{"type":"mcp_tool_call","server":"repo",
                 "tool":"query","arguments":{"q":"repomind weekly"}}}
                """).getAsJsonObject());
        executed.finish("completed", 20, 3);
        JsonObject secondSummary = JsonParser.parseString(
                Files.readString(executed.directory().resolve("summary.json"))).getAsJsonObject();
        assertTrue(secondSummary.get("repomind_runtime_evidence").getAsBoolean());
        assertEquals(2, secondSummary.get("tool_call_count").getAsInt());
    }
}
