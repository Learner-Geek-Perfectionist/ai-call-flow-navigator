package com.youngx.aicallflow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-platform coverage for static playback and Live trace presentation isolation. */
final class CallFlowToolWindowPanelIntegrationTest
        extends LightJavaCodeInsightFixtureTestCase5 {
    @Override
    protected String getTestDataPath() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void liveEventsHighlightAndRevealNodesWithoutChangingStaticPlayback() throws Exception {
        installSource();
        CallFlow flow = flow();
        CallFlowSessionService session = CallFlowSessionService.getInstance(
                getFixture().getProject()
        );
        session.loadAsync(flow, () -> true)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        CallFlowToolWindowPanel panel = onEdt(
                () -> new CallFlowToolWindowPanel(getFixture().getProject())
        );
        try {
            // Flush the Live service's deferred initialization for the preloaded flow.
            onEdt(() -> null);
            assertEquals("entry", session.current().id());
            assertEquals(1, session.visitedNodeCount());
            assertEquals(1, session.currentPath().size());

            TraceEvent liveOnlyEvent = pauseEvent(
                    1,
                    "entry",
                    "live-only",
                    EdgeKind.STEP_INTO
            );
            onEdt(() -> {
                panel.snapshotChanged(snapshot("run-one", liveOnlyEvent));
                return null;
            });

            JBList<?> nodeList = field(panel, "nodeList", JBList.class);
            JBTextArea details = field(panel, "detailsArea", JBTextArea.class);
            JBLabel position = field(panel, "positionLabel", JBLabel.class);
            JBLabel transition = field(panel, "transitionLabel", JBLabel.class);
            assertEquals("live-only", ((CallFlowNode) nodeList.getSelectedValue()).id());
            assertEquals("Live-only summary must stay visible.", details.getText());
            assertTrue(position.getText().startsWith("Live · Paused · Event 1/1"));
            assertTrue(transition.getText().contains("LIVE INTO"));
            assertEquals("entry", session.current().id());
            assertEquals(1, session.visitedNodeCount());
            assertEquals(1, session.currentPath().size());
            assertFalse(session.hasVisited("live-only"));

            // Event sequence numbers restart for each TraceRun; the new run must still update UI.
            TraceEvent secondRunEvent = pauseEvent(
                    1,
                    "entry",
                    "static-next",
                    EdgeKind.NEXT
            );
            onEdt(() -> {
                panel.snapshotChanged(snapshot("run-two", secondRunEvent));
                return null;
            });
            assertEquals("static-next", ((CallFlowNode) nodeList.getSelectedValue()).id());
            assertEquals("Static-next summary must stay visible.", details.getText());
            assertEquals("entry", session.current().id());
            assertEquals(1, session.visitedNodeCount());

            JButton staticNext = field(panel, "nextButton", JButton.class);
            onEdt(() -> {
                staticNext.doClick();
                return null;
            });
            assertEquals("static-next", session.current().id());
            assertEquals(2, session.visitedNodeCount());
            assertEquals(2, session.currentPath().size());
            assertEquals("Static-next summary must stay visible.", details.getText());
            assertTrue(position.getText().startsWith("Step 2"));
            assertFalse(transition.getText().contains("LIVE"));
        } finally {
            onEdt(() -> {
                panel.dispose();
                return null;
            });
        }
    }

    @Test
    void controlsAreSeparatedAndEventReviewDoesNotSuggestReverseDebugging() throws Exception {
        CallFlowToolWindowPanel panel = onEdt(
                () -> new CallFlowToolWindowPanel(getFixture().getProject())
        );
        try {
            List<JButton> buttons = descendants(panel, JButton.class);
            List<String> buttonTexts = buttons.stream().map(JButton::getText).toList();
            assertFalse(
                    buttonTexts.stream()
                            .map(text -> text.toLowerCase(Locale.ROOT))
                            .anyMatch(text -> text.contains("analyze"))
            );
            assertTrue(buttonTexts.containsAll(List.of(
                    "Previous",
                    "Forward",
                    "Next",
                    "Into",
                    "Over",
                    "Out",
                    "Previous Event",
                    "Next Event",
                    "Pause",
                    "Resume",
                    "Step Into",
                    "Step Over",
                    "Step Out"
            )));

            List<String> labels = descendants(panel, JBLabel.class).stream()
                    .map(JBLabel::getText)
                    .toList();
            assertTrue(labels.contains("Static:"));
            assertTrue(labels.contains("Live:"));

            JButton previousEvent = field(panel, "previousEventButton", JButton.class);
            JButton nextEvent = field(panel, "nextEventButton", JButton.class);
            assertEquals("Previous Event", previousEvent.getText());
            assertTrue(previousEvent.getToolTipText().contains(
                    "does not reverse program execution"
            ));
            assertEquals("Next Event", nextEvent.getText());
            assertTrue(nextEvent.getToolTipText().contains(
                    "does not resume program execution"
            ));
        } finally {
            onEdt(() -> {
                panel.dispose();
                return null;
            });
        }
    }

    private void installSource() throws Exception {
        String source = """
                package sample;
                class Sample {
                    void first() {}
                    void liveOnly() {}
                    void staticNext() {}
                }
                """;
        String basePath = getFixture().getProject().getBasePath();
        assertNotNull(basePath);
        Path sourceRootPath = Path.of(basePath).resolve("src");
        Path sourcePath = sourceRootPath.resolve("sample/Sample.java");
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, source);
        VirtualFile sourceRoot = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(sourceRootPath);
        assertNotNull(sourceRoot);
        PsiTestUtil.addSourceContentToRoots(getFixture().getModule(), sourceRoot);
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourcePath));
    }

    private static CallFlow flow() {
        CallFlowNode entry = node(
                "entry",
                NodeKind.ENTRY,
                3,
                "sample.Sample.first",
                "Entry summary."
        );
        CallFlowNode liveOnly = node(
                "live-only",
                NodeKind.CALL,
                4,
                "sample.Sample.liveOnly",
                "Live-only summary must stay visible."
        );
        CallFlowNode staticNext = node(
                "static-next",
                NodeKind.RETURN,
                5,
                "sample.Sample.staticNext",
                "Static-next summary must stay visible."
        );
        return new CallFlow(
                CallFlow.SUPPORTED_VERSION,
                "Panel integration",
                List.of(entry, liveOnly, staticNext),
                List.of(new CallFlowEdge("entry", "static-next", EdgeKind.NEXT, "continue")),
                "entry"
        );
    }

    private static CallFlowNode node(
            String id,
            NodeKind kind,
            int line,
            String symbol,
            String summary
    ) {
        return new CallFlowNode(
                id,
                kind,
                new CallFlowLocation(
                        "src/sample/Sample.java",
                        line,
                        5,
                        line,
                        10,
                        symbol,
                        null
                ),
                summary
        );
    }

    private static TraceEvent pauseEvent(
            long sequence,
            String previousNodeId,
            String nodeId,
            EdgeKind edgeKind
    ) {
        return new TraceEvent(
                sequence,
                1_000 + sequence,
                TraceEventKind.PAUSED,
                "Android App",
                "main",
                new TraceSourcePosition(
                        "src/sample/Sample.java",
                        "live-only".equals(nodeId) ? 4 : 5,
                        "sample.Sample." + ("live-only".equals(nodeId)
                                ? "liveOnly"
                                : "staticNext")
                ),
                previousNodeId,
                nodeId,
                edgeKind,
                TraceMatchConfidence.EXACT,
                List.of(nodeId)
        );
    }

    private static LiveDebuggerSnapshot snapshot(String runId, TraceEvent event) {
        TraceRun run = new TraceRun(
                runId,
                "flow-fingerprint",
                "Panel integration",
                "entry",
                1_000,
                null,
                TraceRunState.PAUSED,
                "Android App",
                List.of(event)
        );
        return new LiveDebuggerSnapshot(
                TraceRunState.PAUSED,
                true,
                "Android App",
                true,
                false,
                true,
                true,
                run,
                event,
                false,
                false,
                "Debugger paused"
        );
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = CallFlowToolWindowPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static <T extends Component> List<T> descendants(
            Container root,
            Class<T> type
    ) {
        List<T> matches = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                matches.add(type.cast(component));
            }
            if (component instanceof Container container) {
                matches.addAll(descendants(container, type));
            }
        }
        return matches;
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        ApplicationManager.getApplication().invokeAndWait(task);
        return task.get();
    }
}
