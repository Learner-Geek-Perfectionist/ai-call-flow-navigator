package com.youngx.aicallflow;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-platform coverage for the XSourcePosition to trace protocol boundary. */
final class DebuggerSourceResolverIntegrationTest extends LightJavaCodeInsightFixtureTestCase5 {
    @Override
    protected String getTestDataPath() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void resolvesNormalizedPathOneBasedLineAndEnclosingJavaMethod() throws Exception {
        String source = """
                package sample;

                class Sample {
                    void target() {
                        System.out.println("pause here");
                    }
                }
                """;
        PsiFile psiFile = getFixture().addFileToProject("src/sample/Sample.java", source);
        assertInstanceOf(PsiJavaFile.class, psiFile);
        VirtualFile sourceFile = psiFile.getVirtualFile();
        assertNotNull(ReadAction.compute(() -> PsiManager.getInstance(
                getFixture().getProject()
        ).findFile(sourceFile)));
        int statementOffset = source.indexOf("System.out.println");
        PsiMethod enclosingMethod = ReadAction.compute(() -> PsiTreeUtil.getParentOfType(
                psiFile.findElementAt(statementOffset),
                PsiMethod.class,
                false
        ));
        assertNotNull(enclosingMethod);
        assertEquals("target", ReadAction.compute(enclosingMethod::getName));

        PsiMethod lineMethod = ReadAction.compute(() -> {
            PsiFile managerFile = PsiManager.getInstance(getFixture().getProject())
                    .findFile(sourceFile);
            Document document = FileDocumentManager.getInstance().getDocument(sourceFile);
            int offset = document.getLineStartOffset(4);
            int end = document.getLineEndOffset(4);
            while (offset < end && Character.isWhitespace(document.getCharsSequence().charAt(offset))) {
                offset++;
            }
            return PsiTreeUtil.getParentOfType(
                    managerFile.findElementAt(offset),
                    PsiMethod.class,
                    false
            );
        });
        assertNotNull(lineMethod);
        assertEquals("target", ReadAction.compute(lineMethod::getName));

        int zeroBasedLine = 4;
        XSourcePosition position = XDebuggerUtil.getInstance()
                .createPosition(sourceFile, zeroBasedLine);

        TraceSourcePosition resolved = DebuggerSourceResolver.resolve(
                getFixture().getProject(),
                position
        );

        assertTrue(resolved.path().endsWith("src/sample/Sample.java"));
        assertEquals(5, resolved.line());
        assertEquals("sample.Sample.target", resolved.symbol());
    }
}
