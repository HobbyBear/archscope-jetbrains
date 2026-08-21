package com.archscope.jetbrains.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CliCommandResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void honorsAnExplicitCliPath() throws Exception {
        Path executable = temporaryDirectory.resolve("codex-custom");
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        assertTrue(executable.toFile().setExecutable(true));
        System.setProperty("archscope.testCliPath", executable.toString());
        try {
            assertEquals(List.of(executable.toAbsolutePath().normalize().toString()),
                    CliCommandResolver.resolve("missing-cli", "archscope.testCliPath", "MISSING_CLI_PATH"));
        } finally {
            System.clearProperty("archscope.testCliPath");
        }
    }

    @Test
    void suppliesACompleteExternalCliEnvironment() {
        ProcessBuilder builder = new ProcessBuilder("ignored");
        builder.environment().put("LD_LIBRARY_PATH", "ide-native");
        builder.environment().put("DYLD_LIBRARY_PATH", "ide-native");

        CliCommandResolver.configureEnvironment(builder);

        assertTrue(builder.environment().get("PATH").contains("/usr/bin"));
        assertFalse(builder.environment().containsKey("LD_LIBRARY_PATH"));
        assertFalse(builder.environment().containsKey("DYLD_LIBRARY_PATH"));
    }
}
