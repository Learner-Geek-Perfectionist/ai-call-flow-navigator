package com.youngx.aicallflow;

/** Thread-safe UI-facing status for the live debugger core. */
public record LiveDebuggerSnapshot(
        TraceRunState state,
        boolean recording,
        String sessionName,
        boolean sessionSuspended,
        boolean canPause,
        boolean canResume,
        boolean canStep,
        TraceRun run,
        TraceEvent currentEvent,
        boolean canPreviousEvent,
        boolean canNextEvent,
        String message
) {
    public static LiveDebuggerSnapshot idle(String message) {
        return new LiveDebuggerSnapshot(
                TraceRunState.IDLE,
                false,
                null,
                false,
                false,
                false,
                false,
                null,
                null,
                false,
                false,
                message
        );
    }
}
