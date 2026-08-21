package com.archscope.jetbrains.model;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record FunctionTarget(
        Path repositoryRoot,
        String relativeFile,
        String symbol,
        String signature,
        int startLine,
        int endLine
) {
    public FunctionTarget {
        repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        relativeFile = normalizeRelativePath(relativeFile);
        symbol = symbol == null ? "" : symbol.strip();
        signature = signature == null ? "" : signature.strip();
        startLine = Math.max(1, startLine);
        endLine = Math.max(startLine, endLine);
        if (relativeFile.isBlank() || symbol.isBlank()) {
            throw new IllegalArgumentException("Function target requires a repository-relative file and symbol");
        }
    }

    public String stableId() {
        String identity = relativeFile + "\n" + symbol;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String displayName() {
        return symbol + " · " + relativeFile;
    }

    private static String normalizeRelativePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/').strip();
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }
}
