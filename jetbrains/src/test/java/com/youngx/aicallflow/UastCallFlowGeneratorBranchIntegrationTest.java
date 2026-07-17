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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that Kotlin conditions become executable graph branches instead of decorations. */
final class UastCallFlowGeneratorBranchIntegrationTest
        extends LightJavaCodeInsightFixtureTestCase5 {
    private static final String SOURCE_PATH = "src/sample/BranchFlow.kt";
    private static final String SOURCE = """
            package sample

            fun branchFlow(flag: Boolean, value: Int) {
                if (flag) {
                    trueLeaf()
                } else {
                    falseLeaf()
                }
                when (value) {
                    0 -> zeroLeaf()
                    1 -> oneLeaf()
                    else -> otherLeaf()
                }
                afterBranches()
            }

            fun trueLeaf() {}
            fun falseLeaf() {}
            fun zeroLeaf() {}
            fun oneLeaf() {}
            fun otherLeaf() {}
            fun afterBranches() {}
            """;

    @Override
    protected String getTestDataPath() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void emitsRealIfAndWhenArmsWithJoinsAndNoPseudoSymbols() throws Exception {
        installKotlinStdlib();
        PsiFile sourceFile = installProjectSource();
        ApplicationManager.getApplication().invokeAndWait(
                () -> PsiDocumentManager.getInstance(getFixture().getProject())
                        .commitAllDocuments()
        );
        DumbService.getInstance(getFixture().getProject()).waitForSmartMode();

        CallFlow flow = ReadAction.compute(() -> {
            UMethod current = CurrentCallableResolver.resolve(
                    sourceFile,
                    SOURCE.indexOf("if (flag)")
            );
            assertNotNull(current, "the caret must resolve to branchFlow through Kotlin UAST");
            return new UastCallFlowGenerator(getFixture().getProject()).generate(current);
        });

        CallFlowNode ifBranch = branch(flow, "if (flag) {");
        assertBranchTargets(
                flow,
                ifBranch,
                call(flow, "trueLeaf()"),
                call(flow, "falseLeaf()")
        );

        CallFlowNode whenBranch = branch(flow, "when (value) {");
        CallFlowNode secondWhenBranch = branch(flow, "1 -> oneLeaf()");
        assertBranchTargets(
                flow,
                whenBranch,
                call(flow, "zeroLeaf()"),
                secondWhenBranch
        );
        assertBranchTargets(
                flow,
                secondWhenBranch,
                call(flow, "oneLeaf()"),
                call(flow, "otherLeaf()")
        );

        List<CallFlowNode> joins = flow.nodes().stream()
                .filter(node -> node.kind() == NodeKind.NOTE)
                .filter(node -> "Branch join".equals(node.execution().phase()))
                .toList();
        assertEquals(3, joins.size(), "if and two when decisions each need a real join");
        for (CallFlowNode join : joins) {
            assertNull(join.location().symbol());
            List<CallFlowEdge> outgoing = outgoing(flow, join);
            assertEquals(1, outgoing.size());
            assertEquals(EdgeKind.NEXT, outgoing.getFirst().kind());
            assertEquals(TransitionKind.CONTINUE, outgoing.getFirst().transition().kind());
        }
        flow.nodes().stream()
                .filter(node -> node.kind() == NodeKind.BRANCH
                        || node.kind() == NodeKind.RETURN)
                .forEach(node -> assertNull(
                        node.location().symbol(),
                        () -> node.kind() + " must not expose a pseudo symbol at " + node.id()
                ));

        CallFlowNode outerWhenJoin = joins.stream()
                .filter(join -> outgoing(flow, join).stream()
                        .anyMatch(edge -> edge.to().equals(call(flow, "afterBranches()").id())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the when paths must join before afterBranches"));
        assertTrue(flow.nodes().contains(outerWhenJoin));
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
                "kotlin-stdlib-branch-test",
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
        assertNotNull(root, "the fixture source root must be visible in VFS");
        PsiTestUtil.addSourceContentToRoots(getFixture().getModule(), root);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
        assertNotNull(file, "the fixture Kotlin file must be visible in VFS");
        PsiFile psiFile = ReadAction.compute(
                () -> PsiManager.getInstance(getFixture().getProject()).findFile(file)
        );
        assertNotNull(psiFile, "the fixture Kotlin file must have PSI");
        return psiFile;
    }

    private static CallFlowNode branch(CallFlow flow, String anchorText) {
        List<CallFlowNode> matches = flow.nodes().stream()
                .filter(node -> node.kind() == NodeKind.BRANCH)
                .filter(node -> anchorText.equals(node.location().anchorText()))
                .toList();
        assertEquals(1, matches.size(), () -> "expected one branch anchored at " + anchorText);
        return matches.getFirst();
    }

    private static CallFlowNode call(CallFlow flow, String anchorText) {
        List<CallFlowNode> matches = flow.nodes().stream()
                .filter(node -> node.kind() == NodeKind.CALL)
                .filter(node -> anchorText.equals(node.location().anchorText()))
                .toList();
        assertEquals(1, matches.size(), () -> "expected one call anchored at " + anchorText);
        return matches.getFirst();
    }

    private static void assertBranchTargets(
            CallFlow flow,
            CallFlowNode branch,
            CallFlowNode trueTarget,
            CallFlowNode falseTarget
    ) {
        List<CallFlowEdge> outgoing = outgoing(flow, branch);
        assertEquals(2, outgoing.size(), "a branch must expose exactly two runtime choices");
        CallFlowEdge trueEdge = outgoing.stream()
                .filter(edge -> edge.kind() == EdgeKind.BRANCH_TRUE)
                .findFirst()
                .orElseThrow();
        CallFlowEdge falseEdge = outgoing.stream()
                .filter(edge -> edge.kind() == EdgeKind.BRANCH_FALSE)
                .findFirst()
                .orElseThrow();
        assertEquals(trueTarget.id(), trueEdge.to());
        assertEquals(falseTarget.id(), falseEdge.to());
        assertEquals(TransitionKind.BRANCH, trueEdge.transition().kind());
        assertEquals(TransitionKind.BRANCH, falseEdge.transition().kind());
    }

    private static List<CallFlowEdge> outgoing(CallFlow flow, CallFlowNode node) {
        return flow.edges().stream()
                .filter(edge -> node.id().equals(edge.from()))
                .toList();
    }
}
