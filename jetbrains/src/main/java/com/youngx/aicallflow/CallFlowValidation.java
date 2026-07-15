package com.youngx.aicallflow;

import java.util.HashSet;
import java.util.List;
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

    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[/\\\\].*");
    private static final Pattern URI_LIKE_PATH = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    private CallFlowValidation() {
    }

    static void validate(CallFlow flow) {
        if (flow == null) {
            throw invalid("call flow is required");
        }
        if (!CallFlow.SUPPORTED_VERSION.equals(flow.version())) {
            throw invalid("version must be \"" + CallFlow.SUPPORTED_VERSION + "\"");
        }
        requireText(flow.title(), "title", MAX_TITLE_LENGTH);
        List<CallFlowNode> nodes = requireNonEmptyList(flow.nodes(), "nodes", MAX_NODES);
        List<CallFlowEdge> edges = requireList(flow.edges(), "edges", MAX_EDGES);
        requireIdentifier(flow.entry(), "entry");

        Set<String> nodeIds = new HashSet<>();
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
            if (node.kind() == null) {
                throw invalid(name + ".kind is required and must be a supported value");
            }
            validateLocation(node.location(), name + ".location");
            requireText(node.summary(), name + ".summary", MAX_SUMMARY_LENGTH);
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
        }
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
