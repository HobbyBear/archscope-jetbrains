package com.archscope.jetbrains.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class ArchitectureToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ArchitectureToolWindowPanel panel = new ArchitectureToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel.component(), "", false);
        Disposer.register(content, panel);
        toolWindow.getContentManager().addContent(content);
    }
}

