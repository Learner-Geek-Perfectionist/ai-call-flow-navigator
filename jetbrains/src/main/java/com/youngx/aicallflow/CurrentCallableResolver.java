package com.youngx.aicallflow;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastContextKt;

/** Resolves the source function containing an editor caret. Must run in a read action. */
final class CurrentCallableResolver {
    private CurrentCallableResolver() {
    }

    static UMethod resolve(PsiFile file, int caretOffset) {
        if (file == null || file.getTextLength() == 0) {
            return null;
        }
        int offset = Math.max(0, Math.min(caretOffset, file.getTextLength() - 1));
        PsiElement element = file.findElementAt(offset);
        UMethod method = parentMethod(element);
        if (method == null && offset > 0) {
            method = parentMethod(file.findElementAt(offset - 1));
        }
        return method;
    }

    private static UMethod parentMethod(PsiElement element) {
        return element == null
                ? null
                : UastContextKt.getUastParentOfType(element, UMethod.class);
    }
}
