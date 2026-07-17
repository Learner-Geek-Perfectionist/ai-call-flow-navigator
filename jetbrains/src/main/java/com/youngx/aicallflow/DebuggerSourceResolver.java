package com.youngx.aicallflow;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XSourcePosition;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.uast.UMethod;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Converts a public XDebugger source position into the runtime trace protocol. */
final class DebuggerSourceResolver {
    private DebuggerSourceResolver() {
    }

    static TraceSourcePosition resolve(Project project, XSourcePosition position) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(position, "position");
        VirtualFile file = Objects.requireNonNull(position.getFile(), "position.file");
        int oneBasedLine = position.getLine() + 1;
        if (oneBasedLine < 1) {
            throw new IllegalArgumentException("Debugger source line is unavailable");
        }
        String symbol = resolveSymbol(project, file, position);
        return new TraceSourcePosition(relativePath(project, file), oneBasedLine, symbol);
    }

    private static String resolveSymbol(
            Project project,
            VirtualFile file,
            XSourcePosition position
    ) {
        try {
            return ReadAction.computeBlocking(() -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile == null || psiFile.getTextLength() == 0) {
                    return null;
                }
                int offset = sourceOffset(file, psiFile, position);
                String directSymbol = sourceSymbol(psiFile.findElementAt(offset));
                if (directSymbol == null && offset > 0) {
                    directSymbol = sourceSymbol(psiFile.findElementAt(offset - 1));
                }
                if (directSymbol != null) {
                    return directSymbol;
                }
                UMethod method = CurrentCallableResolver.resolve(
                        psiFile,
                        offset
                );
                return methodSymbol(method);
            });
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException ignored) {
            // Path and line remain useful when PSI is unavailable or temporarily invalid.
            return null;
        }
    }

    private static int sourceOffset(
            VirtualFile file,
            PsiFile psiFile,
            XSourcePosition position
    ) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        int zeroBasedLine = position.getLine();
        if (document != null && zeroBasedLine >= 0 && zeroBasedLine < document.getLineCount()) {
            int offset = document.getLineStartOffset(zeroBasedLine);
            int lineEnd = document.getLineEndOffset(zeroBasedLine);
            CharSequence text = document.getCharsSequence();
            while (offset < lineEnd && Character.isWhitespace(text.charAt(offset))) {
                offset++;
            }
            return Math.min(offset, psiFile.getTextLength() - 1);
        }
        int positionOffset = position.getOffset();
        return positionOffset < 0
                ? 0
                : Math.min(positionOffset, psiFile.getTextLength() - 1);
    }

    private static String relativePath(Project project, VirtualFile file) {
        String filePath = file.getPath();
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return TraceSourcePosition.normalizePath(filePath);
        }
        try {
            Path root = Path.of(basePath).toAbsolutePath().normalize();
            Path source = Path.of(filePath).toAbsolutePath().normalize();
            if (source.startsWith(root)) {
                return TraceSourcePosition.normalizePath(root.relativize(source).toString());
            }
        } catch (InvalidPathException ignored) {
            // Non-local virtual files keep their normalized VFS path.
        }
        return TraceSourcePosition.normalizePath(filePath);
    }

    private static String methodSymbol(UMethod method) {
        if (method == null) {
            return null;
        }
        PsiElement navigation = method.getNavigationElement();
        if (navigation instanceof KtNamedFunction function) {
            return kotlinFunctionSymbol(function);
        }
        PsiMethod psiMethod = method.getJavaPsi();
        return javaMethodSymbol(psiMethod);
    }

    private static String sourceSymbol(PsiElement element) {
        if (element == null) {
            return null;
        }
        KtNamedFunction kotlinFunction = PsiTreeUtil.getParentOfType(
                element,
                KtNamedFunction.class,
                false
        );
        if (kotlinFunction != null) {
            return kotlinFunctionSymbol(kotlinFunction);
        }
        PsiMethod javaMethod = PsiTreeUtil.getParentOfType(
                element,
                PsiMethod.class,
                false
        );
        return javaMethod == null ? null : javaMethodSymbol(javaMethod);
    }

    private static String javaMethodSymbol(PsiMethod psiMethod) {
        PsiClass owner = psiMethod.getContainingClass();
        String ownerName = owner == null ? null : owner.getQualifiedName();
        return ownerName == null || ownerName.isBlank()
                ? psiMethod.getName()
                : ownerName + "." + psiMethod.getName();
    }

    private static String kotlinFunctionSymbol(KtNamedFunction function) {
        List<String> owners = new ArrayList<>();
        KtClassOrObject owner = PsiTreeUtil.getParentOfType(function, KtClassOrObject.class);
        while (owner != null) {
            if (owner.getName() != null) {
                owners.add(owner.getName());
            }
            owner = PsiTreeUtil.getParentOfType(owner, KtClassOrObject.class, true);
        }
        Collections.reverse(owners);
        String packageName = function.getContainingKtFile().getPackageFqName().asString();
        List<String> segments = new ArrayList<>();
        if (!packageName.isBlank()) {
            segments.add(packageName);
        }
        segments.addAll(owners);
        segments.add(function.getName() == null ? "anonymous" : function.getName());
        return String.join(".", segments);
    }
}
