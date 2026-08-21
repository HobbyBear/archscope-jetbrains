package com.archscope.jetbrains.ui;

import com.intellij.util.messages.Topic;

interface AnalysisRunListener {
    Topic<AnalysisRunListener> TOPIC = Topic.create("CodeBecause live analysis", AnalysisRunListener.class);

    void activeRunChanged();
}
