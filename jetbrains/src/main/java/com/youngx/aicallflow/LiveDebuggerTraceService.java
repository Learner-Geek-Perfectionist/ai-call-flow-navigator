package com.youngx.aicallflow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerManagerListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Project-level adapter between public XDebugger lifecycle APIs and immutable live trace snapshots.
 * All mutable state is confined to the IDE dispatch thread.
 */
@Service(Service.Level.PROJECT)
public final class LiveDebuggerTraceService implements Disposable {
    private static final Logger LOG = Logger.getInstance(LiveDebuggerTraceService.class);

    public interface Listener {
        void snapshotChanged(@NotNull LiveDebuggerSnapshot snapshot);
    }

    private final Project project;
    private final XDebuggerManager debuggerManager;
    private final LiveTraceRecorder recorder = new LiveTraceRecorder();
    private final Set<XDebugSession> observedSessions =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile LiveDebuggerSnapshot snapshot =
            LiveDebuggerSnapshot.idle("Live debugger recording is idle");
    private XDebugSession boundSession;
    private Long selectedExecutionEventSequence;
    private boolean disposed;

    public LiveDebuggerTraceService(@NotNull Project project) {
        this.project = Objects.requireNonNull(project, "project");
        debuggerManager = XDebuggerManager.getInstance(project);
        project.getMessageBus().connect(this).subscribe(
                XDebuggerManager.TOPIC,
                new ManagerListener()
        );
        CallFlowSessionService flowSession = CallFlowSessionService.getInstance(project);
        flowSession.addListener(new CallFlowSessionService.Listener() {
            @Override
            public void flowLoaded(@NotNull CallFlow flow) {
                runLater(() -> startRecording(flow));
            }
        }, this);
        CallFlow existingFlow = flowSession.flow();
        if (existingFlow != null) {
            runLater(() -> startRecording(existingFlow));
        }
        runLater(this::observeExistingSessions);
    }

    public static LiveDebuggerTraceService getInstance(@NotNull Project project) {
        return project.getService(LiveDebuggerTraceService.class);
    }

    /** Starts a new trace pinned to the supplied immutable static Call Flow. */
    public void startRecording(@NotNull CallFlow flow) {
        Objects.requireNonNull(flow, "flow");
        runAndWait(() -> {
            if (disposed) {
                throw new IllegalStateException("Live debugger trace service is disposed");
            }
            if (recorder.active()) {
                recorder.recordingStopped();
            }
            boundSession = null;
            selectedExecutionEventSequence = null;
            recorder.start(flow);
            observeExistingSessions();
            XDebugSession session = preferredSession();
            if (session != null) {
                bind(session);
            } else {
                publish("Waiting for an XDebugger session");
            }
        });
    }

    public void stopRecording() {
        runAndWait(() -> {
            if (recorder.active()) {
                recorder.recordingStopped();
            }
            boundSession = null;
            publish("Live debugger recording stopped");
        });
    }

    public boolean pause() {
        return control(
                TraceEventKind.PAUSE_REQUESTED,
                false,
                XDebugSession::pause,
                "Pause requested"
        );
    }

    public boolean resume() {
        return control(
                TraceEventKind.RESUME_REQUESTED,
                true,
                XDebugSession::resume,
                "Resume requested"
        );
    }

    public boolean stepInto() {
        return control(
                TraceEventKind.STEP_INTO_REQUESTED,
                true,
                XDebugSession::stepInto,
                "Step Into requested"
        );
    }

    public boolean stepOver() {
        return control(
                TraceEventKind.STEP_OVER_REQUESTED,
                true,
                session -> session.stepOver(false),
                "Step Over requested"
        );
    }

    public boolean stepOut() {
        return control(
                TraceEventKind.STEP_OUT_REQUESTED,
                true,
                XDebugSession::stepOut,
                "Step Out requested"
        );
    }

    public LiveDebuggerSnapshot snapshot() {
        return snapshot;
    }

    /** The selected PAUSED sample in the runtime trace, independent from static playback. */
    public TraceEvent currentEvent() {
        return snapshot.currentEvent();
    }

    public long hitCount(@NotNull String nodeId) {
        TraceRun run = snapshot.run();
        return run == null ? 0 : run.hitCount(nodeId);
    }

    public boolean canPreviousEvent() {
        return snapshot.canPreviousEvent();
    }

    public boolean canNextEvent() {
        return snapshot.canNextEvent();
    }

    public boolean canStep() {
        return snapshot.canStep();
    }

    public boolean canResume() {
        return snapshot.canResume();
    }

    /** Moves the trace-view cursor only; it never performs reverse debugger execution. */
    public TraceEvent previousEvent() {
        return moveEventCursor(-1);
    }

    /** Moves the trace-view cursor only; it never controls or resumes the debugger. */
    public TraceEvent nextEvent() {
        return moveEventCursor(1);
    }

