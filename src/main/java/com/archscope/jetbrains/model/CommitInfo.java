package com.archscope.jetbrains.model;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

public record CommitInfo(
        String hash,
        List<String> parents,
        String author,
        String authoredAt,
        String subject
) {
    public String shortHash() {
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
    }

    public OffsetDateTime parsedAuthoredAt() {
        try {
            return OffsetDateTime.parse(authoredAt);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.MIN;
        }
    }
}

