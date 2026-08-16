package com.archscope.jetbrains.ui;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.LightVirtualFile;

public final class HistoricalSourceOpenerPsiTest extends LightPlatformTestCase {
    public void testKeepsAnInternalLineAsThePsiAnchor() {
        PsiFile file = psiFile("""
                {
                  "review": {
                    "prepare": true,
                    "validateResult": true,
                    "publish": true
                  }
                }
                """);
        Document document = document(file);

        PsiElement target = HistoricalSourceOpener.findPsiTarget(file, document, 4, "Service#review()");

        assertNotNull(target);
        assertEquals(3, document.getLineNumber(target.getTextOffset()));
        assertTrue(target.getTextRange().getStartOffset() > file.getText().indexOf("\"review\""));
    }

    public void testDoesNotFallBackToANearbyDeclarationWhenSymbolIsMissing() {
        PsiFile file = psiFile("""
                {
                  "first": true,
                  "second": true
                }
                """);

        assertNull(HistoricalSourceOpener.findPsiTarget(file, document(file), 3, "Service#deleted()"));
    }

    public void testUsesTheRecordedLineToDisambiguateOverloadsWithinOneFile() {
        PsiFile file = psiFile("""
                {
                  "publish": {"kind": "string"},
                  "other": true,
                  "publish": {
                    "kind": "long",
                    "audit": true
                  }
                }
                """);
        Document document = document(file);

        PsiElement target = HistoricalSourceOpener.findPsiTarget(file, document, 6, "Service#publish(long)");

        assertNotNull(target);
        assertEquals(5, document.getLineNumber(target.getTextOffset()));
    }

    private PsiFile psiFile(String text) {
        LightVirtualFile file = new LightVirtualFile(
                "service.json", FileTypeManager.getInstance().getFileTypeByExtension("json"), text);
        PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(file);
        assertNotNull(psiFile);
        return psiFile;
    }

    private Document document(PsiFile file) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        if (document == null) document = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        assertNotNull(document);
        return document;
    }
}
