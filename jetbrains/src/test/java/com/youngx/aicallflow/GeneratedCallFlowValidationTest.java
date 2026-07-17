package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratedCallFlowValidationTest {
    @Test
    void acceptsPairedIntoOverAndOut() {
        assertDoesNotThrow(() -> GeneratedCallFlowValidation.validate(validFlow()));
    }

    @Test
    void rejectsIntoWithoutOver() {
        CallFlow flow = validFlow();
        CallFlow broken = withEdges(
                flow,
                flow.edges().stream()
                        .filter(edge -> edge.kind() != EdgeKind.STEP_OVER)
                        .toList()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(broken)
        );
        assertTrue(error.getMessage().contains("Into but no Over"));
    }

    @Test
    void rejectsIntoPathThatDoesNotReturnToTheOverContinuation() {
        CallFlow flow = validFlow();
        List<CallFlowEdge> edges = new ArrayList<>(flow.edges());
        edges.removeIf(edge -> edge.kind() == EdgeKind.STEP_OUT);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(withEdges(flow, edges))
        );
        assertTrue(error.getMessage().contains("terminates before its Out continuation"));
    }

    @Test
    void rejectsCallbackEnterPairedWithOrdinaryReturn() {
        CallFlow flow = callbackFlow();
        List<CallFlowEdge> edges = flow.edges().stream()
                .map(edge -> edge.kind() == EdgeKind.STEP_OUT
                        ? edge(edgeFrom(flow, edge), edgeTo(flow, edge), EdgeKind.STEP_OUT,
                        TransitionKind.RETURN)
                        : edge)
                .toList();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(withEdges(flow, edges))
        );
        assertTrue(error.getMessage().contains("function return must pop a function frame"));
    }

    @Test
    void rejectsFunctionCallPairedWithCallbackReturn() {
        CallFlow flow = validFlow();
        List<CallFlowEdge> edges = flow.edges().stream()
                .map(edge -> edge.kind() == EdgeKind.STEP_OUT
                        ? edge(edgeFrom(flow, edge), edgeTo(flow, edge), EdgeKind.STEP_OUT,
                        TransitionKind.CALLBACK_RETURN)
                        : edge)
                .toList();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(withEdges(flow, edges))
        );
        assertTrue(error.getMessage().contains("callback return must pop a lambda/callback frame"));
    }

    @Test
    void rejectsWhenOneBranchTerminatesInsideEnteredFrame() {
        CallFlow flow = validFlow();
        CallFlowNode callee = flow.nodes().stream()
                .filter(node -> node.id().equals("callee"))
                .findFirst()
                .orElseThrow();
        CallFlowNode calleeExit = flow.nodes().stream()
                .filter(node -> node.id().equals("callee-exit"))
                .findFirst()
                .orElseThrow();
        CallFlowNode deadEnd = node(
                "dead-end",
                NodeKind.RETURN,
                6,
                callee.execution()
        );
        List<CallFlowNode> nodes = new ArrayList<>(flow.nodes());
        nodes.add(deadEnd);
        List<CallFlowEdge> edges = new ArrayList<>(flow.edges());
        edges.removeIf(edge -> edge.from().equals(callee.id()) && edge.to().equals(calleeExit.id()));
        edges.add(edge(callee, calleeExit, EdgeKind.BRANCH_TRUE, TransitionKind.BRANCH));
        edges.add(edge(callee, deadEnd, EdgeKind.BRANCH_FALSE, TransitionKind.BRANCH));
        CallFlow broken = withNodesAndEdges(flow, nodes, edges);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(broken)
        );
        assertTrue(error.getMessage().contains("terminates before its Out continuation"));
    }

    @Test
    void rejectsWhenOneEnteredPathCanCycleForever() {
        CallFlow flow = validFlow();
        CallFlowNode callee = flow.nodes().stream()
                .filter(node -> node.id().equals("callee"))
                .findFirst()
                .orElseThrow();
        CallFlowNode calleeExit = flow.nodes().stream()
                .filter(node -> node.id().equals("callee-exit"))
                .findFirst()
                .orElseThrow();
        List<CallFlowEdge> edges = new ArrayList<>(flow.edges());
        edges.removeIf(edge -> edge.from().equals(callee.id()) && edge.to().equals(calleeExit.id()));
        edges.add(edge(callee, callee, EdgeKind.BRANCH_TRUE, TransitionKind.BRANCH));
        edges.add(edge(callee, calleeExit, EdgeKind.BRANCH_FALSE, TransitionKind.BRANCH));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(withEdges(flow, edges))
        );
        assertTrue(error.getMessage().contains("cycle without returning"));
    }

    @Test
    void rejectsOutToDifferentCallerContinuation() {
        CallFlow flow = validFlow();
        CallFlowNode alternate = node(
                "alternate-continuation",
                NodeKind.RETURN,
                7,
                flow.nodes().getFirst().execution()
        );
        List<CallFlowNode> nodes = new ArrayList<>(flow.nodes());
        nodes.add(alternate);
        List<CallFlowEdge> edges = flow.edges().stream()
                .map(edge -> edge.kind() == EdgeKind.STEP_OUT
                        ? edge(edgeFrom(flow, edge), alternate, EdgeKind.STEP_OUT,
                        TransitionKind.RETURN)
                        : edge)
                .toList();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(withNodesAndEdges(flow, nodes, edges))
        );
        assertTrue(error.getMessage().contains("mismatched Out continuation"));
    }

    @Test
    void rejectsReturnThatPopsMultipleFrames() {
        CallFlow flow = multiFramePopFlow();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(flow)
        );
        assertTrue(error.getMessage().contains("pop exactly one frame"));
    }

    @Test
    void rejectsUnreachableGeneratedNodes() {
        CallFlow flow = validFlow();
        List<CallFlowNode> nodes = new ArrayList<>(flow.nodes());
        nodes.add(node(
                "orphan",
                NodeKind.RETURN,
                8,
                new CallFlowExecution("static", List.of("root"), null)
        ));
        CallFlow broken = new CallFlow(
                flow.version(),
                flow.title(),
                nodes,
                flow.edges(),
                flow.entry(),
                flow.contexts(),
                flow.frames()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedCallFlowValidation.validate(broken)
        );
        assertTrue(error.getMessage().contains("unreachable"));
    }

    @Test
    void acceptsOpaqueLibraryCallWithOnlyStepOver() {
        CallFlowFrame root = new CallFlowFrame("root", FrameKind.FUNCTION, "root", null);
        CallFlowExecution execution = new CallFlowExecution("static", List.of("root"), null);
        CallFlowNode entry = node("entry", NodeKind.ENTRY, 1, execution);
        CallFlowNode call = node("call", NodeKind.CALL, 2, execution);
        CallFlowNode exit = node("exit", NodeKind.RETURN, 3, execution);
        CallFlow flow = new CallFlow(
                CallFlow.CONTEXT_VERSION,
                "Opaque call",
                List.of(entry, call, exit),
                List.of(
                        edge(entry, call, EdgeKind.NEXT, TransitionKind.CONTINUE),
                        edge(call, exit, EdgeKind.STEP_OVER, TransitionKind.CONTINUE)
                ),
                entry.id(),
                List.of(new CallFlowContext("static", ContextKind.TASK, "Static", null)),
                List.of(root)
        );

        assertDoesNotThrow(() -> GeneratedCallFlowValidation.validate(flow));
    }

    private static CallFlow validFlow() {
        CallFlowFrame root = new CallFlowFrame("root", FrameKind.FUNCTION, "root", null);
        CallFlowFrame child = new CallFlowFrame("child", FrameKind.FUNCTION, "child", null);
        CallFlowExecution rootExecution = new CallFlowExecution("static", List.of("root"), null);
        CallFlowExecution childExecution = new CallFlowExecution(
                "static",
                List.of("root", "child"),
                null
        );
        CallFlowNode entry = node("entry", NodeKind.ENTRY, 1, rootExecution);
        CallFlowNode call = node("call", NodeKind.CALL, 2, rootExecution);
        CallFlowNode callee = node("callee", NodeKind.DECLARATION, 4, childExecution);
        CallFlowNode calleeExit = node("callee-exit", NodeKind.RETURN, 5, childExecution);
        CallFlowNode continuation = node("continuation", NodeKind.RETURN, 3, rootExecution);
        return new CallFlow(
                CallFlow.CONTEXT_VERSION,
                "Generated flow",
                List.of(entry, call, callee, calleeExit, continuation),
                List.of(
                        edge(entry, call, EdgeKind.NEXT, TransitionKind.CONTINUE),
                        edge(call, callee, EdgeKind.STEP_INTO, TransitionKind.CALL),
                        edge(call, continuation, EdgeKind.STEP_OVER, TransitionKind.CONTINUE),
                        edge(callee, calleeExit, EdgeKind.NEXT, TransitionKind.CONTINUE),
                        edge(calleeExit, continuation, EdgeKind.STEP_OUT, TransitionKind.RETURN)
                ),
                entry.id(),
                List.of(new CallFlowContext("static", ContextKind.TASK, "Static", null)),
                List.of(root, child)
        );
    }

    private static CallFlow callbackFlow() {
        CallFlow flow = validFlow();
        CallFlowFrame callback = new CallFlowFrame(
                "child",
                FrameKind.LAMBDA,
                "callback",
                null
        );
        List<CallFlowEdge> edges = flow.edges().stream()
                .map(edge -> edge.kind() == EdgeKind.STEP_INTO
                        ? edge(edgeFrom(flow, edge), edgeTo(flow, edge), EdgeKind.STEP_INTO,
                        TransitionKind.CALLBACK_ENTER)
                        : edge.kind() == EdgeKind.STEP_OUT
                        ? edge(edgeFrom(flow, edge), edgeTo(flow, edge), EdgeKind.STEP_OUT,
                        TransitionKind.CALLBACK_RETURN)
                        : edge)
                .toList();
        return new CallFlow(
                flow.version(),
                flow.title(),
                flow.nodes(),
                edges,
                flow.entry(),
                flow.contexts(),
                List.of(flow.frames().getFirst(), callback)
        );
    }

    private static CallFlow multiFramePopFlow() {
        CallFlowFrame root = new CallFlowFrame("root", FrameKind.FUNCTION, "root", null);
        CallFlowFrame child = new CallFlowFrame("child", FrameKind.FUNCTION, "child", null);
        CallFlowFrame grandchild = new CallFlowFrame(
                "grandchild",
                FrameKind.FUNCTION,
                "grandchild",
                null
        );
        CallFlowExecution rootExecution = new CallFlowExecution("static", List.of("root"), null);
        CallFlowExecution childExecution = new CallFlowExecution(
                "static",
                List.of("root", "child"),
                null
        );
        CallFlowExecution grandchildExecution = new CallFlowExecution(
                "static",
                List.of("root", "child", "grandchild"),
                null
        );
        CallFlowNode entry = node("entry", NodeKind.ENTRY, 1, rootExecution);
        CallFlowNode outerCall = node("outer-call", NodeKind.CALL, 2, rootExecution);
        CallFlowNode childCall = node("child-call", NodeKind.CALL, 3, childExecution);
        CallFlowNode childExit = node("child-exit", NodeKind.RETURN, 4, childExecution);
        CallFlowNode grandchildExit = node(
                "grandchild-exit",
                NodeKind.RETURN,
                5,
                grandchildExecution
        );
        CallFlowNode continuation = node("continuation", NodeKind.RETURN, 6, rootExecution);
        return new CallFlow(
                CallFlow.CONTEXT_VERSION,
                "Multi-frame pop",
                List.of(entry, outerCall, childCall, childExit, grandchildExit, continuation),
                List.of(
                        edge(entry, outerCall, EdgeKind.NEXT, TransitionKind.CONTINUE),
                        edge(outerCall, childCall, EdgeKind.STEP_INTO, TransitionKind.CALL),
                        edge(outerCall, continuation, EdgeKind.STEP_OVER, TransitionKind.CONTINUE),
                        edge(childCall, grandchildExit, EdgeKind.STEP_INTO, TransitionKind.CALL),
                        edge(childCall, childExit, EdgeKind.STEP_OVER, TransitionKind.CONTINUE),
                        edge(grandchildExit, continuation, EdgeKind.STEP_OUT, TransitionKind.RETURN),
                        edge(childExit, continuation, EdgeKind.STEP_OUT, TransitionKind.RETURN)
                ),
                entry.id(),
                List.of(new CallFlowContext("static", ContextKind.TASK, "Static", null)),
                List.of(root, child, grandchild)
        );
    }

    private static CallFlow withEdges(CallFlow flow, List<CallFlowEdge> edges) {
        return new CallFlow(
                flow.version(),
                flow.title(),
                flow.nodes(),
                edges,
                flow.entry(),
                flow.contexts(),
                flow.frames()
        );
    }

    private static CallFlow withNodesAndEdges(
            CallFlow flow,
            List<CallFlowNode> nodes,
            List<CallFlowEdge> edges
    ) {
        return new CallFlow(
                flow.version(),
                flow.title(),
                nodes,
                edges,
                flow.entry(),
                flow.contexts(),
                flow.frames()
        );
    }

    private static CallFlowNode edgeFrom(CallFlow flow, CallFlowEdge edge) {
        return flow.nodes().stream()
                .filter(node -> node.id().equals(edge.from()))
                .findFirst()
                .orElseThrow();
    }

    private static CallFlowNode edgeTo(CallFlow flow, CallFlowEdge edge) {
        return flow.nodes().stream()
                .filter(node -> node.id().equals(edge.to()))
                .findFirst()
                .orElseThrow();
    }

    private static CallFlowEdge edge(
            CallFlowNode from,
            CallFlowNode to,
            EdgeKind kind,
            TransitionKind transition
    ) {
        return new CallFlowEdge(
                from.id(),
                to.id(),
                kind,
                kind.name(),
                new CallFlowTransition(transition)
        );
    }

    private static CallFlowNode node(
            String id,
            NodeKind kind,
            int line,
            CallFlowExecution execution
    ) {
        return new CallFlowNode(
                id,
                kind,
                new CallFlowLocation("src/Main.kt", line, 1, null, null, id, null),
                "Node " + id,
                execution
        );
    }
}
