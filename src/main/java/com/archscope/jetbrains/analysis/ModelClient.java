package com.archscope.jetbrains.analysis;

import com.intellij.openapi.progress.ProgressIndicator;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface ModelClient {
    String id();

    String displayName();

    default String cacheIdentity() {
        return id();
    }

    String complete(
            String systemPrompt,
            String userPrompt,
            Path workingDirectory,
            ProgressIndicator indicator,
            String stage,
            Consumer<String> statusListener,
            WorkspaceAccess workspaceAccess
    ) throws ModelClientException;

    default String complete(
            String systemPrompt,
            String userPrompt,
            Path workingDirectory,
            ProgressIndicator indicator,
            String stage,
            Consumer<String> statusListener,
            WorkspaceAccess workspaceAccess,
            Consumer<ModelStreamEvent> streamListener
    ) throws ModelClientException {
        return complete(systemPrompt, userPrompt, workingDirectory, indicator, stage, statusListener, workspaceAccess);
    }

    enum WorkspaceAccess {
        CURRENT_REPOSITORY,
        READ_ONLY_REPOSITORY,
        BOUNDED_EVIDENCE
    }
}
