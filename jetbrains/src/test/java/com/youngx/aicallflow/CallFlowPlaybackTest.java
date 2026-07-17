package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallFlowPlaybackTest {
    @Test
    void loadsAtEntryAndExposesBranchCandidatesWithoutMoving() {
        CallFlowPlayback playback = new CallFlowPlayback();

        playback.load(flow());

        assertEquals("entry", playback.current().id());
        assertEquals(CallFlowPlayback.TransitionType.ENTRY, playback.currentVisit().transitionType());
        assertEquals(List.of("entry"), playback.currentVisit().execution().stack());
        assertFalse(playback.currentVisit().execution().exact());
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
        CallFlowPlayback.Visit insideVisit = playback.currentVisit();
        playback.choose(playback.stepOut().getFirst());
        CallFlowPlayback.Visit afterVisit = playback.currentVisit();

        assertEquals("inside", playback.previous().id());
        assertSame(insideVisit, playback.currentVisit());
        assertEquals(List.of("entry", "inside"), playback.currentVisit().execution().stack());
        assertEquals("entry", playback.previous().id());
        assertNull(playback.previous());
        assertEquals("inside", playback.forward().id());
        assertEquals("after", playback.forward().id());
        assertSame(afterVisit, playback.currentVisit());
        assertEquals(List.of("entry"), playback.currentVisit().execution().stack());
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
        assertEquals(CallFlowPlayback.TransitionType.DIRECT_JUMP,
                playback.currentVisit().transitionType());
        assertNull(playback.currentVisit().viaEdge());
        assertEquals(List.of("after"), playback.currentVisit().execution().stack());
        assertEquals("entry", playback.previous().id());
        assertThrows(IllegalArgumentException.class, () -> playback.jumpTo("missing"));
    }

    @Test
    void uniqueEdgeJumpPreservesTheEdgeAndItsTransition() {
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(flow());

        playback.jumpTo("inside");

        assertEquals(CallFlowPlayback.TransitionType.CALL,
                playback.currentVisit().transitionType());
        assertEquals("enter call", playback.currentVisit().viaEdge().label());
        assertEquals(List.of("entry", "inside"), playback.currentVisit().execution().stack());
    }

    @Test
    void ambiguousEdgesToTheSameTargetBecomeADirectJump() {
        CallFlowNode entry = node("entry", NodeKind.ENTRY, 1);
        CallFlowNode target = node("target", NodeKind.CALL, 2);
        CallFlow ambiguous = new CallFlow(
                CallFlow.SUPPORTED_VERSION,
                "Ambiguous flow",
                List.of(entry, target),
                List.of(
                        new CallFlowEdge("entry", "target", EdgeKind.NEXT, "continue"),
                        new CallFlowEdge("entry", "target", EdgeKind.STEP_INTO, "call")
                ),
                "entry"
        );
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(ambiguous);

        playback.jumpTo("target");

        assertEquals(CallFlowPlayback.TransitionType.DIRECT_JUMP,
                playback.currentVisit().transitionType());
        assertNull(playback.currentVisit().viaEdge());
        assertEquals(List.of("target"), playback.currentVisit().execution().stack());
    }

    @Test
    void choosingARepeatedCandidatePreservesTheExactEdgeInstance() {
        CallFlowNode entry = node("entry", NodeKind.ENTRY, 1);
        CallFlowNode target = node("target", NodeKind.CALL, 2);
        CallFlowEdge first = new CallFlowEdge("entry", "target", EdgeKind.NEXT, "same");
        CallFlowEdge second = new CallFlowEdge("entry", "target", EdgeKind.NEXT, "same");
        CallFlow repeated = new CallFlow(
                CallFlow.SUPPORTED_VERSION,
                "Repeated edge instances",
                List.of(entry, target),
                List.of(first, second),
                "entry"
        );
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(repeated);
        CallFlowPlayback.Candidate selected = playback.next().get(1);

        playback.choose(selected);

        assertSame(second, playback.currentVisit().viaEdge());
        assertEquals(1, playback.unexploredEdgeCount());
    }

    @Test
    void infersLegacyCallReturnCallbackAndAsyncExecution() {
        CallFlowNode entry = node("entry", NodeKind.ENTRY, 1);
        CallFlowNode callback = node("callback", NodeKind.CALL, 2);
        CallFlowNode async = node("async", NodeKind.ASYNC, 3);
        CallFlow legacy = new CallFlow(
                CallFlow.SUPPORTED_VERSION,
                "Legacy execution",
                List.of(entry, callback, async),
                List.of(
                        new CallFlowEdge("entry", "callback", EdgeKind.CALLBACK, "invoke callback"),
                        new CallFlowEdge("callback", "async", EdgeKind.ASYNC, "switch context")
                ),
                "entry"
        );
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(legacy);

        playback.choose(playback.next().getFirst());
        assertEquals(CallFlowPlayback.TransitionType.CALLBACK,
                playback.currentVisit().transitionType());
        assertEquals(List.of("entry", "callback"), playback.currentVisit().execution().stack());

        playback.choose(playback.next().getFirst());
        assertEquals(CallFlowPlayback.TransitionType.ASYNC,
                playback.currentVisit().transitionType());
        assertEquals("async", playback.currentVisit().execution().contextLabel());
        assertEquals(List.of("async"), playback.currentVisit().execution().stack());
        assertFalse(playback.currentVisit().execution().exact());
    }

    @Test
    void resolvesExactContextAndFrameLabelsForContextFlows() {
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(contextFlow());

        assertExactExecution(playback.currentVisit(), "Main thread", List.of("onCreate"), "startup");

        playback.choose(playback.next().getFirst());
        assertEquals(CallFlowPlayback.TransitionType.CALL,
                playback.currentVisit().transitionType());
        assertExactExecution(
                playback.currentVisit(),
                "Main thread",
                List.of("onCreate", "render content"),
                "render"
        );

        playback.jumpTo("worker");
        assertEquals(CallFlowPlayback.TransitionType.DIRECT_JUMP,
                playback.currentVisit().transitionType());
        assertNull(playback.currentVisit().viaEdge());
        assertExactExecution(
                playback.currentVisit(),
                "Worker coroutine",
                List.of("load data"),
                "loading"
        );
    }

    @Test
    void contextFlowKeepsStepOverVisibleInsteadOfPresentingItAsNext() {
        CallFlowContext main = new CallFlowContext(
                "main",
                ContextKind.THREAD,
                "Main thread",
                null
        );
        CallFlowFrame root = new CallFlowFrame(
                "root",
                FrameKind.FUNCTION,
                "MainActivity.onCreate",
                null
        );
        CallFlowExecution execution = new CallFlowExecution("main", List.of("root"), null);
        CallFlowNode entry = contextNode("entry", NodeKind.CALL, 1, execution);
        CallFlowNode after = contextNode("after", NodeKind.CALL, 2, execution);
        CallFlow flow = new CallFlow(
                CallFlow.CONTEXT_VERSION,
                "Step over",
                List.of(entry, after),
                List.of(new CallFlowEdge(
                        "entry",
                        "after",
                        EdgeKind.STEP_OVER,
                        "Skip implementation",
                        new CallFlowTransition(TransitionKind.CONTINUE)
                )),
                "entry",
                List.of(main),
                List.of(root)
        );
        CallFlowPlayback playback = new CallFlowPlayback();
        playback.load(flow);

        playback.choose(playback.stepOver().getFirst());

        assertEquals(
                CallFlowPlayback.TransitionType.STEP_OVER,
                playback.currentVisit().transitionType()
        );
    }

    @Test
    void executionStateDefensivelyCopiesItsStack() {
        List<String> mutableStack = new ArrayList<>(List.of("root"));

        CallFlowPlayback.ExecutionState state = new CallFlowPlayback.ExecutionState(
                "Main",
                mutableStack,
                null,
                true
        );
        mutableStack.add("child");

        assertEquals(List.of("root"), state.stack());
        assertEquals(0, state.depth());
        assertThrows(UnsupportedOperationException.class, () -> state.stack().add("child"));
    }

    @Test
    void tracksActivePathLatestVisitsAndExplorationStatistics() {
        CallFlowPlayback playback = new CallFlowPlayback();
        assertEquals(0, playback.pathStep());
        assertEquals(0, playback.totalNodeCount());
        assertTrue(playback.currentPath().isEmpty());

        playback.load(flow());
        CallFlowPlayback.Visit entryVisit = playback.currentVisit();
        assertEquals(1, playback.pathStep());
        assertEquals(1, playback.visitedNodeCount());
        assertEquals(4, playback.totalNodeCount());
        assertEquals(2, playback.unexploredEdgeCount());
        assertTrue(playback.hasVisited("entry"));
        assertFalse(playback.hasVisited("inside"));
        assertSame(entryVisit, playback.latestVisit("entry"));
        assertNull(playback.latestVisit("missing"));

        playback.choose(playback.stepInto().getFirst());
        CallFlowPlayback.Visit insideVisit = playback.currentVisit();
        assertEquals(2, playback.pathStep());
        assertEquals(List.of("entry", "inside"), visitIds(playback.currentPath()));
        assertEquals(2, playback.visitedNodeCount());
        assertEquals(2, playback.unexploredEdgeCount());
        assertSame(insideVisit, playback.latestVisit("inside"));

        playback.choose(playback.stepOut().getFirst());
        assertEquals(3, playback.pathStep());
        assertEquals(3, playback.visitedNodeCount());
        assertEquals(1, playback.unexploredEdgeCount());

        playback.previous();
        assertEquals(2, playback.pathStep());
        assertEquals(List.of("entry", "inside"), visitIds(playback.currentPath()));
        assertSame(insideVisit, playback.currentVisit());
        assertEquals(3, playback.visitedNodeCount());
        assertSame(insideVisit, playback.latestVisit("inside"));
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
        assertEquals(1, playback.visitedNodeCount());
        assertEquals(1, playback.pathStep());
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

    private static List<String> visitIds(List<CallFlowPlayback.Visit> visits) {
        return visits.stream().map(visit -> visit.node().id()).toList();
    }

    private static void assertExactExecution(
            CallFlowPlayback.Visit visit,
            String context,
            List<String> stack,
            String phase
    ) {
        assertEquals(context, visit.execution().contextLabel());
        assertEquals(stack, visit.execution().stack());
        assertEquals(phase, visit.execution().phase());
        assertTrue(visit.execution().exact());
        assertEquals(Math.max(0, stack.size() - 1), visit.execution().depth());
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

    private static CallFlow contextFlow() {
        CallFlowContext main = new CallFlowContext(
                "main",
                ContextKind.THREAD,
                "Main thread",
                null
        );
        CallFlowContext worker = new CallFlowContext(
                "worker-context",
                ContextKind.COROUTINE,
                "Worker coroutine",
                "main"
        );
        CallFlowFrame root = new CallFlowFrame(
                "root-frame",
                FrameKind.FUNCTION,
                "onCreate",
                "com.example.MainActivity.onCreate"
        );
        CallFlowFrame render = new CallFlowFrame(
                "render-frame",
                FrameKind.LAMBDA,
                "render content",
                null
        );
        CallFlowFrame load = new CallFlowFrame(
                "load-frame",
                FrameKind.FUNCTION,
                "load data",
                "com.example.Repository.load"
        );
        CallFlowNode entry = contextNode(
                "entry",
                NodeKind.ENTRY,
                1,
                new CallFlowExecution("main", List.of("root-frame"), "startup")
        );
        CallFlowNode inside = contextNode(
                "inside",
                NodeKind.CALL,
                2,
                new CallFlowExecution("main", List.of("root-frame", "render-frame"), "render")
        );
        CallFlowNode workerNode = contextNode(
                "worker",
                NodeKind.ASYNC,
                3,
                new CallFlowExecution("worker-context", List.of("load-frame"), "loading")
        );
        return new CallFlow(
                CallFlow.CONTEXT_VERSION,
                "Context flow",
                List.of(entry, inside, workerNode),
                List.of(new CallFlowEdge(
                        "entry",
                        "inside",
                        EdgeKind.STEP_INTO,
                        "enter render",
                        new CallFlowTransition(TransitionKind.CALL)
                )),
                "entry",
                List.of(main, worker),
                List.of(root, render, load)
        );
    }

    private static CallFlowNode contextNode(
            String id,
            NodeKind kind,
            int line,
            CallFlowExecution execution
    ) {
        return new CallFlowNode(
                id,
                kind,
                new CallFlowLocation("app/src/Main.kt", line, 1, null, null, null, null),
                "Node " + id,
                execution
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
