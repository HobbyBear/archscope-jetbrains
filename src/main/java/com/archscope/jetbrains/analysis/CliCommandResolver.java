package com.archscope.jetbrains.analysis;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.EnvironmentUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class CliCommandResolver {
    private static final Logger LOG = Logger.getInstance(CliCommandResolver.class);
    private static final Duration SHELL_LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private CliCommandResolver() {
    }

    static List<String> resolve(String executable, String propertyName, String environmentName) {
        String override = firstNonBlank(System.getProperty(propertyName), System.getenv(environmentName));
        if (override != null) {
            Path configured = Path.of(override).toAbsolutePath().normalize();
            if (Files.isRegularFile(configured)) return List.of(configured.toString());
            LOG.warn("Configured CLI executable does not exist: " + configured);
        }
        Path located = locateOnPath(executable, effectivePath());
        if (located != null) return List.of(located.toString());
        Path shellLocated = locateWithLoginShell(executable);
        return shellLocated == null ? List.of(executable) : List.of(shellLocated.toString());
    }

    static void configureEnvironment(ProcessBuilder builder) {
        builder.environment().put("PATH", effectivePath());
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.environment().remove("DYLD_LIBRARY_PATH");
    }

    static String effectivePath() {
        Set<String> entries = new LinkedHashSet<>();
        addPath(entries, EnvironmentUtil.getValue("PATH"));
        addPath(entries, System.getenv("PATH"));
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        add(entries, Path.of("/opt/homebrew/bin"));
        add(entries, Path.of("/usr/local/bin"));
        add(entries, home.resolve(".local/bin"));
        add(entries, home.resolve(".npm-global/bin"));
        add(entries, home.resolve(".volta/bin"));
        add(entries, home.resolve(".bun/bin"));
        add(entries, home.resolve(".asdf/shims"));
        add(entries, home.resolve(".local/share/mise/shims"));
        addVersionBins(entries, home.resolve(".nvm/versions/node"), "bin");
        addVersionBins(entries, home.resolve(".local/share/fnm/node-versions"), "installation/bin");
        add(entries, Path.of("/usr/bin"));
        add(entries, Path.of("/bin"));
        add(entries, Path.of("/usr/sbin"));
        add(entries, Path.of("/sbin"));
        return String.join(System.getProperty("path.separator", ":"), entries);
    }

    private static Path locateOnPath(String executable, String pathValue) {
        for (String entry : pathValue.split(Pattern.quote(System.getProperty("path.separator", ":")))) {
            if (entry.isBlank()) continue;
            try {
                Path candidate = Path.of(entry).resolve(executable).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
            } catch (RuntimeException ignored) {
                // Ignore malformed PATH entries supplied by a shell plugin.
            }
        }
        return null;
    }

    private static Path locateWithLoginShell(String executable) {
        if (!isMac()) return null;
        Path shell = preferredShell();
        if (shell == null) return null;
        Process process = null;
        try {
            process = new ProcessBuilder(shell.toString(), "-lic", "command -v " + executable)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(SHELL_LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (output.isBlank()) return null;
            Path candidate = Path.of(output.lines().findFirst().orElse("")).toAbsolutePath().normalize();
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("Could not resolve " + executable + " through the macOS login shell", exception);
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static Path preferredShell() {
        String configured = System.getenv("SHELL");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured);
            if (Files.isExecutable(candidate)) return candidate;
        }
        for (Path candidate : List.of(Path.of("/bin/zsh"), Path.of("/bin/bash"))) {
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static void addPath(Set<String> entries, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) return;
        for (String entry : pathValue.split(Pattern.quote(System.getProperty("path.separator", ":")))) {
            if (!entry.isBlank()) entries.add(entry);
        }
    }

    private static void add(Set<String> entries, Path path) {
        if (Files.isDirectory(path)) entries.add(path.toAbsolutePath().normalize().toString());
    }

    private static void addVersionBins(Set<String> entries, Path versions, String suffix) {
        if (!Files.isDirectory(versions)) return;
        try (var stream = Files.list(versions)) {
            for (Path value : stream.sorted((left, right) -> right.toString().compareTo(left.toString())).toList()) {
                add(entries, value.resolve(suffix));
            }
        } catch (IOException exception) {
            LOG.debug("Could not inspect CLI version directory " + versions, exception);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return null;
    }
}
