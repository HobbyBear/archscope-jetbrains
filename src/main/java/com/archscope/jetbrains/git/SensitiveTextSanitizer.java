package com.archscope.jetbrains.git;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SensitiveTextSanitizer {
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password|passwd)\\s*[:=]\\s*['\\\"]?[^'\\\"\\s,;]{6,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\bgh[opsu]_[A-Za-z0-9]{20,}\\b"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")
    );

    private SensitiveTextSanitizer() {
    }

    public static boolean isSensitivePath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String rooted = "/" + normalized;
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.equals(".env")
                || name.startsWith(".env.")
                || name.equals("credentials")
                || name.equals("credentials.json")
                || name.equals("id_rsa")
                || name.equals("id_ed25519")
                || rooted.contains("/.secrets/")
                || rooted.contains("/secrets/")
                || normalized.endsWith(".pem")
                || normalized.endsWith(".key")
                || normalized.endsWith(".p12")
                || normalized.endsWith(".pfx")
                || normalized.endsWith(".keystore");
    }

    public static boolean looksBinary(String content) {
        return content.indexOf('\0') >= 0;
    }

    public static String redact(String content) {
        String sanitized = content;
        for (Pattern pattern : SECRET_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("[REDACTED_SECRET]");
        }
        return sanitized;
    }
}
