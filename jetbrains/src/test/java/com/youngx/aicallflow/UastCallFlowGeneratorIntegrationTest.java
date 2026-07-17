package com.youngx.aicallflow;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastContextKt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end coverage through real Kotlin PSI and UAST, without mocks. */
final class UastCallFlowGeneratorIntegrationTest extends LightJavaCodeInsightFixtureTestCase5 {
    private static final String SOURCE_PATH = "src/sample/CurrentFlow.kt";
    private static final String SOURCE = """
            package sample

            import androidx.activity.compose.setContent
            import androidx.compose.material3.Scaffold

            fun currentFlow() {
                helper()
                setContent {
                    Scaffold {
                        leaf()
                    }
                }
            }

            fun helper() {
            }

            fun leaf() {
            }
            """;

    @Override
    protected String getTestDataPath() {
        // The Android Studio distribution does not contain an IntelliJ source checkout.
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void generatesCurrentKotlinFunctionWithLocalAndComposeCallbackExpansions() throws Exception {
        installKotlinStdlib();
        JvmScaffold jvmScaffold = installExternalFrameworkLibrary();
        PsiFile sourceFile = installProjectSource();
        ApplicationManager.getApplication().invokeAndWait(
                () -> PsiDocumentManager.getInstance(getFixture().getProject())
                        .commitAllDocuments()
        );
        DumbService.getInstance(getFixture().getProject()).waitForSmartMode();
        assertNotNull(sourceFile.getVirtualFile());
        assertTrue(
                sourceFile.getVirtualFile().getPath().startsWith(getFixture().getProject().getBasePath()),
                () -> "fixture file " + sourceFile.getVirtualFile().getPath()
                        + " must be under project " + getFixture().getProject().getBasePath()
        );

        CallFlow flow = ReadAction.compute(() -> {
            int caretOffset = SOURCE.indexOf("helper()");
            UMethod current = CurrentCallableResolver.resolve(sourceFile, caretOffset);
            assertNotNull(current, "the caret must resolve to currentFlow through real Kotlin UAST");
            assertEquals("currentFlow", current.getName());

            UCallExpression scaffoldCall = callAt(sourceFile, SOURCE.indexOf("Scaffold {"));
            assertNotNull(scaffoldCall, "the nested Scaffold call must have a UAST call parent");
            PsiMethod resolvedScaffold = scaffoldCall.resolve();
            assertNotNull(resolvedScaffold, "the external Kotlin Scaffold fixture must resolve");
            assertEquals("Scaffold", scaffoldCall.getMethodName());

            return new UastCallFlowGenerator(getFixture().getProject()).generate(current);
        });

        assertEquals(CallFlow.CONTEXT_VERSION, flow.version());
        assertEquals("currentFlow — Static Call Flow", flow.title());
        assertEquals("currentFlow", flow.frames().getFirst().label());
        assertFalse(flow.nodes().isEmpty());
        assertFalse(flow.edges().isEmpty());
        assertEquals(1, flow.contexts().size());
        assertEquals(ContextKind.TASK, flow.contexts().getFirst().kind());

        CallFlowNode entry = node(flow, flow.entry());
        assertEquals(NodeKind.ENTRY, entry.kind());
        assertLocation(entry, 6, 5, "sample.currentFlow", "currentFlow() {");

        CallFlowNode helper = call(flow, "helper()");
        assertLocation(helper, 7, 5, "sample.helper", "helper()");
        CallFlowEdge helperInto = edge(flow, helper, EdgeKind.STEP_INTO);
        assertEquals(TransitionKind.CALL, helperInto.transition().kind());
        CallFlowNode helperDeclaration = node(flow, helperInto.to());
        assertEquals(NodeKind.DECLARATION, helperDeclaration.kind());
        assertEquals("sample.helper", helperDeclaration.location().symbol());
        assertEquals(2, helperDeclaration.execution().stack().size());
        assertExpansionReturnsToStepOverTarget(flow, helper, helperDeclaration, TransitionKind.RETURN);

        CallFlowNode setContent = call(flow, "setContent {");
        assertLocation(
                setContent,
                8,
                5,
                "androidx.activity.compose.setContent",
                "setContent {"
        );
        CallFlowEdge setContentInto = edge(flow, setContent, EdgeKind.STEP_INTO);
        assertEquals(TransitionKind.CALLBACK_ENTER, setContentInto.transition().kind());
        CallFlowNode scaffold = node(flow, setContentInto.to());
        assertSame(scaffold, call(flow, "Scaffold {"));
        String scaffoldSymbol = scaffold.location().symbol();
        assertTrue(
                scaffoldSymbol == null
                        || "androidx.compose.material3.Scaffold".equals(scaffoldSymbol),
                () -> "Scaffold must expose either its source symbol or no symbol, got "
                        + scaffoldSymbol
        );
        assertLocation(
                scaffold,
                9,
                9,
                scaffoldSymbol,
                "Scaffold {"
        );
        assertLambdaFrame(flow, scaffold, "setContent content lambda");
        assertExpansionReturnsToStepOverTarget(
                flow,
                setContent,
                scaffold,
                TransitionKind.CALLBACK_RETURN
        );

        CallFlowEdge scaffoldInto = edge(flow, scaffold, EdgeKind.STEP_INTO);
        assertEquals(TransitionKind.CALLBACK_ENTER, scaffoldInto.transition().kind());
        CallFlowNode leaf = node(flow, scaffoldInto.to());
        assertSame(leaf, call(flow, "leaf()"));
        assertLocation(leaf, 10, 13, "sample.leaf", "leaf()");
        assertLambdaFrame(flow, leaf, "Scaffold content lambda");
        assertExpansionReturnsToStepOverTarget(
                flow,
                scaffold,
                leaf,
                TransitionKind.CALLBACK_RETURN
        );

        assertTrue(
                jvmScaffold.methodName().startsWith("Scaffold-"),
                () -> "value-class Scaffold fixture should expose a mangled JVM name, got "
                        + jvmScaffold.methodName()
        );
        assertEquals("androidx.compose.material3.ScaffoldKt", jvmScaffold.owner());
        assertEquals(
                "content",
                UastCallFlowGenerator.frameworkCallbackParameter(
                        null,
                        jvmScaffold.owner(),
                        jvmScaffold.methodName()
                ),
                "the JVM owner/name fallback must recognize a genuinely mangled UAST target"
        );

        // Re-run the production validator explicitly so this test cannot pass on shape-only checks.
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
                "kotlin-stdlib",
                stdlib.getParent().toString(),
                stdlib.getFileName().toString()
        );
    }

