package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.FunctionTarget;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Optional;

public final class FunctionFlowLineMarkerProvider implements LineMarkerProvider {
    private static final Icon ICON = IconLoader.getIcon("/icons/codebecause.svg", FunctionFlowLineMarkerProvider.class);

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!FunctionTargetResolver.isFunctionDeclaration(element) || element.getProject().isDisposed()) return null;
        Optional<FunctionTarget> target = FunctionTargetResolver.fromElement(element.getProject(), element);
        if (target.isEmpty()) return null;
        GutterIconNavigationHandler<PsiElement> handler = (event, ignored) ->
                FunctionAnalysisCoordinator.openLatestOrAnalyze(element.getProject(), target.get());
        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                ICON,
                ignored -> PluginText.text("打开函数业务流程", "Open function business flow"),
                handler,
                GutterIconRenderer.Alignment.RIGHT,
                () -> PluginText.text("函数业务流程", "Function business flow")
        );
    }
}
