package com.archscope.jetbrains.analysis;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class LocalCliModelClient implements ModelClient {
    private static final Logger LOG = Logger.getInstance(LocalCliModelClient.class);
    private static final Duration MAX_RUNTIME = Duration.ofMinutes(30);

    @Override
    public String id() {
        return "codex-local";
    }

    @Override
    public String displayName() {
        return "本机 Codex";
    }

    public String complete(
            String systemPrompt,
            String userPrompt,
            Path workingDirectory,
            ProgressIndicator indicator
    ) throws ModelClientException {
        return complete(systemPrompt, userPrompt, workingDirectory, indicator, "分析代码变化", ignored -> {});
    }

    public String complete(
            String systemPrompt,
            String userPrompt,
            Path workingDirectory,
            ProgressIndicator indicator,
            String stage
    ) throws ModelClientException {
        return complete(systemPrompt, userPrompt, workingDirectory, indicator, stage, ignored -> {});
    }

    public String complete(
            String systemPrompt,
            String userPrompt,
            Path workingDirectory,
            ProgressIndicator indicator,
            String stage,
            Consumer<String> statusListener
    ) throws ModelClientException {
        return complete(
                systemPrompt,
                userPrompt,
                workingDirectory,
                indicator,
                stage,
                statusListener,
                WorkspaceAccess.CLOSED_EVIDENCE
        );
    }

    @Override
    public String complete(
            String systemPrompt,
            String userPrompt,
            Path workingDirectory,
            ProgressIndicator indicator,
            String stage,
            Consumer<String> statusListener,
            WorkspaceAccess workspaceAccess
    ) throws ModelClientException {
        Process process = null;
        long startedAt = System.nanoTime();
        try {
            List<String> command = command(stage, workspaceAccess);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true);
            process = builder.start();
            updateProgress(indicator, statusListener, "本机 Codex · " + stage);
            LOG.info("Local Codex started: stage=" + stage + ", executable=" + command.get(0));

            String prompt = systemPrompt + "\n\nUSER EVIDENCE REQUEST:\n" + userPrompt;
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(prompt);
            }

            Process runningProcess = process;
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                    () -> readOutput(runningProcess, indicator, stage, statusListener)
            );
            long deadline = System.nanoTime() + MAX_RUNTIME.toNanos();
            long lastProgressSecond = -5;
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (indicator.isCanceled()) {
                    terminate(process);
                    indicator.checkCanceled();
                }
                if (System.nanoTime() > deadline) {
                    terminate(process);
                    throw new ModelClientException("本机 Codex 执行超过 30 分钟，已终止");
                }
                long elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000;
                if (elapsedSeconds >= lastProgressSecond + 5) {
                    updateProgress(indicator, statusListener, "本机 Codex · " + stage + " · " + elapsedSeconds + " 秒");
                    lastProgressSecond = elapsedSeconds;
                }
            }
            String output = outputFuture.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new ModelClientException(
                        "本机 Codex 执行失败（exit " + process.exitValue() + "）："
                                + abbreviate(output, 2400)
                );
            }
            String result = extractResult(output);
            LOG.info("Local Codex completed: stage=" + stage + ", outputChars=" + result.length());
            LOG.info("Local Codex timing: stage=" + stage + ", promptChars=" + prompt.length()
                    + ", elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000));
            return result;
        } catch (ModelClientException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) terminate(process);
            throw new ModelClientException("本机模型 CLI 请求被中断", exception);
        } catch (ExecutionException | TimeoutException exception) {
            if (process != null) terminate(process);
            throw new ModelClientException("读取本机模型 CLI 输出失败：" + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new ModelClientException(
                    "无法启动本机 Codex：" + exception.getMessage()
                            + "。请先确认 Codex CLI 已安装、已登录并能在终端中运行。",
                    exception
            );
        } finally {
            if (process != null && process.isAlive()) terminate(process);
        }
    }

    List<String> command() {
        return command("分析代码变化");
    }

    List<String> command(String stage) {
        return command(stage, WorkspaceAccess.CLOSED_EVIDENCE);
    }

    List<String> command(String stage, WorkspaceAccess workspaceAccess) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                Path script = Path.of(appData, "npm", "node_modules", "@openai", "codex", "bin", "codex.js");
                if (Files.isRegularFile(script)) {
                    return codexCommand(List.of("node", script.toString()), stage, workspaceAccess);
                }
            }
        }
        return codexCommand(List.of("codex"), stage, workspaceAccess);
    }

    private List<String> codexCommand(List<String> prefix, String stage, WorkspaceAccess workspaceAccess) {
        List<String> command = new ArrayList<>(prefix);
        String reasoningEffort = isStructuredBusinessStage(stage) ? "low" : "medium";
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");
        if (workspaceAccess == WorkspaceAccess.READ_ONLY_REPOSITORY) {
            command.addAll(List.of("--sandbox", "read-only"));
        }
        command.addAll(List.of(
                "-c",
                "model_reasoning_effort=\"" + reasoningEffort + "\"",
                "--json",
                "-"
        ));
        return List.copyOf(command);
    }

    private boolean isStructuredBusinessStage(String stage) {
        return stage.contains("识别")
                || stage.contains("业务理解报告")
                || stage.contains("补充业务报告")
                || stage.contains("增量更新业务报告")
                || stage.contains("收敛待确认项");
    }

    String extractResult(String output) throws ModelClientException {
        String assistantText = "";
        for (String line : output.lines().toList()) {
            if (!line.stripLeading().startsWith("{")) continue;
            try {
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                String type = string(event, "type");
                if ("item.completed".equals(type)) {
                    JsonObject item = event.getAsJsonObject("item");
                    if (item != null && "agent_message".equals(string(item, "type")) && item.has("text")) {
                        assistantText = item.get("text").getAsString();
                    }
                }
            } catch (RuntimeException ignored) {
                // CLI can print startup notices alongside JSONL; only valid model events matter.
            }
        }
        if (!assistantText.isBlank()) return assistantText;
        throw new ModelClientException("本机 Codex 没有返回可解析的最终文本：" + abbreviate(output, 1800));
    }

    private String readOutput(
            Process process,
            ProgressIndicator indicator,
            String stage,
            Consumer<String> statusListener
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            StringBuilder output = new StringBuilder();
            String line;
            int events = 0;
            int toolCalls = 0;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (line.stripLeading().startsWith("{")) {
                    events++;
                    try {
                        JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                        JsonObject item = event.has("item") && event.get("item").isJsonObject()
                                ? event.getAsJsonObject("item")
                                : null;
                        String itemType = string(item, "type");
                        if ("command_execution".equals(itemType) || "mcp_tool_call".equals(itemType)) toolCalls++;
                    } catch (RuntimeException ignored) {
                        // Startup notices and future event variants do not affect progress.
                    }
                    updateProgress(indicator, statusListener, "本机 Codex · " + stage + " · 事件 " + events);
                }
            }
            LOG.info("Local Codex event summary: stage=" + stage + ", events=" + events + ", toolCalls=" + toolCalls);
            return output.toString();
        } catch (IOException exception) {
            throw new CliOutputRuntimeException(exception);
        }
    }

    private void terminate(Process process) {
        process.descendants().forEach(child -> {
            try {
                child.destroy();
            } catch (RuntimeException ignored) {
                // The child may have already exited.
            }
        });
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(child -> {
                    try {
                        child.destroyForcibly();
                    } catch (RuntimeException ignored) {
                        // The child may have already exited.
                    }
                });
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void updateProgress(
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            String message
    ) {
        indicator.setText(message);
        statusListener.accept(message);
    }

    private String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString()
                : "";
    }

    private String abbreviate(String value, int maxLength) {
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength) + "...";
    }

    private static final class CliOutputRuntimeException extends RuntimeException {
        private CliOutputRuntimeException(Throwable cause) {
            super(cause);
        }
    }
}
