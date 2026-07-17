package com.youngx.aicallflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A versioned, AI-produced source navigation flow.
 */
public record CallFlow(
        String version,
        String title,
        List<CallFlowNode> nodes,
        List<CallFlowEdge> edges,
        String entry,
        List<CallFlowContext> contexts,
        List<CallFlowFrame> frames
) {
    public static final String SUPPORTED_VERSION = "1.0";
    public static final String CONTEXT_VERSION = "1.1";

    public CallFlow {
        nodes = immutableCopy(nodes);
        edges = immutableCopy(edges);
        contexts = immutableCopy(contexts);
        frames = immutableCopy(frames);
    }

    public CallFlow(
            String version,
            String title,
            List<CallFlowNode> nodes,
            List<CallFlowEdge> edges,
            String entry
    ) {
        this(version, title, nodes, edges, entry, null, null);
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        if (source == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
