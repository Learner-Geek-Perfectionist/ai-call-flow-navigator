package com.youngx.aicallflow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Opens and highlights call-flow locations inside the current project. */
public final class CallFlowNavigator implements Disposable {
    private static final int ANCHOR_SEARCH_RADIUS_LINES = 20;

    public enum AnchorMatch {
        NOT_REQUESTED,
        EXACT,
        RELOCATED,
        NOT_FOUND,
        AMBIGUOUS
    }

    public record NavigationResult(
            int line,
            int column,
            AnchorMatch anchorMatch
    ) {
    }

    private final Project project;
    private RangeHighlighter activeHighlighter;

    public CallFlowNavigator(@NotNull Project project) {
        this.project = project;
    }

    /** Must be called on the IntelliJ event-dispatch thread. */
    public NavigationResult navigate(@NotNull CallFlowNode node) {
        return navigate(node, true);
    }

    /** Must be called on the IntelliJ event-dispatch thread. */
    NavigationResult navigate(@NotNull CallFlowNode node, boolean focusEditor) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        clearHighlight();
        CallFlowLocation location = node.location();
        if (location == null) {
            throw new IllegalArgumentException("Node has no source location: " + node.id());
        }

        Path absolutePath = resolveExistingProjectFile(location.path());
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolutePath);
        if (file == null || file.isDirectory()) {
            throw new IllegalArgumentException("Source file not found: " + location.path());
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            throw new IllegalArgumentException("Source file cannot be opened as text: " + location.path());
        }

        ResolvedLocation resolved = resolveLocation(document, location);
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, resolved.startOffset());
        Editor editor = FileEditorManager.getInstance(project).openTextEditor(
                descriptor,
                focusEditor
        );
        if (editor == null) {
            throw new IllegalArgumentException("No text editor is available for: " + location.path());
        }

        TextAttributes attributes = editor.getColorsScheme().getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES);
        if (attributes == null) {
            attributes = new TextAttributes();
        } else {
            attributes = attributes.clone();
        }
        activeHighlighter = editor.getMarkupModel().addRangeHighlighter(
                resolved.startOffset(),
                resolved.endOffset(),
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
        );
        editor.getCaretModel().moveToOffset(resolved.startOffset());
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);

        int resolvedLine = document.getLineNumber(resolved.startOffset()) + 1;
        int resolvedColumn = resolved.startOffset()
                - document.getLineStartOffset(resolvedLine - 1)
                + 1;
        return new NavigationResult(
                resolvedLine,
                resolvedColumn,
                resolved.anchorMatch()
        );
    }

    /** Verifies every node against the project that Android Studio actually opened. */
    void validateLocations(@NotNull CallFlow flow) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (project.isDisposed()) {
            throw new IllegalStateException("Android Studio project is disposed");
        }

        Map<Path, Document> documents = new HashMap<>();
        for (CallFlowNode node : flow.nodes()) {
            CallFlowLocation location = node.location();
            try {
                if (location == null) {
                    throw new IllegalArgumentException("Node has no source location");
                }
                Path absolutePath = resolveExistingProjectFile(location.path());
                Document document = documents.get(absolutePath);
                if (document == null) {
                    document = sourceDocument(absolutePath, location.path());
                    documents.put(absolutePath, document);
                }
                resolveLocation(document, location);
            } catch (RuntimeException error) {
                String detail = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
                throw new IllegalArgumentException(
                        "Call Flow node " + node.id() + " is not navigable: " + detail,
                        error
                );
            }
        }
    }

    @Override
    public void dispose() {
        clearHighlight();
    }

    private Path resolveExistingProjectFile(String relativePath) {
        Path root = project.getService(AiCallFlowProjectService.class).projectRoot();
        Path candidate = ProjectPathResolver.resolveInsideRoot(root, relativePath);
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("Source file not found: " + relativePath);
        }

        try {
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw new IllegalArgumentException("Source path escapes the project root: " + relativePath);
            }
            return realCandidate;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot resolve source file: " + relativePath, error);
        }
    }

    private Document sourceDocument(Path absolutePath, String relativePath) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolutePath);
        if (file == null || file.isDirectory()) {
            throw new IllegalArgumentException("Source file not found: " + relativePath);
        }
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            throw new IllegalArgumentException("Source file cannot be opened as text: " + relativePath);
        }
        return document;
    }

    static ResolvedLocation resolveLocation(Document document, CallFlowLocation location) {
        String anchorText = location.anchorText();

        if (anchorText != null && !anchorText.isBlank()) {
            boolean requestedLineExists = location.line() <= document.getLineCount();
            int requestedLineIndex = requestedLineExists
                    ? location.line() - 1
                    : document.getLineCount() - 1;
            int requestedOffset = requestedLineExists
                    ? offsetOrMinusOne(document, requestedLineIndex, location.column())
                    : -1;
            AnchorSearch search = findUniqueNearbyAnchor(document, requestedLineIndex, anchorText);
            if (search.matches() == 1) {
                int anchorOffset = search.offset();
                AnchorMatch match = anchorOffset == requestedOffset
                        ? AnchorMatch.EXACT
                        : AnchorMatch.RELOCATED;
                return new ResolvedLocation(anchorOffset, anchorOffset + anchorText.length(), match);
            }

            requestedLineIndex = checkedLineIndex(document, location.line());
            int fallbackOffset = checkedOffset(document, requestedLineIndex, location.column());
            int fallbackEnd = fallbackEndOffset(document, location, fallbackOffset);
            AnchorMatch match = search.matches() == 0
                    ? AnchorMatch.NOT_FOUND
                    : AnchorMatch.AMBIGUOUS;
            return new ResolvedLocation(fallbackOffset, fallbackEnd, match);
        }

        int requestedLineIndex = checkedLineIndex(document, location.line());
        int startOffset = checkedOffset(document, requestedLineIndex, location.column());
        return new ResolvedLocation(
                startOffset,
                fallbackEndOffset(document, location, startOffset),
                AnchorMatch.NOT_REQUESTED
        );
    }

    private static AnchorSearch findUniqueNearbyAnchor(
            Document document,
            int requestedLineIndex,
            String anchorText
    ) {
        int firstLine = Math.max(0, requestedLineIndex - ANCHOR_SEARCH_RADIUS_LINES);
        int lastLine = Math.min(
                document.getLineCount() - 1,
                requestedLineIndex + ANCHOR_SEARCH_RADIUS_LINES
        );
        int searchStart = document.getLineStartOffset(firstLine);
        int searchEnd = document.getLineEndOffset(lastLine);
        String nearbyText = document.getCharsSequence().subSequence(searchStart, searchEnd).toString();

        int matches = 0;
        int matchOffset = -1;
        int fromIndex = 0;
        while (fromIndex <= nearbyText.length() - anchorText.length()) {
            int index = nearbyText.indexOf(anchorText, fromIndex);
            if (index < 0) {
                break;
            }
            matches++;
            matchOffset = searchStart + index;
            if (matches > 1) {
                break;
            }
            fromIndex = index + 1;
        }
        return new AnchorSearch(matchOffset, matches);
    }

    private static int fallbackEndOffset(
            Document document,
            CallFlowLocation location,
            int startOffset
    ) {
        if (location.endLine() != null && location.endColumn() != null) {
            int endLineIndex = checkedLineIndex(document, location.endLine());
            int endOffset = checkedOffset(document, endLineIndex, location.endColumn());
            if (endOffset < startOffset) {
                throw new IllegalArgumentException("Source range ends before it starts");
            }
            return endOffset;
        }
        return Math.min(document.getTextLength(), startOffset + 1);
    }

    private static int checkedLineIndex(Document document, int oneBasedLine) {
        if (oneBasedLine < 1 || oneBasedLine > document.getLineCount()) {
            throw new IllegalArgumentException(
                    "Line " + oneBasedLine + " is outside the source document"
            );
        }
        return oneBasedLine - 1;
    }

    private static int checkedOffset(Document document, int lineIndex, int oneBasedColumn) {
        int offset = offsetOrMinusOne(document, lineIndex, oneBasedColumn);
        if (offset < 0) {
            throw new IllegalArgumentException(
                    "Column " + oneBasedColumn + " is outside source line " + (lineIndex + 1)
            );
        }
        return offset;
    }

    private static int offsetOrMinusOne(Document document, int lineIndex, int oneBasedColumn) {
        if (oneBasedColumn < 1) {
            return -1;
        }
        int lineStart = document.getLineStartOffset(lineIndex);
        int lineEnd = document.getLineEndOffset(lineIndex);
        int offset = lineStart + oneBasedColumn - 1;
        return offset <= lineEnd ? offset : -1;
    }

    private void clearHighlight() {
        if (activeHighlighter != null) {
            activeHighlighter.dispose();
            activeHighlighter = null;
        }
    }

    record ResolvedLocation(int startOffset, int endOffset, AnchorMatch anchorMatch) {
    }

    private record AnchorSearch(int offset, int matches) {
    }
}
