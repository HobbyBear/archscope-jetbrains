package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.git.GitCli;
import com.archscope.jetbrains.git.GitCommandException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;

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
        String originCommit = source.has("originCommit") ? source.get("originCommit").getAsString() : "";
        String symbol = source.has("symbol") ? source.get("symbol").getAsString() : "";
        int line = source.has("line") ? Math.max(1, source.get("line").getAsInt()) : 1;
        if (path.isBlank()) return;
        Path sourceRoot = locateSourceRoot(repositoryRoot, path);

        if (originCommit.isBlank()) {
            ApplicationManager.getApplication().invokeLater(
                    () -> openWorkingTree(project, sourceRoot, path, line, symbol));
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String currentPath = resolveCurrentPath(sourceRoot, path, originCommit);
            ApplicationManager.getApplication().invokeLater(
                    () -> openWorkingTree(project, sourceRoot, currentPath, line, symbol));
        });
    }

    static Path locateSourceRoot(Path repositoryRoot, String path) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path current = root;
        for (int level = 0; current != null && level < 8; level++, current = current.getParent()) {
            Path candidate = current.resolve(path).normalize();
            if (candidate.startsWith(current) && java.nio.file.Files.isRegularFile(candidate)) return current;
        }
        return root;
    }

    private static String resolveCurrentPath(Path repositoryRoot, String path, String originCommit) {
        if (java.nio.file.Files.isRegularFile(repositoryRoot.resolve(path).normalize())) return path;
        try {
            String changes = new GitCli(repositoryRoot).run(
                    null, "diff", "--name-status", "--find-renames", originCommit, "--"
            );
            return renamedPath(changes, path);
        } catch (GitCommandException ignored) {
            return path;
        }
    }

    static String renamedPath(String nameStatus, String originalPath) {
        for (String line : nameStatus.lines().toList()) {
            String[] fields = line.split("\\t");
            if (fields.length >= 3 && (fields[0].startsWith("R") || fields[0].startsWith("C"))
                    && originalPath.equals(fields[1])) {
                return fields[2];
            }
        }
        return originalPath;
    }

    private static boolean openWorkingTree(
            Project project,
            Path repositoryRoot,
            String path,
            int line,
            String symbol
    ) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) return false;
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
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
        Document fileDocument = FileDocumentManager.getInstance().getDocument(file);
        if (psiFile == null) return navigateToOffset(project, file, lineOffset(fileDocument, line));
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        Document psiDocument = documentManager.getDocument(psiFile);
        Document document = psiDocument != null ? psiDocument : fileDocument;
        if (psiDocument != null) documentManager.commitDocument(psiDocument);
        PsiElement target = ReadAction.compute(() -> findPsiTarget(psiFile, document, line, symbol));
        int offset = target != null && target.isValid()
                ? ReadAction.compute(target::getTextOffset)
                : lineOffset(document, line);
        return navigateToOffset(project, file, offset);
    }

    private static boolean navigateToOffset(Project project, VirtualFile file, int offset) {
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, Math.max(0, offset));
        if (!descriptor.canNavigate()) return false;
        descriptor.navigate(true);
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
            if (named == null) return elementAtLine(psiFile, offset);
            if (named.getTextRange().containsOffset(offset)) {
                PsiElement anchor = psiFile.findElementAt(Math.min(offset, Math.max(0, psiFile.getTextLength() - 1)));
                if (anchor != null && PsiTreeUtil.isAncestor(named, anchor, false)) return anchor;
            }
            return named;
        }
        return elementAtLine(psiFile, offset);
    }

    private static PsiElement elementAtLine(PsiFile psiFile, int offset) {
        PsiElement element = psiFile.findElementAt(Math.min(offset, Math.max(0, psiFile.getTextLength() - 1)));
        return element == null ? psiFile : element;
    }

    private static int lineOffset(Document document, int line) {
        if (document == null || document.getLineCount() == 0) return 0;
        int lineIndex = Math.min(Math.max(0, line - 1), document.getLineCount() - 1);
        return firstContentOffset(document, lineIndex);
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
