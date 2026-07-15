package com.youngx.aicallflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure Java playback state for a {@link CallFlow}.
 *
 * <p>Candidate methods never mutate the current position. Callers choose one of
 * the returned candidates with {@link #choose(Candidate)}. This keeps branching
 * deterministic and lets UI clients present all possible next steps.</p>
 */
public final class CallFlowPlayback {
    private CallFlow flow;
    private Map<String, CallFlowNode> nodesById = Map.of();
    private Map<String, List<Candidate>> outgoingByNodeId = Map.of();
    private String currentNodeId;
    private final List<String> previousNodeIds = new ArrayList<>();
    private final Deque<String> forwardNodeIds = new ArrayDeque<>();

    /** An outgoing edge paired with its resolved destination node. */
    public record Candidate(CallFlowEdge edge, CallFlowNode node) {
        public Candidate {
            Objects.requireNonNull(edge, "edge");
            Objects.requireNonNull(node, "node");
        }
    }

    /** Loads a flow at its entry and clears all navigation history. */
    public void load(CallFlow newFlow) {
        CallFlowValidation.validate(newFlow);

        Map<String, CallFlowNode> indexedNodes = new LinkedHashMap<>();
        for (CallFlowNode node : newFlow.nodes()) {
            indexedNodes.put(node.id(), node);
        }

        Map<String, List<Candidate>> indexedOutgoing = new LinkedHashMap<>();
        for (CallFlowEdge edge : newFlow.edges()) {
            CallFlowNode destination = indexedNodes.get(edge.to());
            indexedOutgoing
                    .computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
                    .add(new Candidate(edge, destination));
        }
        indexedOutgoing.replaceAll((ignored, candidates) -> List.copyOf(candidates));

        flow = newFlow;
        nodesById = Collections.unmodifiableMap(indexedNodes);
        outgoingByNodeId = Collections.unmodifiableMap(indexedOutgoing);
        currentNodeId = newFlow.entry();
        previousNodeIds.clear();
        forwardNodeIds.clear();
    }

    public CallFlow flow() {
        return flow;
    }

    /** Returns the current node, or {@code null} before a flow is loaded. */
    public CallFlowNode current() {
        return node(currentNodeId);
    }

    public boolean canPrevious() {
        return !previousNodeIds.isEmpty();
    }

    public boolean canForward() {
        return !forwardNodeIds.isEmpty();
    }

    /** Moves to the previous visited node, or returns {@code null} if unavailable. */
    public CallFlowNode previous() {
        requireLoaded();
        if (previousNodeIds.isEmpty()) {
            return null;
        }

        forwardNodeIds.push(currentNodeId);
        currentNodeId = previousNodeIds.remove(previousNodeIds.size() - 1);
        return current();
    }

    /** Moves through history toward the newest visited node, or returns {@code null}. */
    public CallFlowNode forward() {
        requireLoaded();
        if (forwardNodeIds.isEmpty()) {
            return null;
        }

        previousNodeIds.add(currentNodeId);
        currentNodeId = forwardNodeIds.pop();
        return current();
    }

    /** Returns every outgoing candidate in protocol order. */
    public List<Candidate> next() {
        requireLoaded();
        return outgoingByNodeId.getOrDefault(currentNodeId, List.of());
    }

    public List<Candidate> stepInto() {
        return candidatesFor(EnumSet.of(EdgeKind.STEP_INTO));
    }

    public List<Candidate> stepOver() {
        return candidatesFor(EnumSet.of(EdgeKind.STEP_OVER));
    }

    public List<Candidate> stepOut() {
        return candidatesFor(EnumSet.of(EdgeKind.STEP_OUT));
    }

    /**
     * Selects a candidate returned for the current node. Selecting a new path
     * always drops forward history, matching browser/debugger navigation.
     */
    public CallFlowNode choose(Candidate candidate) {
        requireLoaded();
        Objects.requireNonNull(candidate, "candidate");

        Candidate actual = next().stream()
                .filter(value -> value.equals(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Candidate is not an outgoing edge of the current node"
                ));
        moveTo(actual.node().id());
        return current();
    }

    /**
     * Jumps directly to a flow node. This is used by the node list and records
     * the old position in history just like choosing an edge.
     */
    public CallFlowNode jumpTo(String nodeId) {
        requireLoaded();
        if (!nodesById.containsKey(nodeId)) {
            throw new IllegalArgumentException("Unknown call-flow node: " + nodeId);
        }
        if (!Objects.equals(currentNodeId, nodeId)) {
            moveTo(nodeId);
        }
        return current();
    }

    private List<Candidate> candidatesFor(Set<EdgeKind> kinds) {
        requireLoaded();
        return next().stream()
                .filter(candidate -> kinds.contains(candidate.edge().kind()))
                .toList();
    }

    private void moveTo(String nodeId) {
        previousNodeIds.add(currentNodeId);
        currentNodeId = nodeId;
        forwardNodeIds.clear();
    }

    private CallFlowNode node(String nodeId) {
        return nodeId == null ? null : nodesById.get(nodeId);
    }

    private void requireLoaded() {
        if (flow == null) {
            throw new IllegalStateException("No call flow is loaded");
        }
    }
}
