package com.archscope.jetbrains.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record AnalysisGuidance(
        String systemPrompt
) {
    public static final AnalysisGuidance EMPTY = new AnalysisGuidance("");

    public AnalysisGuidance {
        // Both supported CLIs receive this through their native high-priority instruction channel.
        // Keep the combined Windows command line below CreateProcess' limit.
        systemPrompt = normalize(systemPrompt, 4_000);
    }

    public boolean isEmpty() {
        return systemPrompt.isBlank();
    }

    public String fingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(systemPrompt.getBytes(StandardCharsets.UTF_8));
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
