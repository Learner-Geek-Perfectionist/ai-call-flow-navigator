package com.youngx.aicallflow;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-platform coverage for project-service lifecycle without a running debugger. */
final class LiveDebuggerTraceServiceIntegrationTest
        extends LightJavaCodeInsightFixtureTestCase5 {
    @Override
    protected String getTestDataPath() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void projectServiceWaitsForDebuggerAndCompletesAnIndependentTrace() {
        LiveDebuggerTraceService service = LiveDebuggerTraceService.getInstance(
                getFixture().getProject()
        );
        assertNotNull(service);
        assertSame(service, LiveDebuggerTraceService.getInstance(getFixture().getProject()));

        CallFlow flow = flow();
        service.startRecording(flow);

        LiveDebuggerSnapshot waiting = service.snapshot();
        assertEquals(TraceRunState.WAITING_FOR_SESSION, waiting.state());
        assertTrue(waiting.recording());
        assertNotNull(waiting.run());
        assertEquals(0, service.hitCount("entry"));
        assertFalse(service.canStep());
        assertFalse(service.canResume());
        assertFalse(service.canPreviousEvent());
        assertFalse(service.canNextEvent());

        service.stopRecording();

        LiveDebuggerSnapshot completed = service.snapshot();
        assertEquals(TraceRunState.COMPLETED, completed.state());
        assertFalse(completed.recording());
        assertEquals(TraceEventKind.RECORDING_STOPPED, completed.run().latestEvent().kind());
        assertEquals(1, flow.nodes().size(), "the service must not add runtime nodes to CallFlow");
    }

    private static CallFlow flow() {
        CallFlowNode entry = new CallFlowNode(
                "entry",
                NodeKind.ENTRY,
                new CallFlowLocation(
                        "src/Sample.java",
                        1,
                        1,
                        1,
                        10,
                        "Sample.main",
                        null
                ),
                "entry"
        );
        return new CallFlow("1.0", "service test", List.of(entry), List.of(), "entry");
    }
}
