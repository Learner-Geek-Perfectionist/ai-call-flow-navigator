package com.ouyang.asbridge;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(
        name = "AndroidStudioBridgeSettings",
        storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public final class AndroidStudioBridgeSettings implements PersistentStateComponent<AndroidStudioBridgeSettings.SettingsState> {
    private SettingsState state = new SettingsState();

    public static AndroidStudioBridgeSettings getInstance(Project project) {
        return project.getService(AndroidStudioBridgeSettings.class);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        this.state = state;
    }

    BridgeRuntimeConfig toRuntimeConfig(String projectBasePath) {
        String configuredRoot = state.projectRoot == null ? "" : state.projectRoot.trim();
        String fallbackRoot = projectBasePath == null ? "" : projectBasePath.trim();
        String root = configuredRoot.isEmpty() ? fallbackRoot : configuredRoot;
        return new BridgeRuntimeConfig(root, state.port, state.enabled);
    }

    public static final class SettingsState {
        public String projectRoot = "";
        public int port = 17321;
        public boolean enabled = true;
    }
}
