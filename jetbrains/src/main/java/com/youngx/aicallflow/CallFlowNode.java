package com.youngx.aicallflow;

public record CallFlowNode(
        String id,
        NodeKind kind,
        CallFlowLocation location,
        String summary,
        CallFlowExecution execution
) {
    public CallFlowNode(
            String id,
            NodeKind kind,
            CallFlowLocation location,
            String summary
    ) {
        this(id, kind, location, summary, null);
    }
}
