package com.archscope.jetbrains.git;

import com.archscope.jetbrains.i18n.PluginLanguage;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GitCli {
    private static final int COMMAND_OUTPUT_LIMIT = 16 * 1024 * 1024;

    private final Path workingDirectory;

    public GitCli(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public Path findRepositoryRoot(ProgressIndicator indicator) throws GitCommandException {
        String output = run(indicator, "rev-parse", "--show-toplevel").trim();
        if (output.isEmpty()) {
            throw new GitCommandException(PluginLanguage.text("当前项目不在 Git 仓库中",
                    "The current project is not inside a Git repository"));
        }
        return Path.of(output).toAbsolutePath().normalize();
    }

    public String run(ProgressIndicator indicator, String... arguments) throws GitCommandException {
        CommandResult result = execute(indicator, arguments);
        if (result.exitCode() != 0) {
            throw new GitCommandException(PluginLanguage.text("Git 命令失败 (", "Git command failed (")
                    + result.exitCode() + PluginLanguage.text(")：", "): ") + abbreviate(result.output(), 2000));
        }
        return result.output();
    }

    public boolean isAncestor(ProgressIndicator indicator, String ancestor, String descendant) throws GitCommandException {
        CommandResult result = execute(indicator, "merge-base", "--is-ancestor", ancestor, descendant);
        if (result.exitCode() == 0) return true;
        if (result.exitCode() == 1) return false;
        throw new GitCommandException(PluginLanguage.text("无法判断提交祖先关系：",
                "Could not determine the commit ancestry: ") + abbreviate(result.output(), 1200));
    }

    public String grep(ProgressIndicator indicator, String revision, String literal) throws GitCommandException {
        CommandResult result = execute(
                indicator,
                "grep", "-n", "-I", "-F", "-e", literal, revision, "--"
        );
        if (result.exitCode() == 0) return result.output();
        if (result.exitCode() == 1) return "";
        throw new GitCommandException(PluginLanguage.text("Git 搜索失败：", "Git search failed: ")
                + abbreviate(result.output(), 1200));
    }

    private CommandResult execute(ProgressIndicator indicator, String... arguments) throws GitCommandException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(workingDirectory.toString());
        command.addAll(List.of(arguments));

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            throw new GitCommandException(PluginLanguage.text("无法启动 Git，请确认 git 已安装并位于 PATH：",
                    "Could not start Git. Make sure Git is installed and available on PATH: ") + exception.getMessage());
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        try (InputStream input = process.getInputStream()) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (indicator != null && indicator.isCanceled()) {
                    process.destroyForcibly();
                    throw new ProcessCanceledException();
                }
                int writable = Math.min(count, Math.max(0, COMMAND_OUTPUT_LIMIT - total));
                if (writable > 0) {
                    captured.write(buffer, 0, writable);
                    total += writable;
                }
            }
            int exitCode = process.waitFor();
            String output = captured.toString(StandardCharsets.UTF_8);
            return new CommandResult(exitCode, output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitCommandException(PluginLanguage.text("Git 命令被中断", "The Git command was interrupted"));
        } catch (IOException exception) {
            process.destroyForcibly();
            throw new GitCommandException(PluginLanguage.text("读取 Git 输出失败:", "Failed to read Git output: ")
                    + exception.getMessage());
        }
    }

    private record CommandResult(int exitCode, String output) {
    }

    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n... output truncated ...";
    }
}
