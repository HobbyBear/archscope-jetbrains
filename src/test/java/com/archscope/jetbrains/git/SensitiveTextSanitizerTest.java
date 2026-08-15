package com.archscope.jetbrains.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SensitiveTextSanitizerTest {
    @Test
    void recognizesSensitivePathsBeforeReadingContent() {
        assertTrue(SensitiveTextSanitizer.isSensitivePath(".env"));
        assertTrue(SensitiveTextSanitizer.isSensitivePath("config/.env.production"));
        assertTrue(SensitiveTextSanitizer.isSensitivePath("certs/client.p12"));
        assertTrue(SensitiveTextSanitizer.isSensitivePath("deploy/secrets/token.json"));
        assertTrue(SensitiveTextSanitizer.isSensitivePath("secrets/token.json"));
        assertTrue(SensitiveTextSanitizer.isSensitivePath(".secrets/token.json"));
        assertFalse(SensitiveTextSanitizer.isSensitivePath("src/main/App.java"));
    }

    @Test
    void redactsCommonCredentialShapes() {
        String input = "api_key=sk-abcdefghijklmnopqrstuvwxyz\npassword: super-secret-value\n";
        String redacted = SensitiveTextSanitizer.redact(input);
        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz"));
        assertFalse(redacted.contains("super-secret-value"));
        assertTrue(redacted.contains("[REDACTED_SECRET]"));
    }
}
