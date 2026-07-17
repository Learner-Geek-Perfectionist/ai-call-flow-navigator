package com.youngx.aicallflow;

/** Lifecycle of one immutable-static-flow plus live-debugger trace run. */
public enum TraceRunState {
    IDLE,
    WAITING_FOR_SESSION,
    RUNNING,
    PAUSED,
    COMPLETED
}
