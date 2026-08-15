package com.archscope.jetbrains.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record AnalysisGuidance(
        String customInstructions,
        String additionalSystemPrompt
) {
    public static final AnalysisGuidance EMPTY = new AnalysisGuidance("", "");

    public AnalysisGuidance {
        customInstructions = normalize(customInstructions, 16_000);
        additionalSystemPrompt = normalize(additionalSystemPrompt, 8_000);
    }

    public boolean isEmpty() {
        return customInstructions.isBlank() && additionalSystemPrompt.isBlank();
    }

    public String fingerprint() {
        try {
            String value = String.join("\n\u001f\n", customInstructions, additionalSystemPrompt);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalize(String value, int maxChars) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }
}
