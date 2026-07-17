package com.youngx.aicallflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure Java mapping from debugger source samples to immutable static Call Flow nodes. */
public final class TraceNodeMapper {
    public record Match(
            String nodeId,
            EdgeKind viaEdgeKind,
            TraceMatchConfidence confidence,
            List<String> candidateNodeIds
    ) {
        public Match {
            candidateNodeIds = List.copyOf(candidateNodeIds);
        }

        static Match unmatched() {
            return new Match(null, null, TraceMatchConfidence.UNMATCHED, List.of());
        }
    }

    private final Map<String, List<CallFlowNode>> nodesByPath;
    private final Map<String, Map<String, List<CallFlowEdge>>> edgesByEndpoints;

    public TraceNodeMapper(CallFlow flow) {
        Objects.requireNonNull(flow, "flow");
        Map<String, List<CallFlowNode>> pathIndex = new LinkedHashMap<>();
        for (CallFlowNode node : flow.nodes()) {
            CallFlowLocation location = node.location();
            if (location == null || location.path() == null || location.path().isBlank()) {
                continue;
            }
            pathIndex.computeIfAbsent(
                    TraceSourcePosition.normalizePath(location.path()),
                    ignored -> new ArrayList<>()
            ).add(node);
        }
        pathIndex.replaceAll((ignored, nodes) -> List.copyOf(nodes));
        nodesByPath = Map.copyOf(pathIndex);

        Map<String, Map<String, List<CallFlowEdge>>> endpointIndex = new LinkedHashMap<>();
        for (CallFlowEdge edge : flow.edges()) {
            endpointIndex
                    .computeIfAbsent(edge.from(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(edge.to(), ignored -> new ArrayList<>())
                    .add(edge);
        }
        Map<String, Map<String, List<CallFlowEdge>>> immutableEndpoints = new LinkedHashMap<>();
        endpointIndex.forEach((from, targets) -> {
            Map<String, List<CallFlowEdge>> immutableTargets = new LinkedHashMap<>();
            targets.forEach((to, edges) -> immutableTargets.put(to, List.copyOf(edges)));
            immutableEndpoints.put(from, Map.copyOf(immutableTargets));
        });
        edgesByEndpoints = Map.copyOf(immutableEndpoints);
    }

    public Match match(TraceSourcePosition source, String previousNodeId) {
        Objects.requireNonNull(source, "source");
        List<CallFlowNode> pathNodes = nodesByPath.getOrDefault(source.path(), List.of());
        List<CallFlowNode> candidates = pathNodes.stream()
                .filter(node -> covers(node.location(), source.line()))
                .toList();
        if (candidates.isEmpty()) {
            return Match.unmatched();
        }

        List<ScoredNode> scored = candidates.stream()
                .map(node -> score(node, source, previousNodeId))
                .toList();
        int bestScore = scored.stream().mapToInt(ScoredNode::score).max().orElse(0);
        List<ScoredNode> winners = scored.stream()
                .filter(candidate -> candidate.score() == bestScore)
                .toList();
        List<String> candidateIds = candidates.stream().map(CallFlowNode::id).toList();
        if (winners.size() != 1) {
            return new Match(null, null, TraceMatchConfidence.AMBIGUOUS, candidateIds);
        }

        ScoredNode winner = winners.getFirst();
        TraceMatchConfidence confidence;
        if (winner.exactSymbol()) {
            confidence = TraceMatchConfidence.EXACT;
        } else if (winner.adjacent() && candidates.size() > 1) {
            confidence = TraceMatchConfidence.ADJACENT;
        } else if (winner.exactLine()) {
            confidence = TraceMatchConfidence.LINE;
        } else {
            confidence = TraceMatchConfidence.RANGE;
        }
        return new Match(
                winner.node().id(),
                firstEdgeKind(previousNodeId, winner.node().id()),
                confidence,
                candidateIds
        );
    }

    private ScoredNode score(
            CallFlowNode node,
            TraceSourcePosition source,
            String previousNodeId
    ) {
        CallFlowLocation location = node.location();
        int score = 100;
        boolean exactLine = location.line() == source.line();
        if (exactLine) {
            score += 20;
        }

        boolean exactSymbol = source.symbol() != null
                && source.symbol().equals(location.symbol());
        if (exactSymbol) {
            score += 100;
        } else if (sameSimpleSymbol(source.symbol(), location.symbol())) {
            score += 35;
        }

        boolean adjacent = hasEdge(previousNodeId, node.id());
        if (adjacent) {
            score += 70;
        } else if (Objects.equals(previousNodeId, node.id())) {
            score += 20;
        }
        return new ScoredNode(node, score, exactLine, exactSymbol, adjacent);
    }

    private boolean hasEdge(String from, String to) {
        return from != null
                && edgesByEndpoints.getOrDefault(from, Map.of()).containsKey(to);
    }

    private EdgeKind firstEdgeKind(String from, String to) {
        if (from == null) {
            return null;
        }
        List<CallFlowEdge> edges = edgesByEndpoints
                .getOrDefault(from, Map.of())
                .getOrDefault(to, List.of());
        return edges.isEmpty() ? null : edges.getFirst().kind();
    }

    private static boolean covers(CallFlowLocation location, int line) {
        int endLine = location.endLine() == null
                ? location.line()
                : Math.max(location.line(), location.endLine());
        return line >= location.line() && line <= endLine;
    }

    private static boolean sameSimpleSymbol(String first, String second) {
        return first != null
                && second != null
                && simpleSymbol(first).equals(simpleSymbol(second));
    }

    private static String simpleSymbol(String symbol) {
        int separator = symbol.lastIndexOf('.');
        String simple = separator < 0 ? symbol : symbol.substring(separator + 1);
        int defaultSuffix = simple.indexOf("$default");
        if (defaultSuffix >= 0) {
            simple = simple.substring(0, defaultSuffix);
        }
        int mangledSuffix = simple.indexOf('-');
        return mangledSuffix < 0 ? simple : simple.substring(0, mangledSuffix);
    }

    private record ScoredNode(
            CallFlowNode node,
            int score,
            boolean exactLine,
            boolean exactSymbol,
            boolean adjacent
    ) {
    }
}
