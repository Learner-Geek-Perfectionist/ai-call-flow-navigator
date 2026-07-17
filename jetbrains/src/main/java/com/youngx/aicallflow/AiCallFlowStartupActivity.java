package com.youngx.aicallflow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class AiCallFlowStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        project.getService(LiveDebuggerTraceService.class);
        project.getService(AiCallFlowProjectService.class).start();
    }
}
