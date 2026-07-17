package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class TraceNodeMapperTest {
    @Test
    void exactSymbolDisambiguatesNodesOnTheSameLine() {
        CallFlow flow = flow(
                List.of(
                        node("first", "src/Sample.kt", 12, 12, "sample.first"),
                        node("second", "src/Sample.kt", 12, 12, "sample.second")
                ),
                List.of()
        );

        TraceNodeMapper.Match match = new TraceNodeMapper(flow).match(
                new TraceSourcePosition("src/Sample.kt", 12, "sample.second"),
                null
        );

        assertEquals("second", match.nodeId());
        assertEquals(TraceMatchConfidence.EXACT, match.confidence());
        assertEquals(List.of("first", "second"), match.candidateNodeIds());
    }

    @Test
    void outgoingGraphEdgeDisambiguatesTheNextNode() {
        CallFlow flow = flow(
                List.of(
                        node("previous", "src/Sample.kt", 5, 5, "sample.previous"),
                        node("expected", "src/Sample.kt", 20, 20, "sample.expected"),
                        node("other", "src/Sample.kt", 20, 20, "sample.other")
                ),
                List.of(edge("previous", "expected", EdgeKind.STEP_INTO))
        );

        TraceNodeMapper.Match match = new TraceNodeMapper(flow).match(
                new TraceSourcePosition("src/Sample.kt", 20, null),
                "previous"
        );

        assertEquals("expected", match.nodeId());
        assertEquals(EdgeKind.STEP_INTO, match.viaEdgeKind());
        assertEquals(TraceMatchConfidence.ADJACENT, match.confidence());
    }

    @Test
    void unresolvedTieIsReportedInsteadOfGuessing() {
        CallFlow flow = flow(
                List.of(
                        node("first", "src/Sample.kt", 12, 12, "sample.first"),
                        node("second", "src/Sample.kt", 12, 12, "sample.second")
                ),
                List.of()
        );

        TraceNodeMapper.Match match = new TraceNodeMapper(flow).match(
                new TraceSourcePosition("src/Sample.kt", 12, null),
                null
        );

        assertNull(match.nodeId());
        assertEquals(TraceMatchConfidence.AMBIGUOUS, match.confidence());
        assertEquals(List.of("first", "second"), match.candidateNodeIds());
    }

    @Test
    void normalizedWindowsPathAndSourceRangeAreSupported() {
        CallFlow flow = flow(
                List.of(node("range", "src\\sample\\Sample.kt", 10, 16, "sample.range")),
                List.of()
        );

        TraceNodeMapper.Match match = new TraceNodeMapper(flow).match(
                new TraceSourcePosition("./src/sample/Sample.kt", 14, null),
                null
        );

        assertEquals("range", match.nodeId());
        assertEquals(TraceMatchConfidence.RANGE, match.confidence());
    }

    @Test
    void positionOutsideTheStaticGraphIsUnmatched() {
        CallFlow flow = flow(
                List.of(node("known", "src/Sample.kt", 10, 12, "sample.known")),
                List.of()
        );

        TraceNodeMapper.Match match = new TraceNodeMapper(flow).match(
                new TraceSourcePosition("src/Other.kt", 10, "sample.known"),
                null
        );

        assertNull(match.nodeId());
        assertEquals(TraceMatchConfidence.UNMATCHED, match.confidence());
        assertEquals(List.of(), match.candidateNodeIds());
    }

    private static CallFlow flow(List<CallFlowNode> nodes, List<CallFlowEdge> edges) {
        return new CallFlow("1.0", "test", nodes, edges, nodes.getFirst().id());
    }

    private static CallFlowNode node(
            String id,
            String path,
            int line,
            int endLine,
            String symbol
    ) {
        return new CallFlowNode(
                id,
                NodeKind.CALL,
                new CallFlowLocation(path, line, 1, endLine, 1, symbol, null),
                id
        );
    }

    private static CallFlowEdge edge(String from, String to, EdgeKind kind) {
        return new CallFlowEdge(from, to, kind, null);
    }
}
