package com.youngx.aicallflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record CallFlowExecution(
        String context,
        List<String> stack,
        String phase
) {
    public CallFlowExecution {
        if (stack != null) {
            stack = Collections.unmodifiableList(new ArrayList<>(stack));
        }
    }
}
