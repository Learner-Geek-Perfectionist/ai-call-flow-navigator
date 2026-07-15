package com.youngx.aicallflow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Creates the native Call Flow player tool window. */
public final class CallFlowToolWindowFactory implements ToolWindowFactory, DumbAware {
    public static final String TOOL_WINDOW_ID = "Call Flow";

    @Override
    public void createToolWindowContent(
            @NotNull Project project,
            @NotNull ToolWindow toolWindow
    ) {
        CallFlowToolWindowPanel panel = new CallFlowToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
