package com.youngx.aicallflow;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UIdentifier;
import org.jetbrains.uast.UMethod;

import java.nio.file.Path;

/** Converts physical PSI ranges into exact project-relative Call Flow locations. */
final class PsiCallFlowLocationFactory {
    private static final int MAX_ANCHOR_LENGTH = 512;

    private final Project project;
    private final Path projectRoot;

    PsiCallFlowLocationFactory(Project project) {
        this.project = project;
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalStateException("Current project has no local root");
        }
        projectRoot = Path.of(basePath).toAbsolutePath().normalize();
    }

    CallFlowLocation forMethod(UMethod method, String symbol) {
        UElement anchor = method.getUastAnchor();
        PsiElement source = anchor == null ? null : anchor.getSourcePsi();
        if (source == null) {
            source = method.getSourcePsi();
        }
        return forElement(source, symbol);
    }

    CallFlowLocation forElement(UElement element, String symbol) {
        PsiElement source = null;
        if (element instanceof UIdentifier identifier) {
            source = identifier.getSourcePsi();
        }
        if (source == null && element != null) {
            source = element.getSourcePsi();
        }
        return forElement(source, symbol);
    }

    CallFlowLocation forElement(PsiElement source, String symbol) {
        if (source == null || source.getContainingFile() == null) {
            throw new IllegalArgumentException("Static analysis element has no physical source");
        }
        TextRange range = source.getTextRange();
        if (range == null) {
            throw new IllegalArgumentException("Static analysis element has no source range");
        }
        return location(
                source.getContainingFile(),
                range.getStartOffset(),
                Math.max(range.getStartOffset() + 1, range.getEndOffset()),
                symbol,
                true
        );
    }

    CallFlowLocation forScopeExit(UElement body, String symbol) {
        PsiElement source = body == null ? null : body.getSourcePsi();
        if (source == null || source.getContainingFile() == null || source.getTextRange() == null) {
            throw new IllegalArgumentException("Static analysis scope has no physical source");
        }
        TextRange range = source.getTextRange();
        Document document = document(source.getContainingFile());
        int offset = Math.min(range.getEndOffset() - 1, document.getTextLength() - 1);
        while (offset > range.getStartOffset()
                && Character.isWhitespace(document.getCharsSequence().charAt(offset))) {
            offset--;
        }
        return location(source.getContainingFile(), offset, Math.min(offset + 1, document.getTextLength()),
                symbol, false);
    }

    boolean isProjectSource(UMethod method) {
        PsiElement source = method == null ? null : method.getSourcePsi();
        if (source == null && method != null) {
            source = method.getNavigationElement();
        }
        PsiFile file = source == null ? null : source.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) {
            return false;
        }
        Path path = Path.of(virtualFile.getPath()).toAbsolutePath().normalize();
        return path.startsWith(projectRoot);
    }

    private CallFlowLocation location(
            PsiFile file,
            int startOffset,
            int endOffset,
            String symbol,
            boolean includeAnchor
    ) {
        Document document = document(file);
        if (startOffset < 0 || startOffset >= document.getTextLength()) {
            throw new IllegalArgumentException("Static analysis source offset is outside the document");
        }
        int safeEnd = Math.max(startOffset + 1, Math.min(endOffset, document.getTextLength()));
        int startLineIndex = document.getLineNumber(startOffset);
        int endLineIndex = document.getLineNumber(safeEnd);
        int startColumn = startOffset - document.getLineStartOffset(startLineIndex) + 1;
        int endColumn = safeEnd - document.getLineStartOffset(endLineIndex) + 1;

        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null || !virtualFile.isInLocalFileSystem()) {
            throw new IllegalArgumentException("Static analysis source is not a local project file");
        }
        Path sourcePath = Path.of(virtualFile.getPath()).toAbsolutePath().normalize();
        if (!sourcePath.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Static analysis source is outside the current project");
        }
        String relativePath = projectRoot.relativize(sourcePath).toString().replace('\\', '/');

        String anchor = includeAnchor ? lineAnchor(document, startOffset) : null;
        return new CallFlowLocation(
                relativePath,
                startLineIndex + 1,
                startColumn,
                endLineIndex + 1,
                endColumn,
                symbol,
                anchor
        );
    }

    private Document document(PsiFile file) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            throw new IllegalArgumentException("Static analysis source cannot be opened as text");
        }
        return document;
    }

    private static String lineAnchor(Document document, int startOffset) {
        int line = document.getLineNumber(startOffset);
        int lineEnd = document.getLineEndOffset(line);
        String value = document.getCharsSequence()
                .subSequence(startOffset, Math.min(lineEnd, startOffset + MAX_ANCHOR_LENGTH))
                .toString()
                .stripTrailing();
        return value.isBlank() ? null : value;
    }
}
