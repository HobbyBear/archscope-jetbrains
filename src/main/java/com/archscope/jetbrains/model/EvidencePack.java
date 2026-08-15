package com.archscope.jetbrains.model;

import java.nio.file.Path;
import java.util.List;

public record EvidencePack(
        Path repositoryRoot,
        String headCommit,
        String baseCommit,
        String targetCommit,
        String targetTree,
        String fingerprint,
        List<CommitEvidence> commits,
        String aggregateNameStatus,
        List<String> aggregateChangedPaths,
        List<String> targetManifest
) {
    public EvidencePack(
            Path repositoryRoot,
            String headCommit,
            String targetCommit,
            String targetTree,
            String fingerprint,
            List<CommitEvidence> commits,
            List<String> targetManifest
    ) {
        this(
                repositoryRoot,
                headCommit,
                commits.isEmpty() ? "" : commits.get(0).baseCommit(),
                targetCommit,
                targetTree,
                fingerprint,
                commits,
                commits.stream().map(CommitEvidence::nameStatus).collect(java.util.stream.Collectors.joining("\n")),
                commits.stream().flatMap(item -> item.changedPaths().stream()).distinct().toList(),
                targetManifest
        );
    }

    public record CommitEvidence(
            CommitInfo commit,
            String baseCommit,
            String nameStatus,
            List<String> changedPaths
    ) {
    }
}
