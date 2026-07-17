package com.youngx.aicallflow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import org.jetbrains.uast.UMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers callback bindings captured across a local function and nested framework lambdas. */
final class UastCallFlowGeneratorBindingIntegrationTest
        extends LightJavaCodeInsightFixtureTestCase5 {
    private static final String SOURCE_PATH = "src/sample/BindingFlow.kt";
    private static final String SOURCE = """
            package sample

            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Scaffold

            fun bindingFlow() {
                wrapper { boundLeaf() }
            }

            fun wrapper(content: () -> Unit) {
                MaterialTheme {
                    Scaffold(content = content)
                }
            }

            fun boundLeaf() {}
            """;
    private VirtualFile externalMaterialRoot;

    @Override
    protected String getTestDataPath() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void preservesCapturedCallbackBindingAcrossNestedFrameworkLambdas() throws Exception {
        installKotlinStdlib();
        installExternalMaterialSources();
        PsiFile sourceFile = installProjectSource();
        ApplicationManager.getApplication().invokeAndWait(
                () -> PsiDocumentManager.getInstance(getFixture().getProject())
                        .commitAllDocuments()
        );
        DumbService.getInstance(getFixture().getProject()).waitForSmartMode();

        CallFlow flow = ReadAction.compute(() -> {
            UMethod current = CurrentCallableResolver.resolve(
                    sourceFile,
                    SOURCE.indexOf("wrapper { boundLeaf() }")
            );
            assertNotNull(current);
            return new UastCallFlowGenerator(getFixture().getProject()).generate(current);
        });

        CallFlowNode wrapper = call(flow, "wrapper { boundLeaf() }");
        assertEquals(TransitionKind.CALL, edge(flow, wrapper, EdgeKind.STEP_INTO).transition().kind());

        CallFlowNode materialTheme = call(flow, "MaterialTheme {");
        assertEquals("androidx.compose.material3.MaterialTheme", materialTheme.location().symbol());
        assertEquals(
                TransitionKind.CALLBACK_ENTER,
                edge(flow, materialTheme, EdgeKind.STEP_INTO).transition().kind()
        );

        CallFlowNode scaffold = call(flow, "Scaffold(content = content)");
        assertEquals("androidx.compose.material3.Scaffold", scaffold.location().symbol());
        CallFlowEdge scaffoldInto = edge(flow, scaffold, EdgeKind.STEP_INTO);
        assertEquals(TransitionKind.CALLBACK_ENTER, scaffoldInto.transition().kind());

        CallFlowNode boundLeaf = node(flow, scaffoldInto.to());
        assertEquals("boundLeaf() }", boundLeaf.location().anchorText());
        assertEquals("sample.boundLeaf", boundLeaf.location().symbol());
        assertEquals(4, boundLeaf.execution().stack().size());
        assertEquals(
                TransitionKind.CALL,
                edge(flow, boundLeaf, EdgeKind.STEP_INTO).transition().kind()
        );

        assertReturnsToOver(flow, materialTheme, TransitionKind.CALLBACK_RETURN);
        assertReturnsToOver(flow, scaffold, TransitionKind.CALLBACK_RETURN);
        GeneratedCallFlowValidation.validate(flow);
    }

    @AfterEach
    void removeExternalMaterialSources() {
        if (externalMaterialRoot == null) {
            return;
        }
        PsiTestUtil.removeContentEntry(getFixture().getModule(), externalMaterialRoot);
        DumbService.getInstance(getFixture().getProject()).waitForSmartMode();
        externalMaterialRoot = null;
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
                "kotlin-stdlib-binding-test",
                stdlib.getParent().toString(),
                stdlib.getFileName().toString()
        );
    }

    private void installExternalMaterialSources() throws IOException {
        Path sourceRoot = Files.createTempDirectory("ai-call-flow-material-sources-");
        Path source = sourceRoot.resolve("androidx/compose/material3/Material.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package androidx.compose.material3

                fun MaterialTheme(content: () -> Unit) {
                    content()
                }

                fun Scaffold(content: () -> Unit) {
                    content()
                }
                """);
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRoot);
        assertNotNull(root);
        PsiTestUtil.addSourceContentToRoots(getFixture().getModule(), root);
        externalMaterialRoot = root;
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

    private static CallFlowNode call(CallFlow flow, String anchorText) {
        List<CallFlowNode> matches = flow.nodes().stream()
                .filter(node -> node.kind() == NodeKind.CALL)
                .filter(node -> anchorText.equals(node.location().anchorText()))
                .toList();
        assertEquals(1, matches.size(), () -> "expected one call anchored at " + anchorText);
        return matches.getFirst();
    }

    private static CallFlowNode node(CallFlow flow, String id) {
        return flow.nodes().stream()
                .filter(node -> id.equals(node.id()))
                .findFirst()
                .orElseThrow();
    }

    private static CallFlowEdge edge(CallFlow flow, CallFlowNode from, EdgeKind kind) {
        List<CallFlowEdge> matches = flow.edges().stream()
                .filter(edge -> from.id().equals(edge.from()))
                .filter(edge -> edge.kind() == kind)
                .toList();
        assertEquals(1, matches.size(), () -> "expected one " + kind + " from " + from.id());
        return matches.getFirst();
    }

    private static void assertReturnsToOver(
            CallFlow flow,
            CallFlowNode call,
            TransitionKind transition
    ) {
        CallFlowEdge over = edge(flow, call, EdgeKind.STEP_OVER);
        assertTrue(flow.edges().stream()
                .filter(edge -> edge.kind() == EdgeKind.STEP_OUT)
                .filter(edge -> over.to().equals(edge.to()))
                .anyMatch(edge -> edge.transition().kind() == transition));
    }
}
