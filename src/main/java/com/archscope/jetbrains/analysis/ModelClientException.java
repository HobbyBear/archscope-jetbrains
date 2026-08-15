package com.archscope.jetbrains.analysis;

public final class ModelClientException extends Exception {
    public ModelClientException(String message) {
        super(message);
    }

    public ModelClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

