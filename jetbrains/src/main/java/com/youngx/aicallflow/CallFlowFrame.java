package com.youngx.aicallflow;

public record CallFlowFrame(
        String id,
        FrameKind kind,
        String label,
        String symbol
) {
}
