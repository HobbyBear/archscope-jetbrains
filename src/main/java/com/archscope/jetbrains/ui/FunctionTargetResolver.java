package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.model.FunctionTarget;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FunctionTargetResolver {
    private static final Pattern GO_METHOD = Pattern.compile(
            "(?s)^\\s*func\\s*\\(\\s*\\w+\\s+\\*?([\\w.]+)[^)]*\\)\\s*([\\w]+)");

    private FunctionTargetResolver() {
    }

    static Optional<FunctionTarget> resolve(Project project, Editor editor, PsiFile file) {
        if (editor == null || file == null) return Optional.empty();
        PsiElement leaf = file.findElementAt(editor.getCaretModel().getOffset());
        PsiElement declaration = containingFunction(leaf);
        return fromElement(project, declaration, file, editor.getDocument());
    }

    static Optional<FunctionTarget> fromElement(Project project, PsiElement element) {
        if (element == null) return Optional.empty();
        PsiFile file = element.getContainingFile();
        if (file == null) return Optional.empty();
        Document document = file.getViewProvider().getDocument();
        return fromElement(project, element, file, document);
    }

    static boolean isFunctionDeclaration(PsiElement element) {
        if (!(element instanceof PsiNamedElement named) || named.getName() == null) return false;
        String type = element.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (type.contains("call") || type.contains("expression") || type.contains("type")) return false;
        return type.contains("functiondeclaration")
                || type.contains("methoddeclaration")
                || type.equals("psimethodimpl")
                || type.equals("psimethod");
    }

    private static Optional<FunctionTarget> fromElement(
            Project project,
            PsiElement element,
            PsiFile file,
            Document document
    ) {
        if (!isFunctionDeclaration(element) || document == null) return Optional.empty();
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) return Optional.empty();
        Path source = Path.of(virtualFile.getPath()).toAbsolutePath().normalize();
        Path root = repositoryRoot(project, virtualFile, source);
        if (root == null || !source.startsWith(root)) return Optional.empty();
        String relative = root.relativize(source).toString().replace('\\', '/');
        String text = element.getText() == null ? "" : element.getText();
        String signature = signature(text);
        String name = ((PsiNamedElement) element).getName();
        String symbol = qualifiedSymbol(signature, name == null ? "" : name);
        int start = document.getLineNumber(element.getTextRange().getStartOffset()) + 1;
        int endOffset = Math.max(element.getTextRange().getStartOffset(), element.getTextRange().getEndOffset() - 1);
        int end = document.getLineNumber(endOffset) + 1;
        return Optional.of(new FunctionTarget(root, relative, symbol, signature, start, end));
    }

    private static PsiElement containingFunction(PsiElement element) {
        PsiElement current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (isFunctionDeclaration(current)) return current;
            current = current.getParent();
        }
        return null;
    }

    private static Path repositoryRoot(Project project, VirtualFile sourceFile, Path source) {
        VirtualFile vcsRoot = ProjectLevelVcsManager.getInstance(project).getVcsRootFor(sourceFile);
        if (vcsRoot != null) return Path.of(vcsRoot.getPath()).toAbsolutePath().normalize();
        Path current = source.getParent();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return current.toAbsolutePath().normalize();
            current = current.getParent();
        }
        String basePath = project.getBasePath();
        return basePath == null ? null : Path.of(basePath).toAbsolutePath().normalize();
    }

    private static String signature(String text) {
        String compact = text == null ? "" : text.stripLeading();
        int body = compact.indexOf('{');
        if (body >= 0) compact = compact.substring(0, body);
        int newline = compact.indexOf('\n');
        if (newline >= 0 && body < 0) compact = compact.substring(0, newline);
        compact = compact.replaceAll("\\s+", " ").strip();
        return compact.length() > 320 ? compact.substring(0, 320) : compact;
    }

    private static String qualifiedSymbol(String signature, String fallback) {
        Matcher method = GO_METHOD.matcher(signature);
        if (method.find()) return method.group(1) + "." + method.group(2);
        return fallback;
    }
}
