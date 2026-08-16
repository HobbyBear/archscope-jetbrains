package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.i18n.PluginLanguage;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

import java.util.Locale;

final class OutputLanguageStore {
    private static final String KEY = "archscope.output.language";
    private final PropertiesComponent applicationProperties;
    private final PropertiesComponent projectProperties;

    OutputLanguageStore(Project project) {
        applicationProperties = PropertiesComponent.getInstance();
        projectProperties = PropertiesComponent.getInstance(project);
    }

    AnalysisRequest.OutputLanguage load() {
        String fallback = Locale.getDefault().getLanguage().equals(Locale.CHINESE.getLanguage()) ? "zh-CN" : "en";
        String stored = applicationProperties.getValue(KEY);
        if (stored == null) stored = projectProperties.getValue(KEY, fallback);
        AnalysisRequest.OutputLanguage language = "en".equals(stored)
                ? AnalysisRequest.OutputLanguage.ENGLISH
                : AnalysisRequest.OutputLanguage.CHINESE;
        PluginLanguage.use(language);
        return language;
    }

    void save(AnalysisRequest.OutputLanguage language) {
        applicationProperties.setValue(KEY, language.code());
        projectProperties.setValue(KEY, language.code());
        PluginLanguage.use(language);
    }
}
