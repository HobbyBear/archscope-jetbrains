package com.archscope.jetbrains.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class FunctionAnalysisRunRegistry {
    private static final Map<Project, List<FunctionAnalysisRunDialog>> ACTIVE = new WeakHashMap<>();

    private FunctionAnalysisRunRegistry() {
    }

    static void register(Project project, FunctionAnalysisRunDialog dialog) {
        synchronized (FunctionAnalysisRunRegistry.class) {
            ACTIVE.computeIfAbsent(project, ignored -> new ArrayList<>()).add(dialog);
        }
        publish(project);
    }

    static void unregister(Project project, FunctionAnalysisRunDialog dialog) {
        synchronized (FunctionAnalysisRunRegistry.class) {
            List<FunctionAnalysisRunDialog> dialogs = ACTIVE.get(project);
            if (dialogs != null) {
                dialogs.remove(dialog);
                if (dialogs.isEmpty()) ACTIVE.remove(project);
            }
        }
        publish(project);
    }

    static synchronized boolean hasActive(Project project) {
        return active(project).size() > 0;
    }

    static synchronized int activeCount(Project project) {
        return active(project).size();
    }

    static void showActive(Project project) {
        showActive(project, null);
    }

    static void showActive(Project project, @Nullable JComponent anchor) {
        Runnable action = () -> {
            List<FunctionAnalysisRunDialog> dialogs = active(project);
            if (dialogs.isEmpty()) return;
            if (dialogs.size() == 1) {
                dialogs.get(0).showAgain();
                return;
            }
            var popup = JBPopupFactory.getInstance().createPopupChooserBuilder(dialogs)
                    .setTitle(PluginText.text("选择实时分析任务", "Select a live analysis"))
                    .setRenderer(new ColoredListCellRenderer<>() {
                        @Override
                        protected void customizeCellRenderer(
                                @NotNull JList<? extends FunctionAnalysisRunDialog> list,
                                FunctionAnalysisRunDialog value,
                                int index,
                                boolean selected,
                                boolean hasFocus
                        ) {
                            append(value.taskName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                            append("  " + value.taskStatus(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                        }
                    })
                    .setItemChosenCallback(FunctionAnalysisRunDialog::showAgain)
                    .createPopup();
            if (anchor != null && anchor.isShowing()) popup.showUnderneathOf(anchor);
            else popup.showInFocusCenter();
        };
        if (ApplicationManager.getApplication().isDispatchThread()) action.run();
        else ApplicationManager.getApplication().invokeLater(action);
    }

    private static synchronized List<FunctionAnalysisRunDialog> active(Project project) {
        List<FunctionAnalysisRunDialog> dialogs = ACTIVE.get(project);
        if (dialogs == null) return List.of();
        List<FunctionAnalysisRunDialog> result = new ArrayList<>();
        for (int index = dialogs.size() - 1; index >= 0; index--) {
            FunctionAnalysisRunDialog dialog = dialogs.get(index);
            if (isActive(dialog)) result.add(dialog);
        }
        return List.copyOf(result);
    }

    private static boolean isActive(FunctionAnalysisRunDialog dialog) {
        return dialog != null && !dialog.isDisposed() && dialog.isRunning();
    }

    private static void publish(Project project) {
        if (!project.isDisposed()) project.getMessageBus().syncPublisher(AnalysisRunListener.TOPIC).activeRunChanged();
    }
}
