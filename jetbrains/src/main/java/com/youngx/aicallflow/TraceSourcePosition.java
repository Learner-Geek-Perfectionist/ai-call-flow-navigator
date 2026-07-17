package com.youngx.aicallflow;

import java.util.Objects;

/** Project-relative, one-based source position sampled from an XDebugger session. */
public record TraceSourcePosition(String path, int line, String symbol) {
    public TraceSourcePosition {
        path = normalizePath(Objects.requireNonNull(path, "path"));
        if (path.isBlank()) {
            throw new IllegalArgumentException("Trace source path must not be blank");
        }
        if (line < 1) {
            throw new IllegalArgumentException("Trace source line must be one-based");
        }
        if (symbol != null && symbol.isBlank()) {
            symbol = null;
        }
    }

    static String normalizePath(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }
}
