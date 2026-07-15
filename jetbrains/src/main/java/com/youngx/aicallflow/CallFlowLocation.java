package com.youngx.aicallflow;

public record CallFlowLocation(
        String path,
        int line,
        int column,
        Integer endLine,
        Integer endColumn,
        String symbol,
        String anchorText
) {
}
