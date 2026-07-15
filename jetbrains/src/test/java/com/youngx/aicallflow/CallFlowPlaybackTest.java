package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallFlowPlaybackTest {
    @Test
    void loadsAtEntryAndExposesBranchCandidatesWithoutMoving() {
        CallFlowPlayback playback = new CallFlowPlayback();

        playback.load(flow());

        assertEquals("entry", playback.current().id());
        assertEquals(List.of("inside", "over"), ids(playback.next()));
        assertEquals("entry", playback.current().id());
        assertFalse(playback.canPrevious());
        assertFalse(playback.canForward());
    }

    @Test
    void filtersDebuggerStyleStepsByExactEdgeKind() {
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(flow());

        assertEquals(List.of("inside"), ids(playback.stepInto()));
        assertEquals(List.of("over"), ids(playback.stepOver()));
        assertTrue(playback.stepOut().isEmpty());

        playback.choose(playback.stepInto().getFirst());
        assertEquals(List.of("after"), ids(playback.stepOut()));
    }

    @Test
    void previousAndForwardFollowVisitHistory() {
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(flow());
        playback.choose(playback.stepInto().getFirst());
        playback.choose(playback.stepOut().getFirst());

        assertEquals("inside", playback.previous().id());
        assertEquals("entry", playback.previous().id());
        assertNull(playback.previous());
        assertEquals("inside", playback.forward().id());
        assertEquals("after", playback.forward().id());
        assertNull(playback.forward());
    }

    @Test
    void choosingNewBranchClearsForwardHistory() {
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(flow());
        playback.choose(playback.stepInto().getFirst());
        playback.previous();
        assertTrue(playback.canForward());

        playback.choose(playback.stepOver().getFirst());

        assertEquals("over", playback.current().id());
        assertFalse(playback.canForward());
    }

    @Test
    void directJumpIsRecordedAndRejectsUnknownNodes() {
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(flow());

        playback.jumpTo("after");
        assertEquals("after", playback.current().id());
        assertEquals("entry", playback.previous().id());
        assertThrows(IllegalArgumentException.class, () -> playback.jumpTo("missing"));
    }

    @Test
    void reloadClearsHistory() {
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(flow());
        playback.choose(playback.stepInto().getFirst());

        playback.load(flow());

        assertEquals("entry", playback.current().id());
        assertFalse(playback.canPrevious());
        assertFalse(playback.canForward());
    }

    @Test
    void acceptsTerminalSingleNodeFlow() {
        CallFlowNode onlyNode = node("only", NodeKind.ENTRY, 1);
        CallFlow terminalFlow = new CallFlow(
                CallFlow.SUPPORTED_VERSION,
                "Terminal flow",
                List.of(onlyNode),
                List.of(),
                "only"
        );
        CallFlowPlayback playback = new CallFlowPlayback();

        playback.load(terminalFlow);

        assertEquals("only", playback.current().id());
        assertTrue(playback.next().isEmpty());
    }

    private static List<String> ids(List<CallFlowPlayback.Candidate> candidates) {
        return candidates.stream().map(candidate -> candidate.node().id()).toList();
    }

    private static CallFlow flow() {
        CallFlowNode entry = node("entry", NodeKind.ENTRY, 1);
        CallFlowNode inside = node("inside", NodeKind.CALL, 2);
        CallFlowNode over = node("over", NodeKind.CALL, 3);
        CallFlowNode after = node("after", NodeKind.RETURN, 4);
        return new CallFlow(
                CallFlow.SUPPORTED_VERSION,
                "Test flow",
                List.of(entry, inside, over, after),
                List.of(
                        new CallFlowEdge("entry", "inside", EdgeKind.STEP_INTO, "enter call"),
                        new CallFlowEdge("entry", "over", EdgeKind.STEP_OVER, "skip call"),
                        new CallFlowEdge("inside", "after", EdgeKind.STEP_OUT, "return")
                ),
                "entry"
        );
    }

    private static CallFlowNode node(String id, NodeKind kind, int line) {
        return new CallFlowNode(
                id,
                kind,
                new CallFlowLocation("app/src/Main.kt", line, 1, null, null, null, null),
                "Node " + id
        );
    }
}