    private JvmScaffold installExternalFrameworkLibrary() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ai-call-flow-framework-library-");
        writeSource(
                sourceRoot,
                "androidx/activity/compose/ComponentActivity.kt",
                """
                        package androidx.activity.compose

                        fun setContent(content: () -> Unit) {
                            content()
                        }
                        """
        );
        writeSource(
                sourceRoot,
                "androidx/compose/material3/Scaffold.kt",
                """
                        package androidx.compose.material3

                        @JvmInline
                        value class ScaffoldToken(val raw: Int)

                        fun Scaffold(
                            token: ScaffoldToken = ScaffoldToken(0),
                            content: () -> Unit,
                        ) {
                            content()
                        }
                        """
        );
        Path compiler = Path.of(
                PathManager.getHomePath(),
                "plugins",
                "Kotlin",
                "kotlinc",
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "kotlinc.bat"
                        : "kotlinc"
        );
        assertTrue(Files.isRegularFile(compiler), () -> "Kotlin compiler is missing: " + compiler);
        Path library = sourceRoot.resolve("compose-fixture.jar");
        Process process = new ProcessBuilder(
                compiler.toString(),
                sourceRoot.resolve("androidx/activity/compose/ComponentActivity.kt").toString(),
                sourceRoot.resolve("androidx/compose/material3/Scaffold.kt").toString(),
                "-d",
                library.toString()
        ).redirectErrorStream(true).start();
        String compilerOutput = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertEquals(0, process.waitFor(), () -> "kotlinc failed:\n" + compilerOutput);
        assertTrue(Files.isRegularFile(library), "kotlinc must produce the fixture library");
        Path stdlib = Path.of(
                PathManager.getHomePath(),
                "plugins",
                "Kotlin",
                "kotlinc",
                "lib",
                "kotlin-stdlib.jar"
        );
        JvmScaffold jvmScaffold;
        URL[] classpath = {library.toUri().toURL(), stdlib.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(classpath, null)) {
            Class<?> scaffoldClass = Class.forName(
                    "androidx.compose.material3.ScaffoldKt",
                    true,
                    loader
            );
            String methodName = Arrays.stream(scaffoldClass.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.startsWith("Scaffold-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "compiled ScaffoldKt must contain a mangled Scaffold method"
                    ));
            jvmScaffold = new JvmScaffold(scaffoldClass.getName(), methodName);
        }
        PsiTestUtil.addLibrary(
                getFixture().getModule(),
                "compose-fixture",
                library.getParent().toString(),
                library.getFileName().toString()
        );
        return jvmScaffold;
    }

    private PsiFile installProjectSource() throws IOException {
        Path projectRoot = Path.of(getFixture().getProject().getBasePath());
        writeSource(projectRoot, SOURCE_PATH, SOURCE);
        VirtualFile sourceRoot = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectRoot.resolve("src"));
        assertNotNull(sourceRoot, "the project Kotlin source root must be visible in VFS");
        PsiTestUtil.addSourceContentToRoots(getFixture().getModule(), sourceRoot);
        VirtualFile sourceFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectRoot.resolve(SOURCE_PATH));
        assertNotNull(sourceFile, "the project Kotlin fixture must be visible in VFS");
        PsiFile psiFile = ReadAction.compute(
                () -> PsiManager.getInstance(getFixture().getProject()).findFile(sourceFile)
        );
        assertNotNull(psiFile, "the project Kotlin fixture must have PSI");
        return psiFile;
    }

    private static void writeSource(Path root, String relativePath, String content)
            throws IOException {
        Path destination = root.resolve(relativePath);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, content);
    }

    private static UCallExpression callAt(PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        return element == null
                ? null
                : UastContextKt.getUastParentOfType(element, UCallExpression.class);
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
                .orElseThrow(() -> new AssertionError("missing node " + id));
    }

    private static CallFlowEdge edge(CallFlow flow, CallFlowNode from, EdgeKind kind) {
        List<CallFlowEdge> matches = flow.edges().stream()
                .filter(edge -> from.id().equals(edge.from()))
                .filter(edge -> edge.kind() == kind)
                .toList();
        assertEquals(1, matches.size(), () -> "expected one " + kind + " edge from " + from.id());
        return matches.getFirst();
    }

    private static void assertLocation(
            CallFlowNode node,
            int line,
            int column,
            String symbol,
            String anchorText
    ) {
        CallFlowLocation location = node.location();
        assertEquals(SOURCE_PATH, location.path());
        assertEquals(line, location.line());
        assertEquals(column, location.column());
        assertEquals(line, location.endLine());
        assertTrue(location.endColumn() > column);
        assertEquals(symbol, location.symbol());
        assertEquals(anchorText, location.anchorText());
    }

    private static void assertLambdaFrame(CallFlow flow, CallFlowNode node, String label) {
        String frameId = node.execution().stack().getLast();
        CallFlowFrame frame = flow.frames().stream()
                .filter(candidate -> frameId.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing frame " + frameId));
        assertEquals(FrameKind.LAMBDA, frame.kind());
        assertEquals(label, frame.label());
    }

    private static void assertExpansionReturnsToStepOverTarget(
            CallFlow flow,
            CallFlowNode call,
            CallFlowNode expandedEntry,
            TransitionKind returnKind
    ) {
        CallFlowEdge over = edge(flow, call, EdgeKind.STEP_OVER);
        String expandedFrame = expandedEntry.execution().stack().getLast();
        CallFlowEdge out = flow.edges().stream()
                .filter(candidate -> candidate.kind() == EdgeKind.STEP_OUT)
                .filter(candidate -> over.to().equals(candidate.to()))
                .filter(candidate -> {
                    CallFlowNode source = node(flow, candidate.from());
                    List<String> stack = source.execution().stack();
                    return !stack.isEmpty() && expandedFrame.equals(stack.getLast());
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing step_out from expansion of " + call.location().anchorText()
                ));
        assertEquals(returnKind, out.transition().kind());
    }

    private record JvmScaffold(
            String owner,
            String methodName
    ) {
    }
}
