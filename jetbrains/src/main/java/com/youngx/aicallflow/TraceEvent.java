package com.youngx.aicallflow;

import java.util.List;

/** One ordered debugger lifecycle, control, or paused-position sample. */
public record TraceEvent(
        long sequence,
        long timestampEpochMs,
        TraceEventKind kind,
        String sessionName,
        String contextLabel,
        TraceSourcePosition source,
        String previousNodeId,
        String nodeId,
        EdgeKind viaEdgeKind,
        TraceMatchConfidence confidence,
        List<String> candidateNodeIds
) {
    public TraceEvent {
        if (contextLabel != null && contextLabel.isBlank()) {
            contextLabel = null;
        }
        candidateNodeIds = candidateNodeIds == null
                ? List.of()
                : List.copyOf(candidateNodeIds);
    }

    public boolean executionSample() {
        return kind == TraceEventKind.PAUSED;
    }
}
