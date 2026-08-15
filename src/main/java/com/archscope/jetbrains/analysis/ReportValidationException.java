package com.archscope.jetbrains.analysis;

import java.util.List;

public final class ReportValidationException extends Exception {
    private final List<String> errors;

    public ReportValidationException(List<String> errors) {
        super("架构报告校验失败：\n- " + String.join("\n- ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}

