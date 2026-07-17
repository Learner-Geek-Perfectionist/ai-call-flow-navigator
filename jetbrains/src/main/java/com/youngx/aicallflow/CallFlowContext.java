package com.youngx.aicallflow;

public record CallFlowContext(
        String id,
        ContextKind kind,
        String label,
        String parent
) {
}
