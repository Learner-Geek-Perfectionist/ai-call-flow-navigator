package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LiveTraceRecorderTest {
    @Test
    void recordsIndependentExecutionSamplesWithoutMutatingStaticPlayback() {
        AtomicLong clock = new AtomicLong(1_000);
        LiveTraceRecorder recorder = new LiveTraceRecorder(clock::getAndIncrement, () -> "run-1");
        CallFlow flow = flow();

        recorder.start(flow);
        assertEquals(TraceRunState.WAITING_FOR_SESSION, recorder.state());
        recorder.sessionAttached("Android App", false);
        recorder.paused(
                new TraceSourcePosition("src/Sample.kt", 10, "sample.first"),
                "main"
        );
        recorder.frameChanged(
                new TraceSourcePosition("src/Sample.kt", 40, "sample.frameOnly"),
                "worker-1"
        );
        recorder.command(TraceEventKind.STEP_INTO_REQUESTED);
        recorder.resumed();
        recorder.paused(new TraceSourcePosition("src/Sample.kt", 20, null));

        TraceRun active = recorder.snapshot();
        assertEquals(TraceRunState.PAUSED, active.state());
        assertEquals("run-1", active.id());
        assertEquals(2, active.executionEvents().size());
        assertEquals(1, active.hitCount("first"));
        assertEquals(1, active.hitCount("expected"));
        assertEquals(0, active.hitCount("frame-only"));

        TraceEvent firstPause = active.executionEvents().getFirst();
        assertEquals("first", firstPause.nodeId());
        assertEquals("main", firstPause.contextLabel());
        assertEquals(TraceMatchConfidence.EXACT, firstPause.confidence());
        assertNull(firstPause.previousNodeId());

        TraceEvent frameSelection = active.events().get(2);
        assertEquals(TraceEventKind.FRAME_CHANGED, frameSelection.kind());
        assertEquals("frame-only", frameSelection.nodeId());
        assertEquals("worker-1", frameSelection.contextLabel());
        assertEquals("first", frameSelection.previousNodeId());
        assertFalse(frameSelection.executionSample());

        TraceEvent secondPause = active.executionEvents().getLast();
        assertEquals("expected", secondPause.nodeId());
        assertEquals("first", secondPause.previousNodeId());
        assertEquals(EdgeKind.STEP_INTO, secondPause.viaEdgeKind());
        assertEquals(TraceMatchConfidence.ADJACENT, secondPause.confidence());

        recorder.sessionStopped();
        TraceRun completed = recorder.snapshot();
        assertEquals(TraceRunState.COMPLETED, completed.state());
        assertEquals(TraceEventKind.SESSION_STOPPED, completed.latestEvent().kind());
        assertTrue(completed.endedAtEpochMs() >= completed.startedAtEpochMs());
        assertFalse(recorder.active());
        assertThrows(
                UnsupportedOperationException.class,
                () -> completed.events().add(firstPause)
        );
        assertEquals(5, flow.nodes().size(), "the static Call Flow remains a separate value");
    }

    private static CallFlow flow() {
        List<CallFlowNode> nodes = List.of(
                node("first", 10, "sample.first"),
                node("expected", 20, "sample.expected"),
                node("other", 20, "sample.other"),
                node("frame-only", 40, "sample.frameOnly"),
                node("unused", 50, "sample.unused")
        );
        return new CallFlow(
                "1.0",
                "runtime trace test",
                nodes,
                List.of(new CallFlowEdge("first", "expected", EdgeKind.STEP_INTO, null)),
                "first"
        );
    }

    private static CallFlowNode node(String id, int line, String symbol) {
        return new CallFlowNode(
                id,
                NodeKind.CALL,
                new CallFlowLocation(
                        "src/Sample.kt",
                        line,
                        1,
                        line,
                        20,
                        symbol,
                        null
                ),
                id
        );
    }
}
