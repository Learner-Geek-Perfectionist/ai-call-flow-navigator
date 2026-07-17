package com.youngx.aicallflow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** Project-scoped session that joins playback, source navigation, and UI updates. */
public final class CallFlowSessionService implements Disposable {
    private static final Logger LOG = Logger.getInstance(CallFlowSessionService.class);

    public interface Listener {
        default void flowLoaded(@NotNull CallFlow flow) {
        }

        default void currentNodeChanged(
                @NotNull CallFlowNode node,
                CallFlowNavigator.NavigationResult navigationResult,
                String navigationError
        ) {
        }
    }

    private final Project project;
    private final CallFlowPlayback playback = new CallFlowPlayback();
    private final CallFlowNavigator navigator;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private CallFlowNavigator.NavigationResult lastNavigationResult;
    private String lastNavigationError;

    public CallFlowSessionService(@NotNull Project project) {
        this.project = project;
        navigator = new CallFlowNavigator(project);
    }

    public static CallFlowSessionService getInstance(@NotNull Project project) {
        return project.getService(CallFlowSessionService.class);
    }

    /** Loads on EDT only while the originating file inbox registration is current. */
    CompletionStage<Void> loadAsync(
            @NotNull CallFlow flow,
            @NotNull BooleanSupplier shouldLoad
    ) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(shouldLoad, "shouldLoad");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        runOnEdt(() -> {
            try {
                if (!shouldLoad.getAsBoolean()) {
                    throw new IllegalStateException("Call Flow project registration is no longer active");
                }
                loadOnEdt(flow);
                completion.complete(null);
            } catch (RuntimeException error) {
                completion.completeExceptionally(error);
            }
        });
        return completion;
    }

    public CallFlow flow() {
        return playback.flow();
    }

    public CallFlowNode current() {
        return playback.current();
    }

    public CallFlowPlayback.Visit currentVisit() {
        return playback.currentVisit();
    }

    public List<CallFlowPlayback.Visit> currentPath() {
        return playback.currentPath();
    }

    public int pathStep() {
        return playback.pathStep();
    }

    public int visitedNodeCount() {
        return playback.visitedNodeCount();
    }

    public int totalNodeCount() {
        return playback.totalNodeCount();
    }

    public int unexploredEdgeCount() {
        return playback.unexploredEdgeCount();
    }

    public boolean hasVisited(@NotNull String nodeId) {
        return playback.hasVisited(nodeId);
    }

    public CallFlowPlayback.Visit latestVisit(@NotNull String nodeId) {
        return playback.latestVisit(nodeId);
    }

    public CallFlowNavigator.NavigationResult lastNavigationResult() {
        return lastNavigationResult;
    }

    public String lastNavigationError() {
        return lastNavigationError;
    }

    public boolean canPrevious() {
        return playback.canPrevious();
    }

    public boolean canForward() {
        return playback.canForward();
    }

    public List<CallFlowPlayback.Candidate> next() {
        return playback.next();
    }

    public List<CallFlowPlayback.Candidate> stepInto() {
        return playback.stepInto();
    }

    public List<CallFlowPlayback.Candidate> stepOver() {
        return playback.stepOver();
    }

    public List<CallFlowPlayback.Candidate> stepOut() {
        return playback.stepOut();
    }

    public void previous() {
        runOnEdt(() -> navigateMovedNode(playback.previous()));
    }

    public void forward() {
        runOnEdt(() -> navigateMovedNode(playback.forward()));
    }

    public void choose(@NotNull CallFlowPlayback.Candidate candidate) {
        runOnEdt(() -> navigateMovedNode(playback.choose(candidate)));
    }

    public void jumpTo(@NotNull String nodeId) {
        jumpTo(nodeId, true);
    }

    void jumpTo(@NotNull String nodeId, boolean focusEditor) {
        runOnEdt(() -> navigateMovedNode(playback.jumpTo(nodeId), focusEditor));
    }

    /** Reveals a runtime event without changing static playback history or visited state. */
    void revealRuntimeNode(@NotNull String nodeId) {
        runOnEdt(() -> {
            CallFlow flow = playback.flow();
            if (flow == null) {
                return;
            }
            CallFlowNode target = flow.nodes().stream()
                    .filter(node -> Objects.equals(node.id(), nodeId))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return;
            }
            try {
                navigator.navigate(target, false);
            } catch (RuntimeException error) {
                LOG.warn("Cannot reveal live trace node " + nodeId, error);
            }
        });
    }

    public void addListener(@NotNull Listener listener, @NotNull Disposable parentDisposable) {
        listeners.add(listener);
        Disposer.register(parentDisposable, () -> listeners.remove(listener));
    }

    @Override
    public void dispose() {
        listeners.clear();
        navigator.dispose();
    }

    private void loadOnEdt(CallFlow flow) {
        if (project.isDisposed()) {
            throw new IllegalStateException("Android Studio project is disposed");
        }
        navigator.validateLocations(flow);
        playback.load(flow);
        for (Listener listener : listeners) {
            safelyNotify(() -> listener.flowLoaded(flow));
        }
        navigateMovedNode(playback.current());

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(CallFlowToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.show();
        }
    }

    private void navigateMovedNode(CallFlowNode node) {
        navigateMovedNode(node, true);
    }

    private void navigateMovedNode(CallFlowNode node, boolean focusEditor) {
        if (node == null || project.isDisposed()) {
            return;
        }
        try {
            lastNavigationResult = navigator.navigate(node, focusEditor);
            lastNavigationError = null;
        } catch (RuntimeException error) {
            lastNavigationResult = null;
            lastNavigationError = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            LOG.warn("Cannot navigate to call-flow node " + node.id(), error);
        }

        for (Listener listener : listeners) {
            safelyNotify(() -> listener.currentNodeChanged(
                    node,
                    lastNavigationResult,
                    lastNavigationError
            ));
        }
    }

    private static void runOnEdt(Runnable task) {
        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) {
            task.run();
        } else {
            application.invokeLater(task, ModalityState.defaultModalityState());
        }
    }

    private static void safelyNotify(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException error) {
            LOG.warn("Call Flow listener failed", error);
        }
    }
}
