package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.SensitiveTextSanitizer;

public record ModelStreamEvent(Kind kind, String title, String content) {
    public ModelStreamEvent {
        title = SensitiveTextSanitizer.redact(title == null ? "" : title);
        content = SensitiveTextSanitizer.redact(content == null ? "" : content);
    }

    public static ModelStreamEvent status(String content) {
        return new ModelStreamEvent(Kind.STATUS, "", content);
    }

    public static ModelStreamEvent reasoning(String content) {
        return new ModelStreamEvent(Kind.REASONING, "", content);
    }

    public static ModelStreamEvent toolCall(String title, String content) {
        return new ModelStreamEvent(Kind.TOOL_CALL, title, content);
    }

    public static ModelStreamEvent toolResult(String title, String content) {
        return new ModelStreamEvent(Kind.TOOL_RESULT, title, content);
    }

    public static ModelStreamEvent response(String content) {
        return new ModelStreamEvent(Kind.RESPONSE, "", content);
    }

    public static ModelStreamEvent error(String content) {
        return new ModelStreamEvent(Kind.ERROR, "", content);
    }

    public enum Kind {
        STATUS,
        REASONING,
        TOOL_CALL,
        TOOL_RESULT,
        RESPONSE,
        ERROR
    }
}
