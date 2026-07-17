package com.youngx.aicallflow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import org.jetbrains.uast.UMethod;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Kotlin UAST recovery when an unrelated trailing top-level token is malformed. */
final class UastMalformedTailIntegrationTest extends LightJavaCodeInsightFixtureTestCase5 {
    private static final String SOURCE_PATH = "src/malformed/TrailingError.kt";
    private static final String SOURCE = """
            package malformed

            fun onCreate() {
                helper()
            }

            fun helper() {
            }，
            """;

    @Override
    protected String getTestDataPath() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void resolvesAndGeneratesBeforeUnrelatedTrailingTopLevelError() throws Exception {
        installKotlinStdlib();
        PsiFile sourceFile = installProjectSource();
        ApplicationManager.getApplication().invokeAndWait(
                () -> PsiDocumentManager.getInstance(getFixture().getProject())
                        .commitAllDocuments()
        );
        DumbService.getInstance(getFixture().getProject()).waitForSmartMode();

        List<String> errors = ReadAction.compute(() -> PsiTreeUtil.findChildrenOfType(
                        sourceFile,
                        PsiErrorElement.class
                ).stream()
                .map(PsiErrorElement::getErrorDescription)
                .toList());
        assertFalse(errors.isEmpty(), "the full-width comma must remain a real PSI syntax error");
        assertTrue(
                errors.stream().anyMatch(error -> error.contains(
                        "Expecting a top level declaration"
                )),
                () -> "unexpected PSI errors: " + errors
        );

        CallFlow flow = ReadAction.compute(() -> {
            UMethod method = CurrentCallableResolver.resolve(
                    sourceFile,
                    SOURCE.indexOf("helper()")
            );
            assertNotNull(method, "the complete onCreate function must survive PSI recovery");
            assertEquals("onCreate", method.getName());
            assertNotNull(method.getUastBody());
            return new UastCallFlowGenerator(getFixture().getProject()).generate(method);
        });

        CallFlowNode entry = node(flow, flow.entry());
        assertEquals(NodeKind.ENTRY, entry.kind());
        assertEquals(SOURCE_PATH, entry.location().path());
        assertEquals("malformed.onCreate", entry.location().symbol());

        CallFlowNode helper = flow.nodes().stream()
                .filter(node -> node.kind() == NodeKind.CALL)
                .filter(node -> "helper()".equals(node.location().anchorText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the helper call must remain in UAST"));
        assertEquals("malformed.helper", helper.location().symbol());
        CallFlowEdge into = edge(flow, helper, EdgeKind.STEP_INTO);
        assertEquals(TransitionKind.CALL, into.transition().kind());
        CallFlowNode declaration = node(flow, into.to());
        assertEquals(NodeKind.DECLARATION, declaration.kind());
        assertEquals("malformed.helper", declaration.location().symbol());
        assertTrue(flow.edges().stream()
                .anyMatch(edge -> edge.kind() == EdgeKind.STEP_OUT
                        && edge.transition().kind() == TransitionKind.RETURN));
        GeneratedCallFlowValidation.validate(flow);
    }

    private void installKotlinStdlib() {
        Path stdlib = Path.of(
                PathManager.getHomePath(),
                "plugins",
                "Kotlin",
                "kotlinc",
                "lib",
                "kotlin-stdlib.jar"
        );
        assertTrue(Files.isRegularFile(stdlib), () -> "Kotlin stdlib is missing: " + stdlib);
        PsiTestUtil.addLibrary(
                getFixture().getModule(),
                "kotlin-stdlib-malformed-tail-test",
                stdlib.getParent().toString(),
                stdlib.getFileName().toString()
        );
    }

    private PsiFile installProjectSource() throws IOException {
        String basePath = getFixture().getProject().getBasePath();
        assertNotNull(basePath);
        Path projectRoot = Path.of(basePath);
        Path sourceRoot = projectRoot.resolve("src");
        Path source = projectRoot.resolve(SOURCE_PATH);
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE);
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRoot);
        assertNotNull(root);
        PsiTestUtil.addSourceContentToRoots(getFixture().getModule(), root);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
        assertNotNull(file);
        PsiFile psiFile = ReadAction.compute(
                () -> PsiManager.getInstance(getFixture().getProject()).findFile(file)
        );
        assertNotNull(psiFile);
        return psiFile;
    }

    private static CallFlowNode node(CallFlow flow, String id) {
        return flow.nodes().stream()
                .filter(node -> id.equals(node.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing node " + id));
    }

    private static CallFlowEdge edge(CallFlow flow, CallFlowNode from, EdgeKind kind) {
        List<CallFlowEdge> matches = flow.edges().stream()
                .filter(edge -> from.id().equals(edge.from()))
                .filter(edge -> edge.kind() == kind)
                .toList();
        assertEquals(1, matches.size());
        return matches.getFirst();
    }
}
