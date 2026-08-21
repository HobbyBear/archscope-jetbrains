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
        String system = properties.getValue(PREFIX + "systemPrompt", "").strip();
        if (system.isBlank()) {
            String custom = properties.getValue(PREFIX + "customPrompt", "").strip();
            String context = properties.getValue(PREFIX + "businessContext", "").strip();
            String reading = properties.getValue(PREFIX + "codeReading", "").strip();
            String legacy = context.isBlank() ? reading : reading.isBlank() ? context : context + "\n\n" + reading;
            system = custom.isBlank() ? legacy : custom;
        }
        return new AnalysisGuidance(system);
    }

    void save(String system) {
        properties.setValue(PREFIX + "systemPrompt", system, "");
        properties.setValue(PREFIX + "customPrompt", "", "");
        properties.setValue(PREFIX + "businessContext", "", "");
        properties.setValue(PREFIX + "codeReading", "", "");
    }
}
