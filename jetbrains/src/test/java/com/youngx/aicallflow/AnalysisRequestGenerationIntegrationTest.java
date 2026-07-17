package com.youngx.aicallflow;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.project.DumbService;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PSI/UAST coverage for Skill request -> current project -> generated static graph. */
final class AnalysisRequestGenerationIntegrationTest
        extends LightJavaCodeInsightFixtureTestCase5 {
    @Override
    protected String getTestDataPath() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void resolvesTheEntryAgainstTheOpenProjectAndGeneratesFromItsMethod() throws Exception {
        String source = """
                package sample;

                class FlowSample {
                    void target() {
                        helper();
                    }

                    void helper() {
                    }
                }
                """;
        installProjectSource("src/sample/FlowSample.java", source);
        AnalysisRequest request = request(
                "src/sample/FlowSample.java",
                4,
                10,
                "sample.FlowSample.target",
                "target 的真实启动流程"
        );

        CallFlow flow = StaticCallFlowGenerationService.getInstance(getFixture().getProject())
                .generateForRequest(request);

        assertEquals("target 的真实启动流程", flow.title());
        CallFlowNode entry = flow.nodes().stream()
                .filter(node -> flow.entry().equals(node.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("src/sample/FlowSample.java", entry.location().path());
        assertEquals(4, entry.location().line());
        assertEquals(10, entry.location().column());
        assertEquals("sample.FlowSample.target", entry.location().symbol());
        assertTrue(flow.nodes().stream().anyMatch(node ->
                node.location() != null
                        && "sample.FlowSample.helper".equals(node.location().symbol())
        ));
    }

    @Test
    void rejectsWrongSymbolsAndCoordinatesInsteadOfAnalyzingAnotherFunction() throws Exception {
        String source = """
                package sample;
                class RejectSample {
                    void target() {
                    }
                }
                """;
        installProjectSource("src/sample/RejectSample.java", source);
        StaticCallFlowGenerationService service =
                StaticCallFlowGenerationService.getInstance(getFixture().getProject());

        IllegalArgumentException symbolError = assertThrows(
                IllegalArgumentException.class,
                () -> service.generateForRequest(request(
                        "src/sample/RejectSample.java", 3, 10, "sample.RejectSample.other", "wrong"
                ))
        );
        assertTrue(symbolError.getMessage().contains("symbol does not match"));

        IllegalArgumentException lineError = assertThrows(
                IllegalArgumentException.class,
                () -> service.generateForRequest(request(
                        "src/sample/RejectSample.java", 99, 1, "sample.RejectSample.target", "wrong"
                ))
        );
        assertTrue(lineError.getMessage().contains("line is outside"));

        IllegalArgumentException missingError = assertThrows(
                IllegalArgumentException.class,
                () -> service.generateForRequest(request(
                        "src/sample/Missing.java", 1, 1, "sample.Missing.target", "wrong"
                ))
        );
        assertTrue(missingError.getMessage().contains("Cannot resolve analysis entry"));
    }

    private void installProjectSource(String relativePath, String source) throws Exception {
        String basePath = getFixture().getProject().getBasePath();
        assertNotNull(basePath);
        Path projectRoot = Path.of(basePath);
        Path sourceRootPath = projectRoot.resolve("src");
        Path sourcePath = projectRoot.resolve(relativePath);
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, source);
        VirtualFile sourceRoot = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(sourceRootPath);
        assertNotNull(sourceRoot);
        PsiTestUtil.addSourceContentToRoots(getFixture().getModule(), sourceRoot);
        VirtualFile sourceFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(sourcePath);
        assertNotNull(sourceFile);
        DumbService.getInstance(getFixture().getProject()).waitForSmartMode();
    }

    private static AnalysisRequest request(
            String path,
            int line,
            int column,
            String symbol,
            String topic
    ) {
        return new AnalysisRequest(
                new AnalysisRequest.Delivery("3.0", "test-request", 1L, 2L),
                "1.0",
                "analysis-request",
                topic,
                new AnalysisRequest.Entry(path, line, column, symbol),
                new AnalysisRequest.Strategy("static-and-live", "project-code")
        );
    }
}
