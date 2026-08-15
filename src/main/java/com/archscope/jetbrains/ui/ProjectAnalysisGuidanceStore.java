package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.AnalysisGuidance;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;

final class ProjectAnalysisGuidanceStore {
    private static final String PREFIX = "archscope.analysis.guidance.";
    private final PropertiesComponent properties;

    ProjectAnalysisGuidanceStore(Project project) {
        properties = PropertiesComponent.getInstance(project);
    }

    AnalysisGuidance load(Path repositoryRoot) {
        String custom = properties.getValue(PREFIX + "customPrompt", "");
        if (custom.isBlank()) {
            String context = properties.getValue(PREFIX + "businessContext", "").strip();
            String reading = properties.getValue(PREFIX + "codeReading", "").strip();
            custom = context.isBlank() ? reading : reading.isBlank() ? context : context + "\n\n" + reading;
        }
        String system = properties.getValue(PREFIX + "systemPrompt", "");
        return new AnalysisGuidance(custom, system);
    }

    void save(String custom, String system) {
        properties.setValue(PREFIX + "customPrompt", custom, "");
        properties.setValue(PREFIX + "systemPrompt", system, "");
    }
}
