package com.youngx.aicallflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strong graph checks used for deterministic IDE-generated Call Flows. */
final class GeneratedCallFlowValidation {
    private GeneratedCallFlowValidation() {
    }

    static void validate(CallFlow flow) {
        CallFlowValidation.validate(flow);
        Map<String, CallFlowNode> nodes = new HashMap<>();
        for (CallFlowNode node : flow.nodes()) {
            nodes.put(node.id(), node);
        }
        Map<String, List<CallFlowEdge>> outgoing = new HashMap<>();
        Set<String> edgeSignatures = new HashSet<>();
        for (CallFlowEdge edge : flow.edges()) {
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
            String signature = edge.from() + "\u0000" + edge.to() + "\u0000" + edge.kind()
                    + "\u0000" + edge.transition().kind();
            if (!edgeSignatures.add(signature)) {
                throw invalid("duplicate generated edge: " + edge.from() + " -> " + edge.to());
            }
        }

        Set<String> reachable = reachable(flow.entry(), outgoing);
        if (reachable.size() != flow.nodes().size()) {
            List<String> missing = flow.nodes().stream()
                    .map(CallFlowNode::id)
                    .filter(id -> !reachable.contains(id))
                    .toList();
            throw invalid("generated nodes are unreachable from entry: " + String.join(", ", missing));
        }

        Map<String, CallFlowFrame> frames = new HashMap<>();
        for (CallFlowFrame frame : flow.frames()) {
            frames.put(frame.id(), frame);
        }
        for (CallFlowEdge edge : flow.edges()) {
            validateReturnFrame(edge, nodes, frames);
        }
        for (CallFlowNode node : flow.nodes()) {
            List<CallFlowEdge> candidates = outgoing.getOrDefault(node.id(), List.of());
            if (candidates.isEmpty() && node.kind() != NodeKind.RETURN) {
                throw invalid("generated non-return node has no continuation: " + node.id());
            }
            List<CallFlowEdge> into = candidates.stream()
                    .filter(edge -> edge.kind() == EdgeKind.STEP_INTO)
                    .toList();
            List<CallFlowEdge> over = candidates.stream()
                    .filter(edge -> edge.kind() == EdgeKind.STEP_OVER)
                    .toList();
            if (!into.isEmpty() && over.isEmpty()) {
                throw invalid("generated call has Into but no Over: " + node.id());
            }
            if (!into.isEmpty() && over.size() != 1) {
                throw invalid("generated call must have exactly one Over continuation: " + node.id());
            }
            if (node.kind() == NodeKind.CALL && into.isEmpty() && over.isEmpty()) {
                throw invalid("generated call has no debugger continuation: " + node.id());
            }
            for (CallFlowEdge intoEdge : into) {
                validateEnteredFrame(intoEdge, nodes, frames);
                validateAllPathsReturn(intoEdge, over.getFirst(), nodes, outgoing);
            }
        }
    }

    private static void validateEnteredFrame(
            CallFlowEdge edge,
            Map<String, CallFlowNode> nodes,
            Map<String, CallFlowFrame> frames
    ) {
        CallFlowNode target = nodes.get(edge.to());
        List<String> stack = target.execution().stack();
        CallFlowFrame entered = frames.get(stack.getLast());
        if (entered == null) {
            throw invalid("generated Into target has no entered frame: " + edge.to());
        }
        if (edge.transition().kind() == TransitionKind.CALL
                && entered.kind() != FrameKind.FUNCTION) {
            throw invalid("generated call must enter a function frame: " + edge.from());
        }
        if (edge.transition().kind() == TransitionKind.CALLBACK_ENTER
                && entered.kind() != FrameKind.LAMBDA
                && entered.kind() != FrameKind.CALLBACK) {
            throw invalid("generated callback must enter a lambda/callback frame: " + edge.from());
        }
    }

