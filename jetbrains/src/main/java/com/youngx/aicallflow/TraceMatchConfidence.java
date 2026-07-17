package com.youngx.aicallflow;

/** Explains how confidently a debugger source sample maps to a static Call Flow node. */
public enum TraceMatchConfidence {
    EXACT,
    LINE,
    ADJACENT,
    RANGE,
    AMBIGUOUS,
    UNMATCHED
}
