package com.archscope.jetbrains.ui;

import com.intellij.util.messages.Topic;

import java.nio.file.Path;

interface ReportHistoryListener {
    Topic<ReportHistoryListener> TOPIC = Topic.create("CodeBecause report history", ReportHistoryListener.class);

    void reportsChanged(Path repositoryRoot);
}