    private static void validateReturnFrame(
            CallFlowEdge edge,
            Map<String, CallFlowNode> nodes,
            Map<String, CallFlowFrame> frames
    ) {
        TransitionKind transition = edge.transition().kind();
        if (transition != TransitionKind.RETURN
                && transition != TransitionKind.CALLBACK_RETURN) {
            return;
        }
        if (edge.kind() != EdgeKind.STEP_OUT) {
            throw invalid("generated return transition must use Step Out: " + edge.from());
        }
        List<String> sourceStack = nodes.get(edge.from()).execution().stack();
        List<String> targetStack = nodes.get(edge.to()).execution().stack();
        if (targetStack.size() != sourceStack.size() - 1
                || !sourceStack.subList(0, targetStack.size()).equals(targetStack)) {
            throw invalid("generated return must pop exactly one frame: " + edge.from());
        }
        CallFlowFrame popped = frames.get(sourceStack.getLast());
        if (popped == null) {
            throw invalid("generated return pops an unknown frame: " + edge.from());
        }
        if (transition == TransitionKind.RETURN && popped.kind() != FrameKind.FUNCTION) {
            throw invalid("generated function return must pop a function frame: " + edge.from());
        }
        if (transition == TransitionKind.CALLBACK_RETURN
                && popped.kind() != FrameKind.LAMBDA
                && popped.kind() != FrameKind.CALLBACK) {
            throw invalid("generated callback return must pop a lambda/callback frame: "
                    + edge.from());
        }
    }

    private static void validateAllPathsReturn(
            CallFlowEdge into,
            CallFlowEdge over,
            Map<String, CallFlowNode> nodes,
            Map<String, List<CallFlowEdge>> outgoing
    ) {
        CallFlowNode source = nodes.get(into.from());
        CallFlowNode entered = nodes.get(into.to());
        List<String> callerStack = source.execution().stack();
        List<String> enteredStack = entered.execution().stack();
        TransitionKind expectedReturn = into.transition().kind() == TransitionKind.CALL
                ? TransitionKind.RETURN
                : TransitionKind.CALLBACK_RETURN;
        Set<String> internal = new HashSet<>();
        Map<String, List<String>> internalEdges = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        internal.add(entered.id());
        pending.add(entered.id());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            List<CallFlowEdge> candidates = outgoing.getOrDefault(current, List.of());
            if (candidates.isEmpty()) {
                throw invalid("generated Into path terminates before its Out continuation: "
                        + into.from());
            }
            for (CallFlowEdge edge : candidates) {
                CallFlowNode target = nodes.get(edge.to());
                List<String> targetStack = target.execution().stack();
                if (targetStack.equals(callerStack)) {
                    if (edge.kind() != EdgeKind.STEP_OUT
                            || edge.transition().kind() != expectedReturn
                            || !edge.to().equals(over.to())) {
                        throw invalid("generated Into path has a mismatched Out continuation: "
                                + into.from());
                    }
                    continue;
                }
                if (targetStack.size() < enteredStack.size()
                        || !targetStack.subList(0, enteredStack.size()).equals(enteredStack)) {
                    throw invalid("generated Into path escapes its entered frame: " + into.from());
                }
                internalEdges.computeIfAbsent(current, ignored -> new ArrayList<>())
                        .add(edge.to());
                if (internal.add(edge.to())) {
                    pending.addLast(edge.to());
                }
            }
        }
        rejectNonReturningCycle(internal, internalEdges, into.from());
    }

    private static void rejectNonReturningCycle(
            Set<String> internal,
            Map<String, List<String>> internalEdges,
            String callNode
    ) {
        Map<String, Integer> indegree = new HashMap<>();
        for (String node : internal) {
            indegree.put(node, 0);
        }
        for (List<String> targets : internalEdges.values()) {
            for (String target : targets) {
                indegree.computeIfPresent(target, (ignored, value) -> value + 1);
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.addLast(node);
            }
        });
        int processed = 0;
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            processed++;
            for (String target : internalEdges.getOrDefault(current, List.of())) {
                int remaining = indegree.computeIfPresent(
                        target,
                        (ignored, value) -> value - 1
                );
                if (remaining == 0) {
                    ready.addLast(target);
                }
            }
        }
        if (processed != internal.size()) {
            throw invalid("generated Into path can cycle without returning: " + callNode);
        }
    }

    private static Set<String> reachable(
            String entry,
            Map<String, List<CallFlowEdge>> outgoing
    ) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(entry);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (CallFlowEdge edge : outgoing.getOrDefault(current, List.of())) {
                queue.addLast(edge.to());
            }
        }
        return visited;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid generated Call Flow: " + message);
    }
}
