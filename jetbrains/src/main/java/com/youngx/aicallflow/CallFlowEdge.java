package com.youngx.aicallflow;

public record CallFlowEdge(
        String from,
        String to,
        EdgeKind kind,
        String label,
        CallFlowTransition transition
) {
    public CallFlowEdge(String from, String to, EdgeKind kind, String label) {
        this(from, to, kind, label, null);
    }
}
