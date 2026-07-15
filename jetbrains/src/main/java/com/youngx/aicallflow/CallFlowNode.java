package com.youngx.aicallflow;

public record CallFlowNode(
        String id,
        NodeKind kind,
        CallFlowLocation location,
        String summary
) {
}