    public TraceEvent latestEvent() {
        AtomicReference<TraceEvent> selected = new AtomicReference<>();
        runAndWait(() -> {
            TraceEvent latest = latestExecutionEvent(recorder.snapshot());
            selectedExecutionEventSequence = latest == null ? null : latest.sequence();
            publish("Showing latest debugger pause");
            selected.set(latest);
        });
        return selected.get();
    }

    public void addListener(@NotNull Listener listener, @NotNull Disposable parentDisposable) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        Disposer.register(parentDisposable, () -> listeners.remove(listener));
    }

    @Override
    public void dispose() {
        disposed = true;
        observedSessions.clear();
        boundSession = null;
        selectedExecutionEventSequence = null;
        listeners.clear();
        snapshot = LiveDebuggerSnapshot.idle("Live debugger trace service is disposed");
    }

    private boolean control(
            TraceEventKind kind,
            boolean requiresSuspended,
            SessionCommand command,
            String message
    ) {
        AtomicBoolean accepted = new AtomicBoolean();
        runAndWait(() -> {
            XDebugSession session = boundSession;
            if (!recorder.active()
                    || session == null
                    || session.isStopped()
                    || session.isSuspended() != requiresSuspended) {
                publish("Debugger command is unavailable in the current state");
                return;
            }
            try {
                recorder.command(kind);
                command.run(session);
                accepted.set(true);
                publish(message);
            } catch (RuntimeException error) {
                LOG.warn("XDebugger command failed: " + kind, error);
                publish("Debugger command failed: " + safeMessage(error));
            }
        });
        return accepted.get();
    }

    private void observeExistingSessions() {
        assertDispatchThread();
        if (disposed || project.isDisposed()) {
            return;
        }
        for (XDebugSession session : debuggerManager.getDebugSessions()) {
            observe(session);
        }
    }

    private void observe(XDebugSession session) {
        assertDispatchThread();
        if (session == null || session.isStopped() || !observedSessions.add(session)) {
            return;
        }
        session.addSessionListener(new SessionListener(session), this);
    }

    private XDebugSession preferredSession() {
        XDebugSession current = debuggerManager.getCurrentSession();
        if (usable(current)) {
            return current;
        }
        return Arrays.stream(debuggerManager.getDebugSessions())
                .filter(this::usable)
                .findFirst()
                .orElse(null);
    }

    private boolean usable(XDebugSession session) {
        return session != null && !session.isStopped();
    }

    private void bind(XDebugSession session) {
        assertDispatchThread();
        if (!recorder.active() || boundSession != null || !usable(session)) {
            return;
        }
        observe(session);
        boundSession = session;
        recorder.sessionAttached(session.getSessionName(), session.isSuspended());
        if (session.isSuspended()) {
            capture(session, true);
        }
        publish("Attached to debugger session " + session.getSessionName());
    }

    private void paused(XDebugSession session) {
        if (session != boundSession || !recorder.active()) {
            return;
        }
        capture(session, true);
        publish("Debugger paused");
    }

    private void frameChanged(XDebugSession session) {
        if (session != boundSession || !recorder.active()) {
            return;
        }
        capture(session, false);
        publish("Selected stack frame changed");
    }

    private void resumed(XDebugSession session) {
        if (session != boundSession || !recorder.active()) {
            return;
        }
        recorder.resumed();
        publish("Debugger resumed");
    }

    private void stopped(XDebugSession session) {
        observedSessions.remove(session);
        if (session != boundSession || !recorder.active()) {
            return;
        }
        recorder.sessionStopped();
        boundSession = null;
        publish("Debugger session stopped");
    }

    private void capture(XDebugSession session, boolean pauseSample) {
        try {
            XSourcePosition position = session.getCurrentPosition();
            if (position == null) {
                XStackFrame frame = session.getCurrentStackFrame();
                position = frame == null ? null : frame.getSourcePosition();
            }
            TraceSourcePosition source = position == null
                    ? null
                    : DebuggerSourceResolver.resolve(project, position);
            String contextLabel = currentContextLabel(session);
            if (pauseSample) {
                recorder.paused(source, contextLabel);
                selectLatestExecutionEvent();
            } else {
                recorder.frameChanged(source, contextLabel);
            }
        } catch (RuntimeException error) {
            LOG.warn("Cannot sample the current XDebugger source position", error);
            if (pauseSample) {
                recorder.paused(null);
                selectLatestExecutionEvent();
            } else {
                recorder.frameChanged(null);
            }
        }
    }

    private static String currentContextLabel(XDebugSession session) {
        XSuspendContext suspendContext = session.getSuspendContext();
        XExecutionStack stack = suspendContext == null
                ? null
                : suspendContext.getActiveExecutionStack();
        return stack == null ? null : stack.getDisplayName();
    }

    private void publish(String message) {
        assertDispatchThread();
        TraceRun run = recorder.snapshot();
        XDebugSession session = boundSession;
        boolean active = recorder.active();
        boolean usable = session != null && !session.isStopped();
        boolean suspended = usable && session.isSuspended();
        TraceEvent currentEvent = selectedExecutionEvent(run);
        EventCursorAvailability cursor = eventCursorAvailability(run, currentEvent);
        LiveDebuggerSnapshot next = new LiveDebuggerSnapshot(
                recorder.state(),
                active,
                run == null ? null : run.sessionName(),
                suspended,
                active && usable && !suspended,
                active && usable && suspended,
                active && usable && suspended,
                run,
                currentEvent,
                cursor.canPrevious(),
                cursor.canNext(),
                message
        );
        snapshot = next;
        for (Listener listener : listeners) {
            try {
                listener.snapshotChanged(next);
            } catch (RuntimeException error) {
                LOG.warn("Live debugger listener failed", error);
            }
        }
    }

    private void runAndWait(Runnable action) {
        if (project.isDisposed()) {
            throw new IllegalStateException("Android Studio project is disposed");
        }
        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) {
            action.run();
        } else {
            application.invokeAndWait(action, ModalityState.any());
        }
    }

    private TraceEvent moveEventCursor(int delta) {
        AtomicReference<TraceEvent> selected = new AtomicReference<>();
        runAndWait(() -> {
            TraceRun run = recorder.snapshot();
            if (run == null) {
                selected.set(null);
                return;
            }
            java.util.List<TraceEvent> events = run.executionEvents();
            if (events.isEmpty()) {
                selectedExecutionEventSequence = null;
                publish("No debugger pause has been recorded");
                return;
            }
            int index = selectedExecutionEventIndex(events);
            int target = Math.max(0, Math.min(events.size() - 1, index + delta));
            TraceEvent event = events.get(target);
            selectedExecutionEventSequence = event.sequence();
            publish("Reviewing debugger pause " + (target + 1) + " of " + events.size());
            selected.set(event);
        });
        return selected.get();
    }

    private void selectLatestExecutionEvent() {
        TraceEvent latest = latestExecutionEvent(recorder.snapshot());
        selectedExecutionEventSequence = latest == null ? null : latest.sequence();
    }

    private TraceEvent selectedExecutionEvent(TraceRun run) {
        if (run == null) {
            return null;
        }
        java.util.List<TraceEvent> events = run.executionEvents();
        if (events.isEmpty()) {
            return null;
        }
        int index = selectedExecutionEventIndex(events);
        return events.get(index);
    }

    private int selectedExecutionEventIndex(java.util.List<TraceEvent> events) {
        if (selectedExecutionEventSequence != null) {
            for (int index = 0; index < events.size(); index++) {
                if (events.get(index).sequence() == selectedExecutionEventSequence) {
                    return index;
                }
            }
        }
        return events.size() - 1;
    }

    private EventCursorAvailability eventCursorAvailability(
            TraceRun run,
            TraceEvent currentEvent
    ) {
        if (run == null || currentEvent == null) {
            return new EventCursorAvailability(false, false);
        }
        java.util.List<TraceEvent> events = run.executionEvents();
        int index = selectedExecutionEventIndex(events);
        return new EventCursorAvailability(index > 0, index + 1 < events.size());
    }

    private static TraceEvent latestExecutionEvent(TraceRun run) {
        if (run == null) {
            return null;
        }
        java.util.List<TraceEvent> events = run.executionEvents();
        return events.isEmpty() ? null : events.getLast();
    }

    private void runLater(Runnable action) {
        Application application = ApplicationManager.getApplication();
        application.invokeLater(() -> {
            if (!disposed && !project.isDisposed()) {
                action.run();
            }
        }, ModalityState.any());
    }

    private static void assertDispatchThread() {
        ApplicationManager.getApplication().assertIsDispatchThread();
    }

    private static String safeMessage(RuntimeException error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank()
                ? error.getClass().getSimpleName()
                : detail;
    }

    @FunctionalInterface
    private interface SessionCommand {
        void run(XDebugSession session);
    }

    private record EventCursorAvailability(boolean canPrevious, boolean canNext) {
    }

    private final class ManagerListener implements XDebuggerManagerListener {
        @Override
        public void processStarted(@NotNull XDebugProcess debugProcess) {
            runLater(() -> {
                XDebugSession session = debugProcess.getSession();
                observe(session);
                if (recorder.active() && boundSession == null) {
                    bind(session);
                }
            });
        }

        @Override
        public void processStopped(@NotNull XDebugProcess debugProcess) {
            runLater(() -> stopped(debugProcess.getSession()));
        }

        @Override
        public void currentSessionChanged(
                XDebugSession previousSession,
                XDebugSession currentSession
        ) {
            runLater(() -> {
                observe(currentSession);
                if (recorder.active() && boundSession == null) {
                    bind(currentSession);
                }
            });
        }
    }

    private final class SessionListener implements XDebugSessionListener {
        private final XDebugSession session;

        private SessionListener(XDebugSession session) {
            this.session = session;
        }

        @Override
        public void sessionPaused() {
            runLater(() -> paused(session));
        }

        @Override
        public void sessionResumed() {
            runLater(() -> resumed(session));
        }

        @Override
        public void sessionStopped() {
            runLater(() -> stopped(session));
        }

        @Override
        public void stackFrameChanged() {
            runLater(() -> frameChanged(session));
        }

    }
}
