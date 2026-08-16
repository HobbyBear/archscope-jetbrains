package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.git.GitCli;
import com.archscope.jetbrains.git.GitCommandException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightVirtualFile;

import java.nio.file.Path;
import java.util.Comparator;

final class HistoricalSourceOpener {
    private HistoricalSourceOpener() {
    }

    static void open(Project project, Path repositoryRoot, String sourceJson) {
        JsonObject source;
        try {
            source = JsonParser.parseString(sourceJson).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }
        String path = source.has("path") ? source.get("path").getAsString() : "";
        String commit = source.has("commit") ? source.get("commit").getAsString() : "";
        String symbol = source.has("symbol") ? source.get("symbol").getAsString() : "";
        int line = source.has("line") ? Math.max(1, source.get("line").getAsInt()) : 1;
        if (path.isBlank()) return;

        if (!commit.isBlank()) {
            openHistoricalSnapshot(project, repositoryRoot, path, commit, line, symbol);
            return;
        }
        ApplicationManager.getApplication().invokeLater(
                () -> openWorkingTree(project, repositoryRoot, path, line, symbol));
    }

    private static void openHistoricalSnapshot(
            Project project,
            Path repositoryRoot,
            String path,
            String commit,
            int line,
            String symbol
    ) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String content = new GitCli(repositoryRoot).run(null, "show", commit + ":" + path);
                ApplicationManager.getApplication().invokeLater(
                        () -> openSnapshot(project, path, commit, line, symbol, content)
                );
            } catch (GitCommandException ignored) {
                // A snapshot link must never silently navigate to a different working-tree version.
            }
        });
    }

    private static void openSnapshot(
            Project project,
            String path,
            String commit,
            int line,
            String symbol,
            String content
    ) {
        String shortCommit = commit.length() > 10 ? commit.substring(0, 10) : commit;
        LightVirtualFile file = new LightVirtualFile(
                path + " @ " + shortCommit,
                FileTypeManager.getInstance().getFileTypeByFileName(path),
                content
        );
        file.setWritable(false);
        navigateWithPsi(project, file, line, symbol);
    }

    private static boolean openWorkingTree(
            Project project,
            Path repositoryRoot,
            String path,
            int line,
            String symbol
    ) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repositoryRoot.resolve(path));
        if (file == null || file.isDirectory()) return false;

        return navigateWithPsi(project, file, line, symbol);
    }

    private static boolean navigateWithPsi(
            Project project,
            VirtualFile file,
            int line,
            String symbol
    ) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) return false;
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        Document psiDocument = documentManager.getDocument(psiFile);
        Document document = psiDocument != null ? psiDocument : FileDocumentManager.getInstance().getDocument(file);
        if (psiDocument != null) documentManager.commitDocument(psiDocument);
        PsiElement target = ReadAction.compute(() -> findPsiTarget(psiFile, document, line, symbol));
        if (target == null || !target.isValid()) return false;
        int offset = ReadAction.compute(target::getTextOffset);
        Navigatable navigatable = PsiNavigationSupport.getInstance().createNavigatable(project, file, offset);
        if (!navigatable.canNavigate()) return false;
        navigatable.navigate(true);
        return true;
    }

    static PsiElement findPsiTarget(PsiFile psiFile, Document document, int line, String symbol) {
        if (document == null || document.getLineCount() == 0) return psiFile;
        int lineIndex = Math.min(Math.max(0, line - 1), document.getLineCount() - 1);
        int offset = firstContentOffset(document, lineIndex);
        String expectedName = normalizeSymbol(symbol);
        if (!expectedName.isEmpty()) {
            PsiNamedElement named = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement.class).stream()
                    .filter(element -> expectedName.equals(normalizeSymbol(element.getName())))
                    .min(Comparator
                            .comparingInt((PsiNamedElement element) -> element.getTextRange().containsOffset(offset) ? 0 : 1)
                            .thenComparingInt(element -> Math.abs(element.getTextOffset() - offset)))
                    .orElse(null);
            if (named == null) return null;
            if (named.getTextRange().containsOffset(offset)) {
                PsiElement anchor = psiFile.findElementAt(Math.min(offset, Math.max(0, psiFile.getTextLength() - 1)));
                if (anchor != null && PsiTreeUtil.isAncestor(named, anchor, false)) return anchor;
            }
            return named;
        }
        PsiElement element = psiFile.findElementAt(Math.min(offset, Math.max(0, psiFile.getTextLength() - 1)));
        return element == null ? psiFile : element;
    }

    private static int firstContentOffset(Document document, int lineIndex) {
        int start = document.getLineStartOffset(lineIndex);
        int end = document.getLineEndOffset(lineIndex);
        CharSequence chars = document.getCharsSequence();
        while (start < end && Character.isWhitespace(chars.charAt(start))) start++;
        return start;
    }

    static String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String normalized = symbol.strip();
        int receiver = normalized.indexOf(").");
        if (normalized.startsWith("(") && receiver >= 0) normalized = normalized.substring(receiver + 2);
        int parameters = normalized.indexOf('(');
        if (parameters >= 0) normalized = normalized.substring(0, parameters);
        int separator = Math.max(normalized.lastIndexOf('.'), normalized.lastIndexOf('#'));
        if (separator >= 0) normalized = normalized.substring(separator + 1);
        return normalized.strip();
    }
}
