package com.youngx.aicallflow;

import com.google.gson.annotations.SerializedName;

public enum EdgeKind {
    @SerializedName("next")
    NEXT,
    @SerializedName("step_into")
    STEP_INTO,
    @SerializedName("step_over")
    STEP_OVER,
    @SerializedName("step_out")
    STEP_OUT,
    @SerializedName("branch_true")
    BRANCH_TRUE,
    @SerializedName("branch_false")
    BRANCH_FALSE,
    @SerializedName("return")
    RETURN,
    @SerializedName("async")
    ASYNC,
    @SerializedName("callback")
    CALLBACK
}
