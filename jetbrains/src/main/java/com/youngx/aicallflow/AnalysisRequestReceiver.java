package com.youngx.aicallflow;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
interface AnalysisRequestReceiver {
    CompletionStage<CallFlow> receive(AnalysisRequest request);
}
