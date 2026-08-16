package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.i18n.PluginLanguage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

public final class ClaudeCliModelClient implements ModelClient {
    private static final Logger LOG = Logger.getInstance(ClaudeCliModelClient.class);
    private static final Duration MAX_RUNTIME = Duration.ofMinutes(30);

    @Override
    public String id() {
        return "claude-local";
    }

    @Override
    public String displayName() {
        return "Claude CLI";
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
            List<String> command = command(workspaceAccess);
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            updateProgress(indicator, statusListener, "Claude CLI · " + stage);
            String prompt = systemPrompt + "\n\nUSER EVIDENCE REQUEST:\n" + userPrompt;
            Process runningProcess = process;
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                    () -> readOutput(runningProcess, indicator, stage, statusListener)
            );
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(prompt);
            } catch (IOException exception) {
                if (!process.waitFor(1, TimeUnit.SECONDS)) throw exception;
                String output = outputFuture.get(5, TimeUnit.SECONDS);
                throw new ModelClientException(
                        PluginLanguage.text("Claude CLI 执行失败（exit ", "Claude CLI failed (exit ")
                                + process.exitValue() + PluginLanguage.text("）：", "): ") + failureMessage(output),
                        exception
                );
            }

            long deadline = System.nanoTime() + MAX_RUNTIME.toNanos();
            long lastProgressSecond = -5;
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (indicator.isCanceled()) {
                    terminate(process);
                    indicator.checkCanceled();
                }
                if (System.nanoTime() > deadline) {
                    terminate(process);
                    throw new ModelClientException(PluginLanguage.text("Claude CLI 执行超过 30 分钟，已终止",
                            "Claude CLI exceeded 30 minutes and was terminated"));
                }
                long elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000;
                if (elapsedSeconds >= lastProgressSecond + 5) {
                    updateProgress(indicator, statusListener,
                            "Claude CLI · " + stage + " · " + elapsedSeconds + PluginLanguage.text(" 秒", "s"));
                    lastProgressSecond = elapsedSeconds;
                }
            }
            String output = outputFuture.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new ModelClientException(
                        PluginLanguage.text("Claude CLI 执行失败（exit ", "Claude CLI failed (exit ")
                                + process.exitValue() + PluginLanguage.text("）：", "): ") + failureMessage(output)
                );
            }
            String result = extractResult(output);
            LOG.info("Claude CLI timing: stage=" + stage + ", promptChars=" + prompt.length()
                    + ", elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000));
            return result;
        } catch (ModelClientException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) terminate(process);
            throw new ModelClientException(PluginLanguage.text("Claude CLI 请求被中断",
                    "The Claude CLI request was interrupted"), exception);
        } catch (ExecutionException | TimeoutException exception) {
            if (process != null) terminate(process);
            throw new ModelClientException(PluginLanguage.text("读取 Claude CLI 输出失败：",
                    "Failed to read Claude CLI output: ") + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new ModelClientException(
                    PluginLanguage.text("无法启动 Claude CLI：", "Could not start Claude CLI: ") + exception.getMessage()
                            + PluginLanguage.text("。请先确认 Claude CLI 已安装、已登录并能在终端中运行。",
                            ". Make sure Claude CLI is installed, signed in, and runs in a terminal."),
                    exception
            );
        } finally {
            if (process != null && process.isAlive()) terminate(process);
        }
    }

    List<String> command(WorkspaceAccess workspaceAccess) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> prefix = List.of("claude");
        if (windows) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                Path script = Path.of(appData, "npm", "node_modules", "@anthropic-ai", "claude-code", "cli.js");
                if (Files.isRegularFile(script)) prefix = List.of("node", script.toString());
            }
        }
        List<String> command = new ArrayList<>(prefix);
        command.addAll(List.of(
                "-p",
                "--output-format", "stream-json",
                "--verbose",
                "--no-session-persistence"
        ));
        return List.copyOf(command);
    }

    String extractResult(String output) throws ModelClientException {
        String assistantText = "";
        for (String line : output.lines().toList()) {
            if (!line.stripLeading().startsWith("{")) continue;
            try {
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                if ("result".equals(string(event, "type")) && !booleanValue(event, "is_error")) {
                    String result = string(event, "result");
                    if (!result.isBlank()) return result;
                }
                if (!"assistant".equals(string(event, "type"))) continue;
                JsonObject message = object(event, "message");
                JsonArray content = message == null ? null : array(message, "content");
                if (content == null) continue;
                for (JsonElement item : content) {
                    if (!item.isJsonObject()) continue;
                    JsonObject block = item.getAsJsonObject();
                    if ("text".equals(string(block, "type")) && !string(block, "text").isBlank()) {
                        assistantText = string(block, "text");
                    }
                }
            } catch (RuntimeException ignored) {
                // Startup notices and future event variants do not affect the final result.
            }
        }
        if (!assistantText.isBlank()) return assistantText;
        throw new ModelClientException(PluginLanguage.text("Claude CLI 没有返回可解析的最终文本：",
                "Claude CLI did not return parseable final text: ") + abbreviate(output, 1800));
    }

    String failureMessage(String output) {
        String structured = "";
        for (String line : output.lines().toList()) {
            if (!line.stripLeading().startsWith("{")) continue;
            try {
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                if (!"result".equals(string(event, "type")) || !booleanValue(event, "is_error")) continue;
                String result = string(event, "result");
                if (!result.isBlank()) {
                    structured = result;
                } else if (event.has("errors")) {
                    structured = event.get("errors").toString();
                } else {
                    structured = string(event, "subtype");
                }
            } catch (RuntimeException ignored) {
                // Non-JSON notices are handled by the tail fallback below.
            }
        }
        return structured.isBlank() ? abbreviateTail(output, 2400) : abbreviate(structured, 2400);
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
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (line.stripLeading().startsWith("{")) {
                    events++;
                    updateProgress(indicator, statusListener, "Claude CLI · " + stage
                            + PluginLanguage.text(" · 事件 ", " · event ") + events);
                }
            }
            return output.toString();
        } catch (IOException exception) {
            throw new CliOutputRuntimeException(exception);
        }
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
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
        message = PluginLanguage.userMessage(message);
        indicator.setText(message);
        statusListener.accept(message);
    }

    private JsonObject object(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonObject()
                ? owner.getAsJsonObject(name) : null;
    }

    private JsonArray array(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonArray()
                ? owner.getAsJsonArray(name) : null;
    }

    private String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private boolean booleanValue(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && object.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String abbreviate(String value, int maxLength) {
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength) + "...";
    }

    private String abbreviateTail(String value, int maxLength) {
        String stripped = value.strip();
        return stripped.length() <= maxLength
                ? stripped
                : "... output truncated ...\n" + stripped.substring(stripped.length() - maxLength);
    }

    private static final class CliOutputRuntimeException extends RuntimeException {
        private CliOutputRuntimeException(Throwable cause) {
            super(cause);
        }
    }
}
