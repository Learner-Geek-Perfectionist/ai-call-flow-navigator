package com.youngx.aicallflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** EDT-confined pure Java reducer that creates immutable {@link TraceRun} snapshots. */
final class LiveTraceRecorder {
    private final LongSupplier clock;
    private final Supplier<String> idSupplier;
    private final List<TraceEvent> events = new ArrayList<>();

    private TraceNodeMapper mapper;
    private String id;
    private String flowFingerprint;
    private String flowTitle;
    private String flowEntry;
    private long startedAtEpochMs;
    private Long endedAtEpochMs;
    private TraceRunState state = TraceRunState.IDLE;
    private String sessionName;
    private String lastMatchedNodeId;
    private long sequence;

    LiveTraceRecorder() {
        this(System::currentTimeMillis, () -> UUID.randomUUID().toString());
    }

    LiveTraceRecorder(LongSupplier clock, Supplier<String> idSupplier) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    void start(CallFlow flow) {
        Objects.requireNonNull(flow, "flow");
        mapper = new TraceNodeMapper(flow);
        id = idSupplier.get();
        flowFingerprint = fingerprint(flow);
        flowTitle = flow.title();
        flowEntry = flow.entry();
        startedAtEpochMs = clock.getAsLong();
        endedAtEpochMs = null;
        state = TraceRunState.WAITING_FOR_SESSION;
        sessionName = null;
        lastMatchedNodeId = null;
        sequence = 0;
        events.clear();
    }

    void sessionAttached(String name, boolean suspended) {
        requireActive();
        sessionName = name;
        state = suspended ? TraceRunState.PAUSED : TraceRunState.RUNNING;
        append(TraceEventKind.SESSION_ATTACHED, null, false);
    }

    void paused(TraceSourcePosition source) {
        paused(source, null);
    }

    void paused(TraceSourcePosition source, String contextLabel) {
        requireActive();
        state = TraceRunState.PAUSED;
        append(TraceEventKind.PAUSED, source, contextLabel, true);
    }

    void frameChanged(TraceSourcePosition source) {
        frameChanged(source, null);
    }

    void frameChanged(TraceSourcePosition source, String contextLabel) {
        requireActive();
        append(TraceEventKind.FRAME_CHANGED, source, contextLabel, false);
    }

    void resumed() {
        requireActive();
        state = TraceRunState.RUNNING;
        append(TraceEventKind.RESUMED, null, false);
    }

    void command(TraceEventKind kind) {
        requireActive();
        append(kind, null, false);
    }

    void sessionStopped() {
        complete(TraceEventKind.SESSION_STOPPED);
    }

    void recordingStopped() {
        complete(TraceEventKind.RECORDING_STOPPED);
    }

    boolean active() {
        return state != TraceRunState.IDLE && state != TraceRunState.COMPLETED;
    }

    TraceRunState state() {
        return state;
    }

    TraceRun snapshot() {
        if (state == TraceRunState.IDLE) {
            return null;
        }
        return new TraceRun(
                id,
                flowFingerprint,
                flowTitle,
                flowEntry,
                startedAtEpochMs,
                endedAtEpochMs,
                state,
                sessionName,
                events
        );
    }

    private void complete(TraceEventKind kind) {
        if (!active()) {
            return;
        }
        append(kind, null, false);
        endedAtEpochMs = clock.getAsLong();
        state = TraceRunState.COMPLETED;
    }

    private void append(
            TraceEventKind kind,
            TraceSourcePosition source,
            boolean updateExecutionNode
    ) {
        append(kind, source, null, updateExecutionNode);
    }

    private void append(
            TraceEventKind kind,
            TraceSourcePosition source,
            String contextLabel,
            boolean updateExecutionNode
    ) {
        TraceNodeMapper.Match match = source == null
                ? TraceNodeMapper.Match.unmatched()
                : mapper.match(source, lastMatchedNodeId);
        String previousNodeId = lastMatchedNodeId;
        TraceEvent event = new TraceEvent(
                ++sequence,
                clock.getAsLong(),
                kind,
                sessionName,
                contextLabel,
                source,
                previousNodeId,
                match.nodeId(),
                match.viaEdgeKind(),
                match.confidence(),
                match.candidateNodeIds()
        );
        events.add(event);
        if (updateExecutionNode && match.nodeId() != null) {
            lastMatchedNodeId = match.nodeId();
        }
    }

    private void requireActive() {
        if (!active()) {
            throw new IllegalStateException("No live debugger recording is active");
        }
    }

    private static String fingerprint(CallFlow flow) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, flow.version());
            update(digest, flow.title());
            update(digest, flow.entry());
            for (CallFlowNode node : flow.nodes()) {
                update(digest, node.id());
                update(digest, node.kind() == null ? null : node.kind().name());
                CallFlowLocation location = node.location();
                if (location != null) {
                    update(digest, location.path());
                    update(digest, Integer.toString(location.line()));
                    update(digest, location.symbol());
                }
            }
            for (CallFlowEdge edge : flow.edges()) {
                update(digest, edge.from());
                update(digest, edge.to());
                update(digest, edge.kind() == null ? null : edge.kind().name());
                update(digest, edge.label());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
    }
}
