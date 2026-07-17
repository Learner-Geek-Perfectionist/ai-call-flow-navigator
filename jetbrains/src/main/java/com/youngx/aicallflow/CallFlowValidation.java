package com.youngx.aicallflow;

import java.util.HashSet;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class CallFlowValidation {
    static final int MAX_NODES = 10_000;
    static final int MAX_EDGES = 50_000;

    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_TITLE_LENGTH = 512;
    private static final int MAX_LABEL_LENGTH = 512;
    private static final int MAX_SYMBOL_LENGTH = 512;
    private static final int MAX_PATH_LENGTH = 4_096;
    private static final int MAX_SUMMARY_LENGTH = 16_384;
    private static final int MAX_ANCHOR_TEXT_LENGTH = 16_384;
    private static final int MAX_PHASE_LENGTH = 512;

    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[/\\\\].*");
    private static final Pattern URI_LIKE_PATH = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    private CallFlowValidation() {
    }

    static void validate(CallFlow flow) {
        if (flow == null) {
            throw invalid("call flow is required");
        }
        boolean versionOne = CallFlow.SUPPORTED_VERSION.equals(flow.version());
        boolean versionOneOne = CallFlow.CONTEXT_VERSION.equals(flow.version());
        if (!versionOne && !versionOneOne) {
            throw invalid(
                    "version must be \"" + CallFlow.SUPPORTED_VERSION
                            + "\" or \"" + CallFlow.CONTEXT_VERSION + "\""
            );
        }
        requireText(flow.title(), "title", MAX_TITLE_LENGTH);
        Set<String> contextIds = Set.of();
        Set<String> frameIds = Set.of();
        if (versionOneOne) {
            contextIds = validateContexts(flow.contexts());
            frameIds = validateFrames(flow.frames());
        } else {
            if (flow.contexts() != null) {
                throw invalid("contexts is only supported in version \"1.1\"");
            }
            if (flow.frames() != null) {
                throw invalid("frames is only supported in version \"1.1\"");
            }
        }

        List<CallFlowNode> nodes = requireNonEmptyList(flow.nodes(), "nodes", MAX_NODES);
        List<CallFlowEdge> edges = requireList(flow.edges(), "edges", MAX_EDGES);
        requireIdentifier(flow.entry(), "entry");

        Set<String> nodeIds = new HashSet<>();
        Map<String, CallFlowNode> nodesById = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            CallFlowNode node = nodes.get(index);
            String name = "nodes[" + index + "]";
            if (node == null) {
                throw invalid(name + " is required");
            }
            requireIdentifier(node.id(), name + ".id");
            if (!nodeIds.add(node.id())) {
                throw invalid("duplicate node id: " + node.id());
            }
            nodesById.put(node.id(), node);
            if (node.kind() == null) {
                throw invalid(name + ".kind is required and must be a supported value");
            }
            validateLocation(node.location(), name + ".location");
            requireText(node.summary(), name + ".summary", MAX_SUMMARY_LENGTH);
            if (versionOneOne) {
                validateExecution(node.execution(), name + ".execution", contextIds, frameIds);
            } else if (node.execution() != null) {
                throw invalid(name + ".execution is only supported in version \"1.1\"");
            }
        }

        if (!nodeIds.contains(flow.entry())) {
            throw invalid("entry references an unknown node: " + flow.entry());
        }

        for (int index = 0; index < edges.size(); index++) {
            CallFlowEdge edge = edges.get(index);
            String name = "edges[" + index + "]";
            if (edge == null) {
                throw invalid(name + " is required");
            }
            requireIdentifier(edge.from(), name + ".from");
            requireIdentifier(edge.to(), name + ".to");
            if (!nodeIds.contains(edge.from())) {
                throw invalid(name + ".from references an unknown node: " + edge.from());
            }
            if (!nodeIds.contains(edge.to())) {
                throw invalid(name + ".to references an unknown node: " + edge.to());
            }
            if (edge.kind() == null) {
                throw invalid(name + ".kind is required and must be a supported value");
            }
            requireOptionalText(edge.label(), name + ".label", MAX_LABEL_LENGTH);
            if (versionOneOne) {
                validateTransition(
                        edge,
                        name,
                        nodesById.get(edge.from()).execution(),
                        nodesById.get(edge.to()).execution()
                );
            } else if (edge.transition() != null) {
                throw invalid(name + ".transition is only supported in version \"1.1\"");
            }
        }

    }

    private static Set<String> validateContexts(List<CallFlowContext> contexts) {
        List<CallFlowContext> values = requireNonEmptyList(contexts, "contexts");
        Set<String> identifiers = new HashSet<>();
        Map<String, String> parents = new HashMap<>();
        for (int index = 0; index < values.size(); index++) {
            CallFlowContext context = values.get(index);
            String name = "contexts[" + index + "]";
            if (context == null) {
                throw invalid(name + " is required");
            }
            requireIdentifier(context.id(), name + ".id");
            if (!identifiers.add(context.id())) {
                throw invalid("duplicate context id: " + context.id());
            }
            if (context.kind() == null) {
                throw invalid(name + ".kind is required and must be a supported value");
            }
            requireText(context.label(), name + ".label", MAX_LABEL_LENGTH);
            if (context.parent() != null) {
                requireIdentifier(context.parent(), name + ".parent");
            }
            parents.put(context.id(), context.parent());
        }

        for (Map.Entry<String, String> entry : parents.entrySet()) {
            String parent = entry.getValue();
            if (parent != null && !identifiers.contains(parent)) {
                throw invalid(
                        "context " + entry.getKey() + " parent references an unknown context: " + parent
                );
            }
        }
        rejectContextCycles(parents);
        return identifiers;
    }

    private static void rejectContextCycles(Map<String, String> parents) {
        Set<String> resolved = new HashSet<>();
        for (String start : parents.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && !resolved.contains(current)) {
                if (!path.add(current)) {
                    throw invalid("context parent cycle includes: " + current);
                }
                current = parents.get(current);
            }
            resolved.addAll(path);
        }
    }

    private static Set<String> validateFrames(List<CallFlowFrame> frames) {
        List<CallFlowFrame> values = requireNonEmptyList(frames, "frames");
        Set<String> identifiers = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            CallFlowFrame frame = values.get(index);
            String name = "frames[" + index + "]";
            if (frame == null) {
                throw invalid(name + " is required");
            }
            requireIdentifier(frame.id(), name + ".id");
            if (!identifiers.add(frame.id())) {
                throw invalid("duplicate frame id: " + frame.id());
            }
            if (frame.kind() == null) {
                throw invalid(name + ".kind is required and must be a supported value");
            }
            requireText(frame.label(), name + ".label", MAX_LABEL_LENGTH);
            requireOptionalText(frame.symbol(), name + ".symbol", MAX_SYMBOL_LENGTH);
        }
        return identifiers;
    }

    private static void validateExecution(
            CallFlowExecution execution,
            String name,
            Set<String> contextIds,
            Set<String> frameIds
    ) {
        if (execution == null) {
            throw invalid(name + " is required");
        }
        requireIdentifier(execution.context(), name + ".context");
        if (!contextIds.contains(execution.context())) {
            throw invalid(name + ".context references an unknown context: " + execution.context());
        }
        List<String> stack = requireNonEmptyList(execution.stack(), name + ".stack");
        for (int index = 0; index < stack.size(); index++) {
            String frame = stack.get(index);
            String frameName = name + ".stack[" + index + "]";
            requireIdentifier(frame, frameName);
            if (!frameIds.contains(frame)) {
                throw invalid(frameName + " references an unknown frame: " + frame);
            }
        }
        requireOptionalText(execution.phase(), name + ".phase", MAX_PHASE_LENGTH);
    }

    private static void validateTransition(
            CallFlowEdge edge,
            String name,
            CallFlowExecution source,
            CallFlowExecution target
    ) {
        CallFlowTransition transition = edge.transition();
        if (transition == null) {
            throw invalid(name + ".transition is required");
        }
        TransitionKind kind = transition.kind();
        if (kind == null) {
            throw invalid(name + ".transition.kind is required and must be a supported value");
        }
        if (!compatibleTransitions(edge.kind()).contains(kind)) {
            throw invalid(
                    name + ".transition.kind " + protocolName(kind)
                            + " is not compatible with edge kind " + protocolName(edge.kind())
            );
        }

        boolean sameContext = source.context().equals(target.context());
        List<String> sourceStack = source.stack();
        List<String> targetStack = target.stack();
        switch (kind) {
            case CONTINUE, BRANCH, LOOP_BACK, LOOP_EXIT -> {
                if (!sameContext || !sourceStack.equals(targetStack)) {
                    throw invalid(name + ".transition " + protocolName(kind)
                            + " requires the same context and stack");
                }
            }
            case CALL, CALLBACK_ENTER -> {
                if (!sameContext
                        || targetStack.size() != sourceStack.size() + 1
                        || !targetStack.subList(0, sourceStack.size()).equals(sourceStack)) {
                    throw invalid(name + ".transition " + protocolName(kind)
                            + " requires the target stack to append exactly one frame");
                }
            }
            case RETURN, CALLBACK_RETURN -> {
                if (!sameContext
                        || targetStack.size() >= sourceStack.size()
                        || !sourceStack.subList(0, targetStack.size()).equals(targetStack)) {
                    throw invalid(name + ".transition " + protocolName(kind)
                            + " requires the target stack to be a strict prefix of the source stack");
                }
            }
            case ASYNC_FORK, ASYNC_RESUME, ASYNC_JOIN -> {
                if (sameContext) {
                    throw invalid(name + ".transition " + protocolName(kind)
                            + " requires different source and target contexts");
                }
            }
        }
    }

    private static Set<TransitionKind> compatibleTransitions(EdgeKind edgeKind) {
        return switch (edgeKind) {
            case NEXT -> EnumSet.of(
                    TransitionKind.CONTINUE,
                    TransitionKind.LOOP_BACK,
                    TransitionKind.LOOP_EXIT
            );
            case STEP_INTO -> EnumSet.of(
                    TransitionKind.CALL,
                    TransitionKind.CALLBACK_ENTER
            );
            case STEP_OVER -> EnumSet.of(TransitionKind.CONTINUE);
            case STEP_OUT, RETURN -> EnumSet.of(
                    TransitionKind.RETURN,
                    TransitionKind.CALLBACK_RETURN
            );
            case BRANCH_TRUE, BRANCH_FALSE -> EnumSet.of(
                    TransitionKind.BRANCH,
                    TransitionKind.LOOP_EXIT
            );
            case ASYNC -> EnumSet.of(
                    TransitionKind.ASYNC_FORK,
                    TransitionKind.ASYNC_RESUME,
                    TransitionKind.ASYNC_JOIN
            );
            case CALLBACK -> EnumSet.of(
                    TransitionKind.CALLBACK_ENTER,
                    TransitionKind.CALLBACK_RETURN
            );
        };
    }

    private static String protocolName(Enum<?> value) {
        return value.name().toLowerCase();
    }

    private static void validateLocation(CallFlowLocation location, String name) {
        if (location == null) {
            throw invalid(name + " is required");
        }
        validateRelativePath(location.path(), name + ".path");
        requirePositive(location.line(), name + ".line");
        requirePositive(location.column(), name + ".column");

        Integer endLine = location.endLine();
        Integer endColumn = location.endColumn();
        if ((endLine == null) != (endColumn == null)) {
            throw invalid(name + ".endLine and " + name + ".endColumn must be provided together");
        }
        if (endLine != null) {
            requirePositive(endLine, name + ".endLine");
            requirePositive(endColumn, name + ".endColumn");
            if (endLine < location.line()
                    || (endLine == location.line() && endColumn < location.column())) {
                throw invalid(name + " end position must not precede its start position");
            }
        }

        requireOptionalText(location.symbol(), name + ".symbol", MAX_SYMBOL_LENGTH);
        String anchorText = location.anchorText();
        requireOptionalText(anchorText, name + ".anchorText", MAX_ANCHOR_TEXT_LENGTH);
        if (anchorText != null && (anchorText.indexOf('\n') >= 0 || anchorText.indexOf('\r') >= 0)) {
            throw invalid(name + ".anchorText must stay on one source line");
        }
    }

    private static void validateRelativePath(String path, String name) {
        requireText(path, name, MAX_PATH_LENGTH);
        if (!path.equals(path.trim())) {
            throw invalid(name + " must not have leading or trailing whitespace");
        }
        if (path.startsWith("/")
                || path.startsWith("\\")
                || WINDOWS_ABSOLUTE_PATH.matcher(path).matches()
                || URI_LIKE_PATH.matcher(path).matches()) {
            throw invalid(name + " must be a project-relative path");
        }
        if (path.indexOf('\\') >= 0) {
            throw invalid(name + " must use forward slashes");
        }
        for (int index = 0; index < path.length(); index++) {
            if (Character.isISOControl(path.charAt(index))) {
                throw invalid(name + " must not contain control characters");
            }
        }

        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid(name + " must be a normalized project-relative path");
            }
        }
    }

    private static void requireIdentifier(String value, String name) {
        requireText(value, name, MAX_ID_LENGTH);
        if (!value.equals(value.trim())) {
            throw invalid(name + " must not have leading or trailing whitespace");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw invalid(name + " must not contain control characters");
            }
        }
    }

    private static void requireOptionalText(String value, String name, int maxLength) {
        if (value != null) {
            requireText(value, name, maxLength);
        }
    }

    private static void requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
        if (value.length() > maxLength) {
            throw invalid(name + " must be at most " + maxLength + " characters");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw invalid(name + " must be a positive 1-based coordinate");
        }
    }

    private static <T> List<T> requireNonEmptyList(List<T> value, String name, int maxSize) {
        if (value == null || value.isEmpty()) {
            throw invalid(name + " must not be empty");
        }
        return requireList(value, name, maxSize);
    }

    private static <T> List<T> requireNonEmptyList(List<T> value, String name) {
        if (value == null || value.isEmpty()) {
            throw invalid(name + " must not be empty");
        }
        return value;
    }

    private static <T> List<T> requireList(List<T> value, String name, int maxSize) {
        if (value == null) {
            throw invalid(name + " is required");
        }
        if (value.size() > maxSize) {
            throw invalid(name + " must contain at most " + maxSize + " items");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid call flow: " + message);
    }
}
