package com.youngx.aicallflow;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
interface CallFlowFileReceiver {
    CompletionStage<Path> receive(CallFlow callFlow, String sourceJson);
}
