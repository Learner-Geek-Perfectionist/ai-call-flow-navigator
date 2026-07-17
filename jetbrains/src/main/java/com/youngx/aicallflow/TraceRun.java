package com.youngx.aicallflow;

import java.util.List;
import java.util.Objects;

/** Immutable snapshot of one live debugger recording over a pinned static Call Flow. */
public record TraceRun(
        String id,
        String flowFingerprint,
        String flowTitle,
        String flowEntry,
        long startedAtEpochMs,
        Long endedAtEpochMs,
        TraceRunState state,
        String sessionName,
        List<TraceEvent> events
) {
    public TraceRun {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public TraceEvent latestEvent() {
        return events.isEmpty() ? null : events.getLast();
    }

    /** Debugger pause samples only; control and frame-selection events are not execution hits. */
    public List<TraceEvent> executionEvents() {
        return events.stream().filter(TraceEvent::executionSample).toList();
    }

    public long hitCount(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return events.stream()
                .filter(TraceEvent::executionSample)
                .filter(event -> nodeId.equals(event.nodeId()))
                .count();
    }
}
