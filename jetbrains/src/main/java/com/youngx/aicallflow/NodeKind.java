package com.youngx.aicallflow;

import com.google.gson.annotations.SerializedName;

public enum NodeKind {
    @SerializedName("entry")
    ENTRY,
    @SerializedName("declaration")
    DECLARATION,
    @SerializedName("call")
    CALL,
    @SerializedName("branch")
    BRANCH,
    @SerializedName("return")
    RETURN,
    @SerializedName("async")
    ASYNC,
    @SerializedName("callback")
    CALLBACK,
    @SerializedName("note")
    NOTE
}
