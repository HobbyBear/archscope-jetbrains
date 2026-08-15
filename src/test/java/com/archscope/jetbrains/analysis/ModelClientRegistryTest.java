package com.archscope.jetbrains.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelClientRegistryTest {
    @Test
    void discoversTheBundledProviderThroughTheProviderContract() {
        assertTrue(ModelClientRegistry.available().stream().anyMatch(client -> "codex-local".equals(client.id())));
        assertTrue(ModelClientRegistry.available().stream().anyMatch(client -> "claude-local".equals(client.id())));
        assertEquals("codex-local", ModelClientRegistry.selected().id());
        assertEquals("claude-local", ModelClientRegistry.selected("claude-local").id());
    }
}
