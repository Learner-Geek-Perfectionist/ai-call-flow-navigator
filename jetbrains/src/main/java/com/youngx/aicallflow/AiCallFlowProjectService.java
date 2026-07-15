package com.youngx.aicallflow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AiCallFlowProjectService implements Disposable {
    private static final Logger LOG = Logger.getInstance(AiCallFlowProjectService.class);

    private final Project project;
    private CallFlowFileStore fileStore;
    private CallFlowFileInboxService.Registration registration;
    private volatile Path projectRoot;
    private volatile String connectionStatus = "Preparing local JSON inbox";
    private volatile long connectionGeneration;

    public AiCallFlowProjectService(@NotNull Project project) {
        this.project = project;
    }

    public synchronized void start() {
        stop();

        Path root;
        try {
            root = resolveProjectRoot();
        } catch (IllegalArgumentException error) {
            failConnection(error.getMessage(), error);
            return;
        }
        projectRoot = root;
        long generation = connectionGeneration;

        try {
            CallFlowFileStore activeStore = CallFlowFileStore.create(root);
            fileStore = activeStore;
            registration = CallFlowFileInboxService.getInstance().register(
                    (flow, sourceJson) -> receive(generation, activeStore, flow, sourceJson)
            );
        } catch (IOException | RuntimeException error) {
            stop();
            String detail = error.getMessage();
            failConnection(
                    detail == null || detail.isBlank()
                            ? "Cannot start the local JSON inbox"
                            : detail,
                    error
            );
            return;
        }

        connectionStatus = "File inbox ready · waiting for AI";
        setStatus("AI Call Flow Navigator: waiting for a local Call Flow JSON file");
    }

    public synchronized void restart() {
        start();
    }

    public synchronized boolean isRunning() {
        return registration != null && fileStore != null;
    }

    public String connectionStatus() {
        return connectionStatus;
    }

    Path projectRoot() {
        Path root = projectRoot;
        return root == null ? resolveProjectRoot() : root;
    }

    @Override
    public synchronized void dispose() {
        stop();
    }

    private CompletionStage<Path> receive(
            long generation,
            CallFlowFileStore expectedStore,
            CallFlow flow,
            String sourceJson
    ) {
        CompletableFuture<Path> completion = new CompletableFuture<>();
        if (!isCurrentRegistration(generation, expectedStore)) {
            completion.completeExceptionally(
                    new IllegalStateException("Call Flow project registration changed")
            );
            return completion;
        }

        Path storedPath;
        try {
            storedPath = expectedStore.persist(sourceJson);
            if (!isCurrentRegistration(generation, expectedStore)) {
                Files.deleteIfExists(storedPath);
                throw new IOException("Call Flow project registration changed while saving JSON");
            }
        } catch (IOException | RuntimeException error) {
            completion.completeExceptionally(error);
            return completion;
        }

        CallFlowSessionService.getInstance(project)
                .loadAsync(flow, () -> isCurrentRegistration(generation, expectedStore))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        completion.complete(storedPath);
                        return;
                    }
                    try {
                        Files.deleteIfExists(storedPath);
                    } catch (IOException cleanupError) {
                        error.addSuppressed(cleanupError);
                    }
                    completion.completeExceptionally(error);
                });
        return completion;
    }

    private synchronized void stop() {
        connectionGeneration++;
        if (registration != null) {
            registration.close();
            registration = null;
        }
        if (fileStore != null) {
            fileStore.close();
            fileStore = null;
        }
    }

    private boolean isCurrentRegistration(
            long generation,
            CallFlowFileStore expectedStore
    ) {
        return connectionGeneration == generation
                && fileStore == expectedStore
                && !project.isDisposed();
    }

    private Path resolveProjectRoot() {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalArgumentException("Current project has no local root");
        }
        try {
            Path root = Path.of(basePath).toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Current project root is not a directory");
            }
            return root;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot resolve the current project root", error);
        }
    }

    private void failConnection(String message, Throwable error) {
        LOG.warn("Failed to start AI Call Flow Navigator", error);
        connectionStatus = "File inbox failed · " + message;
        setStatus("AI Call Flow Navigator: " + connectionStatus);
    }

    private void setStatus(String message) {
        StatusBar.Info.set(message, project);
    }
}
