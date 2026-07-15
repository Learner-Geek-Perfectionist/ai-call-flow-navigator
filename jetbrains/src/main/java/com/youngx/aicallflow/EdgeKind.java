package com.youngx.aicallflow;

public enum EdgeKind {
    NEXT,
    STEP_INTO,
    STEP_OVER,
    STEP_OUT,
    BRANCH_TRUE,
    BRANCH_FALSE,
    RETURN,
    ASYNC,
    CALLBACK
}
