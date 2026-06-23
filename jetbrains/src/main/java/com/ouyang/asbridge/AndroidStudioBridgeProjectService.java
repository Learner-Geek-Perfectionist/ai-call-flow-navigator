package com.ouyang.asbridge;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class AndroidStudioBridgeProjectService implements Disposable {
    private static final Logger LOG = Logger.getInstance(AndroidStudioBridgeProjectService.class);

    private final Project project;
    private BridgeHttpServer server;
    private int reusedPort = -1;

    public AndroidStudioBridgeProjectService(@NotNull Project project) {
        this.project = project;
    }

    public synchronized void start() {
        stop();

        BridgeRuntimeConfig config;
        try {
            config = AndroidStudioBridgeSettings.getInstance(project).toRuntimeConfig(project.getBasePath());
        } catch (IllegalArgumentException error) {
            setStatus("Android Studio Bridge: " + error.getMessage());
            return;
        }

        if (!config.enabled()) {
            setStatus("Android Studio Bridge: disabled");
            return;
        }

        try {
            server = BridgeHttpServer.start(config, this::openSource);
            reusedPort = -1;
            setStatus("Android Studio Bridge: listening on 127.0.0.1:" + server.port());
        } catch (IOException error) {
            if (BridgeHttpServer.isAddressInUse(error)) {
                server = null;
                reusedPort = config.port();
                setStatus("Android Studio Bridge: reusing 127.0.0.1:" + config.port());
                return;
            }

            server = null;
            reusedPort = -1;
            LOG.warn("Failed to start Android Studio Bridge", error);
            setStatus("Android Studio Bridge: failed to start: " + error.getMessage());
        }
    }

    public synchronized void restart() {
        start();
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized boolean isReusingExistingBridge() {
        return reusedPort > 0;
    }

    public synchronized int activePort() {
        if (server != null) {
            return server.port();
        }
        return reusedPort;
    }

    @Override
    public synchronized void dispose() {
        stop();
    }

    private synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
        }
        reusedPort = -1;
    }

    private void openSource(ResolvedOpenRequest request) {
        Application application = ApplicationManager.getApplication();
        Runnable task = () -> openSourceOnEdt(request);
        if (application.isDispatchThread()) {
            task.run();
        } else {
            application.invokeLater(task, ModalityState.any());
        }
    }

    private void openSourceOnEdt(ResolvedOpenRequest request) {
        if (project.isDisposed()) {
            return;
        }

        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(request.absolutePath());
        if (file == null) {
            setStatus("Android Studio Bridge: file not found: " + request.absolutePath());
            return;
        }

        OpenFileDescriptor descriptor = new OpenFileDescriptor(
                project,
                file,
                request.line() - 1,
                request.column() - 1
        );
        FileEditorManager manager = FileEditorManager.getInstance(project);
        if (manager.openTextEditor(descriptor, true) == null) {
            manager.openEditor(descriptor, true);
        }
    }

    private void setStatus(String message) {
        StatusBar.Info.set(message, project);
    }
}
