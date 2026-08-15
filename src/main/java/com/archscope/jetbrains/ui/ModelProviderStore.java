package com.archscope.jetbrains.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

final class ModelProviderStore {
    private static final String KEY = "archscope.model.provider";
    private final PropertiesComponent properties;

    ModelProviderStore(Project project) {
        properties = PropertiesComponent.getInstance(project);
    }

    String load() {
        String provider = properties.getValue(KEY, "codex-local");
        return "claude-local".equals(provider) ? provider : "codex-local";
    }

    void save(String provider) {
        properties.setValue(KEY, "claude-local".equals(provider) ? provider : "codex-local", "codex-local");
    }
}
