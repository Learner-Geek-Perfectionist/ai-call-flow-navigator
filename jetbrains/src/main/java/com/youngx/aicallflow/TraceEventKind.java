package com.youngx.aicallflow;

/** Runtime debugger lifecycle, sampling, and control events kept outside the static graph. */
public enum TraceEventKind {
    SESSION_ATTACHED,
    PAUSED,
    FRAME_CHANGED,
    RESUMED,
    SESSION_STOPPED,
    RECORDING_STOPPED,
    PAUSE_REQUESTED,
    RESUME_REQUESTED,
    STEP_INTO_REQUESTED,
    STEP_OVER_REQUESTED,
    STEP_OUT_REQUESTED
}
