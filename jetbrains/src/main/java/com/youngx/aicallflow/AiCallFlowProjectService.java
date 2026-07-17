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
    private AnalysisRequestFileInboxService.Registration registration;
    private volatile Path projectRoot;
    private volatile String connectionStatus = "Preparing analysis request inbox";
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
            registration = AnalysisRequestFileInboxService.getInstance().register(
                    request -> receive(generation, request)
            );
        } catch (IOException | RuntimeException error) {
            stop();
            String detail = error.getMessage();
            failConnection(
                    detail == null || detail.isBlank()
                            ? "Cannot start the analysis request inbox"
                            : detail,
                    error
            );
            return;
        }

        connectionStatus = "File inbox ready · waiting for AI Skill";
        setStatus("AI Call Flow Navigator: waiting for an AI analysis request");
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

    private CompletionStage<CallFlow> receive(
            long generation,
            AnalysisRequest request
    ) {
        CompletableFuture<CallFlow> completion = new CompletableFuture<>();
        if (!isCurrentRegistration(generation)) {
            completion.completeExceptionally(
                    new IllegalStateException("Analysis request project registration changed")
            );
            return completion;
        }

        CompletionStage<CallFlow> generationRequest;
        try {
            generationRequest = StaticCallFlowGenerationService.getInstance(project)
                    .generateAndLoad(request, () -> isCurrentRegistration(generation));
        } catch (RuntimeException error) {
            completion.completeExceptionally(error);
            return completion;
        }

        generationRequest.whenComplete((flow, error) -> {
            if (error != null) {
                completion.completeExceptionally(error);
            } else if (!isCurrentRegistration(generation)) {
                completion.completeExceptionally(
                        new IllegalStateException("Analysis request project registration changed")
                );
            } else {
                completion.complete(flow);
            }
        });
        return completion;
    }

    private synchronized void stop() {
        connectionGeneration++;
        if (registration != null) {
            registration.close();
            registration = null;
        }
    }

    private boolean isCurrentRegistration(long generation) {
        return connectionGeneration == generation
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
