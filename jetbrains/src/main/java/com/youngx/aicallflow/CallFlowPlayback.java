package com.youngx.aicallflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private Map<String, CallFlowContext> contextsById = Map.of();
    private Map<String, CallFlowFrame> framesById = Map.of();
    private Map<String, List<Candidate>> outgoingByNodeId = Map.of();
    private Visit currentVisit;
    private final List<Visit> previousVisits = new ArrayList<>();
    private final Deque<Visit> forwardVisits = new ArrayDeque<>();
    private final Set<String> visitedNodeIds = new LinkedHashSet<>();
    private final Set<CallFlowEdge> visitedEdges =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Visit> latestVisitsByNodeId = new LinkedHashMap<>();

    /** The semantic reason playback moved from one node to another. */
    public enum TransitionType {
        ENTRY,
        CONTINUE,
        CALL,
        RETURN,
        BRANCH,
        LOOP_BACK,
        LOOP_EXIT,
        ASYNC,
        ASYNC_FORK,
        ASYNC_RESUME,
        ASYNC_JOIN,
        CALLBACK,
        CALLBACK_ENTER,
        CALLBACK_RETURN,
        STEP_OVER,
        DIRECT_JUMP,
        UNKNOWN
    }

    /** The execution context and root-to-current call stack for one visit. */
    public record ExecutionState(
            String contextLabel,
            List<String> stack,
            String phase,
            boolean exact
    ) {
        public ExecutionState {
            stack = List.copyOf(Objects.requireNonNull(stack, "stack"));
        }

        public int depth() {
            return Math.max(0, stack.size() - 1);
        }
    }

    /** A node visit together with the transition and execution snapshot that produced it. */
    public record Visit(
            CallFlowNode node,
            CallFlowEdge viaEdge,
            TransitionType transitionType,
            ExecutionState execution
    ) {
        public Visit {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(transitionType, "transitionType");
            Objects.requireNonNull(execution, "execution");
        }
    }

    /** An outgoing edge paired with its resolved destination node. */
    public record Candidate(CallFlowEdge edge, CallFlowNode node) {
        public Candidate {
            Objects.requireNonNull(edge, "edge");
            Objects.requireNonNull(node, "node");
        }
    }

    /** Loads a flow at its entry and clears all navigation and visitation history. */
    public void load(CallFlow newFlow) {
        CallFlowValidation.validate(newFlow);

        Map<String, CallFlowNode> indexedNodes = new LinkedHashMap<>();
        for (CallFlowNode node : newFlow.nodes()) {
            indexedNodes.put(node.id(), node);
        }

        Map<String, CallFlowContext> indexedContexts = new LinkedHashMap<>();
        if (newFlow.contexts() != null) {
            for (CallFlowContext context : newFlow.contexts()) {
                indexedContexts.put(context.id(), context);
            }
        }

        Map<String, CallFlowFrame> indexedFrames = new LinkedHashMap<>();
        if (newFlow.frames() != null) {
            for (CallFlowFrame frame : newFlow.frames()) {
                indexedFrames.put(frame.id(), frame);
            }
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
        contextsById = Collections.unmodifiableMap(indexedContexts);
        framesById = Collections.unmodifiableMap(indexedFrames);
        outgoingByNodeId = Collections.unmodifiableMap(indexedOutgoing);
        previousVisits.clear();
        forwardVisits.clear();
        visitedNodeIds.clear();
        visitedEdges.clear();
        latestVisitsByNodeId.clear();

        CallFlowNode entry = indexedNodes.get(newFlow.entry());
        currentVisit = createVisit(entry, null, TransitionType.ENTRY, entryExecution(entry));
    }

    public CallFlow flow() {
        return flow;
    }

    /** Returns the current node, or {@code null} before a flow is loaded. */
    public CallFlowNode current() {
        return currentVisit == null ? null : currentVisit.node();
    }

    /** Returns the current visit snapshot, or {@code null} before a flow is loaded. */
    public Visit currentVisit() {
        return currentVisit;
    }

    /** Returns the active history path, including the current visit. */
    public List<Visit> currentPath() {
        if (currentVisit == null) {
            return List.of();
        }
        List<Visit> path = new ArrayList<>(previousVisits.size() + 1);
        path.addAll(previousVisits);
        path.add(currentVisit);
        return List.copyOf(path);
    }

    /** Returns the current one-based step in the active path, or zero before load. */
    public int pathStep() {
        return currentVisit == null ? 0 : previousVisits.size() + 1;
    }

    /** Number of distinct nodes visited since the current flow was loaded. */
    public int visitedNodeCount() {
        return visitedNodeIds.size();
    }

    /** Total number of nodes in the loaded flow, or zero before load. */
    public int totalNodeCount() {
        return flow == null ? 0 : flow.nodes().size();
    }

    /**
     * Counts unvisited edges whose source node has already been visited. These
     * are the currently discoverable, unexplored branches of the flow.
     */
    public int unexploredEdgeCount() {
        if (flow == null) {
            return 0;
        }
        int count = 0;
        for (CallFlowEdge edge : flow.edges()) {
            if (visitedNodeIds.contains(edge.from()) && !visitedEdges.contains(edge)) {
                count++;
            }
        }
        return count;
    }

    public boolean hasVisited(String nodeId) {
        return visitedNodeIds.contains(nodeId);
    }

    /** Returns the most recently created visit for a node, or {@code null}. */
    public Visit latestVisit(String nodeId) {
        return latestVisitsByNodeId.get(nodeId);
    }

    public boolean canPrevious() {
        return !previousVisits.isEmpty();
    }

    public boolean canForward() {
        return !forwardVisits.isEmpty();
    }

    /** Moves to the previous visit snapshot, or returns {@code null} if unavailable. */
    public CallFlowNode previous() {
        requireLoaded();
        if (previousVisits.isEmpty()) {
            return null;
        }

        forwardVisits.push(currentVisit);
        currentVisit = previousVisits.remove(previousVisits.size() - 1);
        return current();
    }

    /** Moves through history toward the newest visit snapshot, or returns {@code null}. */
    public CallFlowNode forward() {
        requireLoaded();
        if (forwardVisits.isEmpty()) {
            return null;
        }

        previousVisits.add(currentVisit);
        currentVisit = forwardVisits.pop();
        return current();
    }

    /** Returns every outgoing candidate in protocol order. */
    public List<Candidate> next() {
        requireLoaded();
        return outgoingByNodeId.getOrDefault(current().id(), List.of());
    }

    public List<Candidate> stepInto() {
        return candidatesFor(EdgeKind.STEP_INTO);
    }

    public List<Candidate> stepOver() {
        return candidatesFor(EdgeKind.STEP_OVER);
    }

    public List<Candidate> stepOut() {
        return candidatesFor(EdgeKind.STEP_OUT);
    }

    /**
     * Selects a candidate returned for the current node. Selecting a new path
     * always drops forward history, matching browser/debugger navigation.
     */
    public CallFlowNode choose(Candidate candidate) {
        requireLoaded();
        Objects.requireNonNull(candidate, "candidate");

        Candidate actual = next().stream()
                .filter(value -> value == candidate)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Candidate is not an outgoing edge of the current node"
                ));
        TransitionType transitionType = transitionType(actual.edge());
        ExecutionState execution = targetExecution(actual.node(), transitionType);
        moveTo(createVisit(actual.node(), actual.edge(), transitionType, execution));
        return current();
    }

    /**
     * Jumps to a flow node. A unique edge from the current node to the target
     * preserves its transition semantics; other jumps are recorded as direct.
     */
    public CallFlowNode jumpTo(String nodeId) {
        requireLoaded();
        CallFlowNode target = nodesById.get(nodeId);
        if (target == null) {
            throw new IllegalArgumentException("Unknown call-flow node: " + nodeId);
        }
        if (Objects.equals(current().id(), nodeId)) {
            return current();
        }

        List<Candidate> matchingCandidates = next().stream()
                .filter(candidate -> candidate.node().id().equals(nodeId))
                .toList();
        if (matchingCandidates.size() == 1) {
            return choose(matchingCandidates.getFirst());
        }

        ExecutionState execution = isContextFlow()
                ? exactExecution(target)
                : legacyInitialExecution(target);
        moveTo(createVisit(target, null, TransitionType.DIRECT_JUMP, execution));
        return current();
    }

    private List<Candidate> candidatesFor(EdgeKind kind) {
        requireLoaded();
        return next().stream()
                .filter(candidate -> candidate.edge().kind() == kind)
                .toList();
    }

    private void moveTo(Visit visit) {
        previousVisits.add(currentVisit);
        currentVisit = visit;
        forwardVisits.clear();
    }

    private Visit createVisit(
            CallFlowNode node,
            CallFlowEdge viaEdge,
            TransitionType transitionType,
            ExecutionState execution
    ) {
        Visit visit = new Visit(node, viaEdge, transitionType, execution);
        visitedNodeIds.add(node.id());
        if (viaEdge != null) {
            visitedEdges.add(viaEdge);
        }
        latestVisitsByNodeId.put(node.id(), visit);
        return visit;
    }

    private ExecutionState entryExecution(CallFlowNode entry) {
        return isContextFlow() ? exactExecution(entry) : legacyInitialExecution(entry);
    }

    private ExecutionState targetExecution(CallFlowNode target, TransitionType transitionType) {
        if (isContextFlow()) {
            return exactExecution(target);
        }

        ExecutionState currentExecution = currentVisit.execution();
        List<String> stack = new ArrayList<>(currentExecution.stack());
        String contextLabel = currentExecution.contextLabel();
        switch (transitionType) {
            case CALL, CALLBACK -> stack.add(nodeLabel(target));
            case RETURN -> {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            }
            case ASYNC -> {
                contextLabel = nodeLabel(target);
                stack = new ArrayList<>(List.of(nodeLabel(target)));
            }
            default -> {
                // CONTINUE, BRANCH and STEP_OVER remain in the current frame.
            }
        }
        return new ExecutionState(contextLabel, stack, null, false);
    }

    private ExecutionState exactExecution(CallFlowNode node) {
        CallFlowExecution execution = Objects.requireNonNull(
                node.execution(),
                "1.1 node execution"
        );
        CallFlowContext context = contextsById.get(execution.context());
        String contextLabel = context == null
                ? execution.context()
                : preferredLabel(context.label(), context.id());
        List<String> stack = execution.stack().stream()
                .map(frameId -> {
                    CallFlowFrame frame = framesById.get(frameId);
                    if (frame == null) {
                        return frameId;
                    }
                    return preferredLabel(frame.label(), preferredLabel(frame.symbol(), frame.id()));
                })
                .toList();
        return new ExecutionState(contextLabel, stack, execution.phase(), true);
    }

    private ExecutionState legacyInitialExecution(CallFlowNode node) {
        return new ExecutionState(null, List.of(nodeLabel(node)), null, false);
    }

    private String nodeLabel(CallFlowNode node) {
        return preferredLabel(node.location().symbol(), node.id());
    }

    private static String preferredLabel(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private TransitionType transitionType(CallFlowEdge edge) {
        if (isContextFlow()) {
            if (edge.kind() == EdgeKind.STEP_OVER) {
                return TransitionType.STEP_OVER;
            }
            CallFlowTransition transition = edge.transition();
            return transition == null || transition.kind() == null
                    ? TransitionType.UNKNOWN
                    : switch (transition.kind()) {
                        case CONTINUE -> TransitionType.CONTINUE;
                        case CALL -> TransitionType.CALL;
                        case RETURN -> TransitionType.RETURN;
                        case BRANCH -> TransitionType.BRANCH;
                        case LOOP_BACK -> TransitionType.LOOP_BACK;
                        case LOOP_EXIT -> TransitionType.LOOP_EXIT;
                        case ASYNC_FORK -> TransitionType.ASYNC_FORK;
                        case ASYNC_RESUME -> TransitionType.ASYNC_RESUME;
                        case ASYNC_JOIN -> TransitionType.ASYNC_JOIN;
                        case CALLBACK_ENTER -> TransitionType.CALLBACK_ENTER;
                        case CALLBACK_RETURN -> TransitionType.CALLBACK_RETURN;
                    };
        }

        return switch (edge.kind()) {
            case NEXT -> TransitionType.CONTINUE;
            case STEP_INTO -> TransitionType.CALL;
            case STEP_OVER -> TransitionType.STEP_OVER;
            case STEP_OUT, RETURN -> TransitionType.RETURN;
            case BRANCH_TRUE, BRANCH_FALSE -> TransitionType.BRANCH;
            case ASYNC -> TransitionType.ASYNC;
            case CALLBACK -> TransitionType.CALLBACK;
        };
    }

    private boolean isContextFlow() {
        return flow != null && CallFlow.CONTEXT_VERSION.equals(flow.version());
    }

    private void requireLoaded() {
        if (flow == null) {
            throw new IllegalStateException("No call flow is loaded");
        }
    }
}
