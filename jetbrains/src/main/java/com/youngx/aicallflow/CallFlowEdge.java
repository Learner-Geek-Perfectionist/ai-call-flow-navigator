package com.youngx.aicallflow;

public record CallFlowEdge(
        String from,
        String to,
        EdgeKind kind,
        String label
) {
}
