package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.FunctionTarget;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public final class AnalyzeFunctionFlowAction extends AnAction implements DumbAware {
    @Override
    public void update(@NotNull AnActionEvent event) {
        if (event.getProject() == null) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        Optional<FunctionTarget> target = FunctionTargetResolver.resolve(
                event.getProject(),
                event.getData(CommonDataKeys.EDITOR),
                event.getData(CommonDataKeys.PSI_FILE)
        );
        event.getPresentation().setEnabledAndVisible(target.isPresent());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() == null) return;
        FunctionTargetResolver.resolve(
                event.getProject(),
                event.getData(CommonDataKeys.EDITOR),
                event.getData(CommonDataKeys.PSI_FILE)
        ).ifPresent(target -> FunctionAnalysisCoordinator.configureAndAnalyze(event.getProject(), target));
    }
}
