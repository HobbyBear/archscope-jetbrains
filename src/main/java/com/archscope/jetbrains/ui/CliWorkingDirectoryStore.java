package com.archscope.jetbrains.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;

final class CliWorkingDirectoryStore {
    private static final String KEY = "archscope.analysis.cliWorkingDirectory";
    private final PropertiesComponent properties;

    CliWorkingDirectoryStore(Project project) {
        properties = PropertiesComponent.getInstance(project);
    }

    String load(Path defaultDirectory) {
        String stored = properties.getValue(KEY);
        if (stored != null && !stored.isBlank()) return stored;
        return defaultDirectory == null ? "" : defaultDirectory.toAbsolutePath().normalize().toString();
    }

    void save(String directory) {
        properties.setValue(KEY, directory == null ? "" : directory.strip(), "");
    }
}
