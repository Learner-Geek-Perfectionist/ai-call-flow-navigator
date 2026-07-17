package com.youngx.aicallflow;

public enum TransitionKind {
    CONTINUE,
    CALL,
    RETURN,
    BRANCH,
    LOOP_BACK,
    LOOP_EXIT,
    ASYNC_FORK,
    ASYNC_RESUME,
    ASYNC_JOIN,
    CALLBACK_ENTER,
    CALLBACK_RETURN
}
