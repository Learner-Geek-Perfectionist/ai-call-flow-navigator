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
        CallFlowProject project,
        List<CallFlowNode> nodes,
        List<CallFlowEdge> edges,
        String entry
) {
    public static final String SUPPORTED_VERSION = "1.0";

    public CallFlow {
        nodes = immutableCopy(nodes);
        edges = immutableCopy(edges);
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        if (source == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
