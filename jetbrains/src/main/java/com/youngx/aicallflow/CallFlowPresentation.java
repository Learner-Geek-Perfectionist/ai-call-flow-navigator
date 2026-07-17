package com.youngx.aicallflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure presentation helpers for playback position, transitions, and node rows. */
final class CallFlowPresentation {
    private static final int MAX_VISIBLE_INDENT_DEPTH = 10;
    private static final int INDENT_WIDTH_PER_LEVEL = 24;
    private static final Pattern KOTLIN_FUNCTION = Pattern.compile(
            "\\bfun\\s+(`[^`]+`|[\\p{L}_$][\\p{L}\\p{N}_$]*)"
    );
    private static final Pattern SOURCE_CALLABLE = Pattern.compile(
            "(`[^`]+`|[\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*(?=\\(|\\{)"
    );
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "when", "for", "while", "catch", "switch", "synchronized"
    );

    record Header(String position, String breadcrumb, String transition) {
    }

    enum BadgeTone {
        ENTRY,
        FLOW,
        CALL,
        RETURN,
        BRANCH,
        ASYNC,
        CALLBACK,
        OVER,
        DIRECT,
        UNKNOWN,
        PLANNED
    }

    record NodeRow(
            String marker,
            int indentWidth,
            boolean indentTruncated,
            String level,
            String badge,
            BadgeTone badgeTone,
            boolean pending,
            String title,
            boolean exact
    ) {
    }

    private CallFlowPresentation() {
    }

    static Header header(
            CallFlowPlayback.Visit visit,
            CallFlowPlayback.Visit previous,
            int pathStep,
            int visitedNodes,
            int totalNodes,
            int unexploredEdges,
            int nextChoices
    ) {
        if (visit == null) {
            return new Header(
                    "Waiting for a Call Flow",
                    "No execution context",
                    "No transition"
            );
        }

        CallFlowPlayback.ExecutionState execution = visit.execution();
        String precision = execution.exact() ? "" : " (inferred)";
        String ending = nextChoices == 0
                ? " · End of current path"
                : nextChoices > 1 ? " · " + nextChoices + " next choices" : "";
        String position = "Step " + pathStep
                + " · Depth L" + execution.depth() + precision
                + " · Visited " + visitedNodes + "/" + totalNodes
                + " · " + unexploredEdges + " unexplored"
                + ending;
        return new Header(
                position,
                breadcrumb(execution),
                transitionDescription(visit, previous)
        );
    }

    static NodeRow nodeRow(
            CallFlowNode node,
            CallFlowPlayback.Visit latestVisit,
            String frameLabel,
            boolean visited,
            boolean current
    ) {
        int depth;
        boolean exact;
        String badge;
        BadgeTone badgeTone;
        boolean pending;
        if (latestVisit != null) {
            depth = latestVisit.execution().depth();
            exact = latestVisit.execution().exact();
            badge = shortTransition(latestVisit.transitionType());
            badgeTone = badgeTone(latestVisit.transitionType());
            pending = false;
        } else if (node.execution() != null && node.execution().stack() != null) {
            depth = Math.max(0, node.execution().stack().size() - 1);
            exact = true;
            badge = plannedNodeBadge(node.kind());
            badgeTone = BadgeTone.PLANNED;
            pending = true;
        } else {
            depth = 0;
            exact = false;
            badge = plannedNodeBadge(node.kind());
            badgeTone = BadgeTone.PLANNED;
            pending = true;
        }

        int visibleDepth = Math.min(Math.max(0, depth), MAX_VISIBLE_INDENT_DEPTH);
        String visibleFrameLabel = latestVisit != null && !latestVisit.execution().exact()
                ? null
                : frameLabel;
        return new NodeRow(
                current ? "▶" : visited ? "✓" : "○",
                visibleDepth * INDENT_WIDTH_PER_LEVEL,
                depth > MAX_VISIBLE_INDENT_DEPTH,
                "L" + depth,
                badge,
                badgeTone,
                pending,
                nodeTitle(node, visibleFrameLabel),
                exact
        );
    }

    static String nodeDetails(CallFlowNode node) {
        return node.summary();
    }

    static String nodeTitle(CallFlowNode node, String frameLabel) {
        CallFlowLocation location = node.location();
        if (location != null) {
            if (node.kind() == NodeKind.ENTRY) {
                String entrySymbol = ownerQualifiedSourceSymbol(location.symbol());
                if (entrySymbol != null) {
                    return entrySymbol;
                }
            }
            if (node.kind() == NodeKind.CALL
                    && location.anchorText() != null
                    && location.anchorText().strip().startsWith("super.")) {
                String superCallable = callableFromAnchor(location.anchorText());
                if (superCallable != null) {
                    return "super." + superCallable;
                }
            }
            String symbol = shortSourceSymbol(location.symbol());
            if (symbol != null) {
                return symbol;
            }
            if (node.kind() == NodeKind.ENTRY
                    || node.kind() == NodeKind.DECLARATION
                    || node.kind() == NodeKind.CALL) {
                String anchor = callableFromAnchor(location.anchorText());
                if (anchor != null) {
                    return anchor;
                }
            }
        }

        if (frameLabel != null && !frameLabel.isBlank()
                && node.kind() != NodeKind.CALL
                && node.kind() != NodeKind.BRANCH) {
            return frameLabel.strip();
        }
        return sourceLocationTitle(location);
    }

    static String shortSourceSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String value = beforeSignature(symbol.strip());
        List<String> segments = qualifiedSegments(value);
        if (segments.isEmpty()) {
            return null;
        }
        String title = segments.getLast().strip();
        if (title.equals("<init>") || title.equals("constructor")) {
            if (segments.size() < 2) {
                return null;
            }
            return segments.get(segments.size() - 2).strip() + "()";
        }
        if (title.endsWith("$default")) {
            title = title.substring(0, title.length() - "$default".length());
        }
        int lambda = title.indexOf("$lambda$");
        if (lambda > 0) {
            title = title.substring(0, lambda);
        }
        return title.isBlank() ? null : title;
    }

    static String breadcrumb(CallFlowPlayback.ExecutionState execution) {
        StringBuilder text = new StringBuilder();
        if (execution.contextLabel() != null && !execution.contextLabel().isBlank()) {
            text.append(execution.contextLabel()).append(" · ");
        }
        List<String> stack = execution.stack();
        text.append(stack.isEmpty() ? "No active frame" : String.join("  ›  ", stack));
        if (execution.phase() != null && !execution.phase().isBlank()) {
            text.append(" · Phase: ").append(execution.phase());
        }
        return text.toString();
    }

    static String transitionDescription(
            CallFlowPlayback.Visit visit,
            CallFlowPlayback.Visit previous
    ) {
        String badge = transitionBadge(visit.transitionType());
        String currentTitle = nodeTitle(visit.node(), exactInnermostFrame(visit));
        if (visit.transitionType() == CallFlowPlayback.TransitionType.ENTRY) {
            return badge + " · " + currentTitle;
        }
        if (visit.transitionType() == CallFlowPlayback.TransitionType.DIRECT_JUMP) {
            String route = previous == null
                    ? currentTitle
                    : nodeTitle(previous.node(), exactInnermostFrame(previous))
                            + "  →  " + currentTitle;
            return badge + " · " + route + " · Source location only";
        }

        CallFlowEdge edge = visit.viaEdge();
        if (edge == null || previous == null) {
            return badge + " · " + currentTitle;
        }
        StringBuilder text = new StringBuilder(badge)
                .append(" · ")
                .append(nodeTitle(previous.node(), exactInnermostFrame(previous)))
                .append("  →  ")
                .append(currentTitle);
        if (edge.label() != null && !edge.label().isBlank()) {
            text.append(" · ").append(edge.label());
        }
        return text.toString();
    }

    static String transitionBadge(CallFlowPlayback.TransitionType type) {
        return switch (type) {
            case ENTRY -> "● ENTRY";
            case CONTINUE -> "→ NEXT";
            case CALL -> "↳ CALL / INTO";
            case RETURN -> "↰ RETURN / OUT";
            case BRANCH -> "◇ BRANCH";
            case LOOP_BACK -> "↺ LOOP BACK";
            case LOOP_EXIT -> "↗ LOOP EXIT";
            case ASYNC -> "⚡ ASYNC";
            case ASYNC_FORK -> "⚡ ASYNC FORK";
            case ASYNC_RESUME -> "⚡ ASYNC RESUME";
            case ASYNC_JOIN -> "⚡ ASYNC JOIN";
            case CALLBACK -> "↪ CALLBACK";
            case CALLBACK_ENTER -> "↪ CALLBACK ENTER";
            case CALLBACK_RETURN -> "↩ CALLBACK RETURN";
            case STEP_OVER -> "↷ STEP OVER";
            case DIRECT_JUMP -> "⌖ DIRECT JUMP";
            case UNKNOWN -> "? UNKNOWN TRANSITION";
        };
    }

    static String shortTransition(CallFlowPlayback.TransitionType type) {
        return switch (type) {
            case ENTRY -> "● ENTRY";
            case CONTINUE -> "→ NEXT";
            case CALL -> "↳ CALL";
            case RETURN -> "↰ RETURN";
            case BRANCH -> "◇ BRANCH";
            case LOOP_BACK -> "↺ LOOP";
            case LOOP_EXIT -> "↗ LOOP EXIT";
            case ASYNC -> "⚡ ASYNC";
            case ASYNC_FORK -> "⚡ FORK";
            case ASYNC_RESUME -> "⚡ RESUME";
            case ASYNC_JOIN -> "⚡ JOIN";
            case CALLBACK -> "↪ CALLBACK";
            case CALLBACK_ENTER -> "↪ CB IN";
            case CALLBACK_RETURN -> "↩ CB OUT";
            case STEP_OVER -> "↷ OVER";
            case DIRECT_JUMP -> "⌖ DIRECT";
            case UNKNOWN -> "? UNKNOWN";
        };
    }

    static BadgeTone badgeTone(CallFlowPlayback.TransitionType type) {
        return switch (type) {
            case ENTRY -> BadgeTone.ENTRY;
            case CONTINUE -> BadgeTone.FLOW;
            case CALL -> BadgeTone.CALL;
            case RETURN -> BadgeTone.RETURN;
            case BRANCH, LOOP_BACK, LOOP_EXIT -> BadgeTone.BRANCH;
            case ASYNC, ASYNC_FORK, ASYNC_RESUME, ASYNC_JOIN -> BadgeTone.ASYNC;
            case CALLBACK, CALLBACK_ENTER, CALLBACK_RETURN -> BadgeTone.CALLBACK;
            case STEP_OVER -> BadgeTone.OVER;
            case DIRECT_JUMP -> BadgeTone.DIRECT;
            case UNKNOWN -> BadgeTone.UNKNOWN;
        };
    }

    static String plannedNodeBadge(NodeKind kind) {
        return switch (kind) {
            case ENTRY -> "[entry]";
            case DECLARATION -> "[decl]";
            case CALL -> "[call]";
            case BRANCH -> "[branch]";
            case RETURN -> "[return]";
            case ASYNC -> "[async]";
            case CALLBACK -> "[callback]";
            case NOTE -> "[note]";
        };
    }

    private static String exactInnermostFrame(CallFlowPlayback.Visit visit) {
        if (!visit.execution().exact()) {
            return null;
        }
        List<String> stack = visit.execution().stack();
        return stack.isEmpty() ? null : stack.getLast();
    }

    private static String ownerQualifiedSourceSymbol(String symbol) {
        String shortName = shortSourceSymbol(symbol);
        if (shortName == null || shortName.endsWith("()")) {
            return shortName;
        }
        List<String> segments = qualifiedSegments(beforeSignature(symbol.strip()));
        if (segments.size() < 2) {
            return shortName;
        }
        String owner = segments.get(segments.size() - 2).strip();
        return owner.isBlank() ? shortName : owner + "." + shortName;
    }

    private static String callableFromAnchor(String anchorText) {
        if (anchorText == null || anchorText.isBlank()) {
            return null;
        }
        Matcher function = KOTLIN_FUNCTION.matcher(anchorText);
        if (function.find()) {
            return function.group(1);
        }
        Matcher callable = SOURCE_CALLABLE.matcher(anchorText);
        while (callable.find()) {
            String candidate = callable.group(1);
            if (!CONTROL_KEYWORDS.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String sourceLocationTitle(CallFlowLocation location) {
        if (location == null || location.path() == null || location.path().isBlank()) {
            return "Unknown source";
        }
        String path = location.path().replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String file = slash < 0 ? path : path.substring(slash + 1);
        return file + ":" + location.line();
    }

    private static String beforeSignature(String symbol) {
        boolean backtick = false;
        for (int index = 0; index < symbol.length(); index++) {
            char character = symbol.charAt(index);
            if (character == '`') {
                backtick = !backtick;
            } else if (character == '(' && !backtick) {
                return symbol.substring(0, index).strip();
            }
        }
        return symbol;
    }

    private static List<String> qualifiedSegments(String symbol) {
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        boolean backtick = false;
        for (int index = 0; index < symbol.length(); index++) {
            char character = symbol.charAt(index);
            if (character == '`') {
                backtick = !backtick;
                segment.append(character);
                continue;
            }
            boolean doubleColon = !backtick && character == ':'
                    && index + 1 < symbol.length() && symbol.charAt(index + 1) == ':';
            if (!backtick && (character == '.' || character == '#' || doubleColon)) {
                if (!segment.isEmpty()) {
                    segments.add(segment.toString());
                    segment.setLength(0);
                }
                if (doubleColon) {
                    index++;
                }
            } else {
                segment.append(character);
            }
        }
        if (!segment.isEmpty()) {
            segments.add(segment.toString());
        }
        return segments;
    }
}
