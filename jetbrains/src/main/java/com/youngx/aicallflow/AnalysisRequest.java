package com.youngx.aicallflow;

/** A validated request from the explicit AI Skill entry point. */
record AnalysisRequest(
        Delivery delivery,
        String version,
        String type,
        String topic,
        Entry entry,
        Strategy strategy
) {
    static final String REQUEST_VERSION = "1.0";
    static final String REQUEST_TYPE = "analysis-request";

    record Delivery(
            String version,
            String requestId,
            long createdAtEpochMs,
            long expiresAtEpochMs
    ) {
    }

    record Entry(
            String path,
            int line,
            int column,
            String symbol
    ) {
    }

    record Strategy(
            String mode,
            String scope
    ) {
        static final String MODE = "static-and-live";
        static final String SCOPE = "project-code";
    }
}
