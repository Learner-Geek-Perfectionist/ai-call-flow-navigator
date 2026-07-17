package com.youngx.aicallflow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.IPopupChooserBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Swing UI for reviewing a PSI/UAST Call Flow and its runtime trace. */
public final class CallFlowToolWindowPanel extends JPanel
        implements Disposable,
        CallFlowSessionService.Listener,
        LiveDebuggerTraceService.Listener {
    private static final String NAVIGATE_SELECTED_NODE_ACTION = "navigate-selected-call-flow-node";
    private static final JBColor CURRENT_MARKER_COLOR = color(0x0B57D0, 0x8AB4F8);
    private static final JBColor VISITED_MARKER_COLOR = color(0x188038, 0x81C995);
    private static final JBColor PENDING_MARKER_COLOR = color(0x80868B, 0x9AA0A6);
    private static final JBColor DEPTH_FOREGROUND = color(0x5F6368, 0xBDC1C6);
    private static final JBColor DEPTH_BACKGROUND = color(0xE8EAED, 0x3C4043);

    private final Project project;
    private final CallFlowSessionService session;
    private final LiveDebuggerTraceService liveDebugger;
    private final DefaultListModel<CallFlowNode> nodeModel = new DefaultListModel<>();
    private final JBList<CallFlowNode> nodeList = new JBList<>(nodeModel);
    private final JBLabel titleLabel = new JBLabel("Waiting for an AI analysis request");
    private final JBLabel positionLabel = new JBLabel("Waiting for a Call Flow");
    private final JBLabel breadcrumbLabel = new JBLabel("No execution context");
    private final JBLabel transitionLabel = new JBLabel("No runtime trace");
    private final JBLabel statusLabel = new JBLabel("Preparing local analysis inbox");
    private final JBTextArea detailsArea = new JBTextArea();
    private final JButton previousButton = new JButton("Previous");
    private final JButton forwardButton = new JButton("Forward");
    private final JButton nextButton = new JButton("Next");
    private final JButton intoButton = new JButton("Into");
    private final JButton overButton = new JButton("Over");
    private final JButton outButton = new JButton("Out");
    private final JButton previousEventButton = new JButton("Previous Event");
    private final JButton nextEventButton = new JButton("Next Event");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton resumeButton = new JButton("Resume");
    private final JButton liveIntoButton = new JButton("Step Into");
    private final JButton liveOverButton = new JButton("Step Over");
    private final JButton liveOutButton = new JButton("Step Out");

    private CallFlowNode currentNode;
    private CallFlowPlayback.Visit currentVisit;
    private Map<String, String> frameLabelsById = Map.of();
    private CallFlowNavigator.NavigationResult navigationResult;
    private String navigationError;
    private LiveDebuggerSnapshot liveSnapshot =
            LiveDebuggerSnapshot.idle("Waiting for a static Call Flow");
    private boolean liveView;
    private String displayedRuntimeRunId;
    private long displayedRuntimeSequence = -1L;
    private volatile boolean disposed;

    public CallFlowToolWindowPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        session = CallFlowSessionService.getInstance(project);
        liveDebugger = LiveDebuggerTraceService.getInstance(project);
        setBorder(JBUI.Borders.empty(8));
        configureHeader();
        configureContent();
        configureActions();
        session.addListener(this, this);
        liveDebugger.addListener(this, this);
        initializeFromSession();
    }

    @Override
    public void flowLoaded(@NotNull CallFlow flow) {
        if (disposed || project.isDisposed()) {
            return;
        }
        replaceFlow(flow);
    }

    @Override
    public void currentNodeChanged(
            @NotNull CallFlowNode node,
            CallFlowNavigator.NavigationResult result,
            String error
    ) {
        if (disposed || project.isDisposed()) {
            return;
        }
        liveView = false;
        currentNode = node;
        currentVisit = session.currentVisit();
        navigationResult = result;
        navigationError = error;
        selectNode(node.id());
        showDetails(node);
        refreshPlaybackPresentation();
        updateButtons();
    }

    @Override
    public void snapshotChanged(@NotNull LiveDebuggerSnapshot snapshot) {
        if (disposed || project.isDisposed()) {
            return;
        }
        applyLiveSnapshot(snapshot);
    }

    @Override
    public void dispose() {
        disposed = true;
        nodeModel.clear();
    }

    private void configureHeader() {
        JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD));
        header.add(titleLabel, BorderLayout.NORTH);

        JPanel playbackContext = new JPanel();
        playbackContext.setLayout(new BoxLayout(playbackContext, BoxLayout.Y_AXIS));
        positionLabel.setFont(positionLabel.getFont().deriveFont(java.awt.Font.BOLD));
        breadcrumbLabel.setForeground(JBColor.GRAY);
        transitionLabel.setForeground(JBColor.BLUE);
        playbackContext.add(positionLabel);
        playbackContext.add(breadcrumbLabel);
        playbackContext.add(transitionLabel);
        playbackContext.setBorder(JBUI.Borders.emptyTop(4));
        header.add(playbackContext, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        JPanel staticControls = new JPanel(new FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(4),
                0
        ));
        staticControls.add(new JBLabel("Static:"));
        staticControls.add(previousButton);
        staticControls.add(forwardButton);
        staticControls.add(nextButton);
        staticControls.add(intoButton);
        staticControls.add(overButton);
        staticControls.add(outButton);
        controls.add(staticControls);

        JPanel liveControls = new JPanel(new FlowLayout(
                FlowLayout.LEFT,
                JBUI.scale(4),
                JBUI.scale(2)
        ));
        liveControls.add(new JBLabel("Live:"));
        liveControls.add(previousEventButton);
        liveControls.add(nextEventButton);
        liveControls.add(pauseButton);
        liveControls.add(resumeButton);
        liveControls.add(liveIntoButton);
        liveControls.add(liveOverButton);
        liveControls.add(liveOutButton);
        controls.add(liveControls);
        footer.add(controls, BorderLayout.NORTH);

        statusLabel.setForeground(JBColor.GRAY);
        footer.add(statusLabel, BorderLayout.SOUTH);
        header.add(footer, BorderLayout.SOUTH);
        header.setBorder(JBUI.Borders.emptyBottom(8));
        add(header, BorderLayout.NORTH);
    }

    private void configureContent() {
        nodeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeList.setCellRenderer(new NodeRenderer());
        nodeList.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                CallFlowNode selected = nodeList.getSelectedValue();
                if (selected != null) {
                    showDetails(selected);
                }
            }
        });
        installEnterNavigation(nodeList, this::navigateToNode);
        nodeList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2) {
                    return;
                }
                int index = nodeList.locationToIndex(event.getPoint());
                var bounds = index < 0 ? null : nodeList.getCellBounds(index, index);
                if (bounds != null && bounds.contains(event.getPoint())) {
                    navigateToNode(nodeModel.get(index), true);
                }
            }
        });

        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setBorder(JBUI.Borders.empty(8));

        JBScrollPane nodeScrollPane = new JBScrollPane(nodeList);
        nodeScrollPane.setBorder(BorderFactory.createTitledBorder("Flow nodes"));
        JBScrollPane detailsScrollPane = new JBScrollPane(detailsArea);
        detailsScrollPane.setBorder(BorderFactory.createTitledBorder("Node details"));

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                nodeScrollPane,
                detailsScrollPane
        );
        splitPane.setResizeWeight(0.42);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);
    }

    static void installEnterNavigation(
            JList<CallFlowNode> list,
            NodeNavigation navigation
    ) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(navigation, "navigation");
        list.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                NAVIGATE_SELECTED_NODE_ACTION
        );
        list.getActionMap().put(NAVIGATE_SELECTED_NODE_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                CallFlowNode selected = list.getSelectedValue();
                if (selected != null) {
                    try {
                        navigation.navigate(selected, false);
                    } finally {
                        list.requestFocusInWindow();
                    }
                }
            }
        });
    }

    private void navigateToNode(CallFlowNode node, boolean focusEditor) {
        liveView = false;
        session.jumpTo(node.id(), focusEditor);
    }

    @FunctionalInterface
    interface NodeNavigation {
        void navigate(CallFlowNode node, boolean focusEditor);
    }

    private void configureActions() {
        previousButton.addActionListener(event -> staticAction(session::previous));
        forwardButton.addActionListener(event -> staticAction(session::forward));
        nextButton.addActionListener(event -> staticAction(
                () -> chooseCandidate(session::next, nextButton)
        ));
        intoButton.addActionListener(event -> staticAction(
                () -> chooseCandidate(session::stepInto, intoButton)
        ));
        overButton.addActionListener(event -> staticAction(
                () -> chooseCandidate(session::stepOver, overButton)
        ));
        outButton.addActionListener(event -> staticAction(
                () -> chooseCandidate(session::stepOut, outButton)
        ));

        previousEventButton.setToolTipText(
                "Review the previous recorded pause; this does not reverse program execution"
        );
        nextEventButton.setToolTipText(
                "Review the next recorded pause; this does not resume program execution"
        );
        previousEventButton.addActionListener(event -> liveAction(liveDebugger::previousEvent));
        nextEventButton.addActionListener(event -> liveAction(liveDebugger::nextEvent));
        pauseButton.addActionListener(event -> liveAction(liveDebugger::pause));
        resumeButton.addActionListener(event -> liveAction(liveDebugger::resume));
        liveIntoButton.addActionListener(event -> liveAction(liveDebugger::stepInto));
        liveOverButton.addActionListener(event -> liveAction(liveDebugger::stepOver));
        liveOutButton.addActionListener(event -> liveAction(liveDebugger::stepOut));
        updateButtons();
    }

    private void staticAction(Runnable action) {
        liveView = false;
        action.run();
        refreshPlaybackPresentation();
        updateButtons();
    }

    private void liveAction(Runnable action) {
        liveView = true;
        action.run();
    }

    private void initializeFromSession() {
        CallFlow existingFlow = session.flow();
        if (existingFlow != null) {
            replaceFlow(existingFlow);
            CallFlowNode existingNode = session.current();
            if (existingNode != null) {
                currentNodeChanged(
                        existingNode,
                        session.lastNavigationResult(),
                        session.lastNavigationError()
                );
            }
            applyLiveSnapshot(liveDebugger.snapshot());
        } else {
            statusLabel.setText("Waiting for an ai-call-flow-navigator Skill request");
            detailsArea.setText(
                    "Start from the project root and explicitly invoke the Skill:\n\n"
                            + "Codex:  $ai-call-flow-navigator <topic>\n"
                            + "Claude: /ai-call-flow-navigator <topic>\n\n"
                            + "Android Studio will resolve the requested entry with PSI/UAST "
                            + "and add live Debugger observations automatically."
            );
            refreshPlaybackPresentation();
            updateButtons();
        }
    }

    private void replaceFlow(CallFlow flow) {
        liveView = false;
        displayedRuntimeRunId = null;
        displayedRuntimeSequence = -1L;
        liveSnapshot = LiveDebuggerSnapshot.idle("Waiting for live debugger observations");
        titleLabel.setText(flow.title());
        Map<String, String> frameLabels = new LinkedHashMap<>();
        if (flow.frames() != null) {
            for (CallFlowFrame frame : flow.frames()) {
                frameLabels.put(frame.id(), frame.label());
            }
        }
        frameLabelsById = Map.copyOf(frameLabels);
        nodeModel.clear();
        for (CallFlowNode node : flow.nodes()) {
            nodeModel.addElement(node);
        }
        currentNode = session.current();
        currentVisit = session.currentVisit();
        navigationResult = session.lastNavigationResult();
        navigationError = session.lastNavigationError();
        if (currentNode != null) {
            selectNode(currentNode.id());
            showDetails(currentNode);
        }
        refreshPlaybackPresentation();
        updateButtons();
    }

    private void selectNode(String nodeId) {
        for (int index = 0; index < nodeModel.size(); index++) {
            if (Objects.equals(nodeModel.get(index).id(), nodeId)) {
                nodeList.setSelectedIndex(index);
                nodeList.ensureIndexIsVisible(index);
                return;
            }
        }
    }

    private void showDetails(CallFlowNode node) {
        if (currentNode != null && Objects.equals(currentNode.id(), node.id())) {
            updateNavigationStatus();
        }
        detailsArea.setText(CallFlowPresentation.nodeDetails(node));
        detailsArea.setCaretPosition(0);
    }

    private void refreshPlaybackPresentation() {
        if (liveView && liveSnapshot.currentEvent() != null) {
            refreshLivePresentation();
            return;
        }
        CallFlowPlayback.Visit previous = previousVisit();
        CallFlowPresentation.Header header = CallFlowPresentation.header(
                currentVisit,
                previous,
                session.pathStep(),
                session.visitedNodeCount(),
                session.totalNodeCount(),
                session.unexploredEdgeCount(),
                currentVisit == null ? 0 : session.next().size()
        );
        positionLabel.setText(header.position());
        breadcrumbLabel.setText(header.breadcrumb());
        transitionLabel.setText(header.transition());
        positionLabel.setToolTipText(header.position());
        breadcrumbLabel.setToolTipText(header.breadcrumb());
        transitionLabel.setToolTipText(header.transition());
        nodeList.repaint();
    }

    private void applyLiveSnapshot(LiveDebuggerSnapshot snapshot) {
        liveSnapshot = snapshot;
        TraceRun run = snapshot.run();
        String runId = run == null ? null : run.id();
        if (!Objects.equals(displayedRuntimeRunId, runId)) {
            displayedRuntimeRunId = runId;
            displayedRuntimeSequence = -1L;
            if (snapshot.currentEvent() == null) {
                liveView = false;
            }
        }
        TraceEvent event = snapshot.currentEvent();
        if (event != null && event.sequence() != displayedRuntimeSequence) {
            displayedRuntimeSequence = event.sequence();
            liveView = true;
            if (event.nodeId() != null) {
                CallFlowNode node = nodeById(event.nodeId());
                if (node != null) {
                    selectNode(node.id());
                    showDetails(node);
                    session.revealRuntimeNode(node.id());
                }
            }
        }
        if (snapshot.recording() || snapshot.run() != null) {
            statusLabel.setText(snapshot.message());
        }
        refreshPlaybackPresentation();
        updateButtons();
    }

    private void refreshLivePresentation() {
        TraceEvent event = liveSnapshot.currentEvent();
        TraceRun run = liveSnapshot.run();
        if (event == null || run == null) {
            return;
        }
        List<TraceEvent> executionEvents = run.executionEvents();
        int eventIndex = executionEvents.indexOf(event);
        long hitNodes = executionEvents.stream()
                .map(TraceEvent::nodeId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        positionLabel.setText(
                "Live · " + liveStateLabel(liveSnapshot.state())
                        + " · Event " + (eventIndex + 1) + "/" + executionEvents.size()
                        + " · Hit " + hitNodes + "/" + session.totalNodeCount()
        );

        TraceSourcePosition source = event.source();
        String sourceText = source == null
                ? "No project source position"
                : (source.symbol() == null ? source.path() : source.symbol())
                        + " · " + source.path() + ":" + source.line();
        breadcrumbLabel.setText(
                (event.contextLabel() == null ? "Debugger" : event.contextLabel())
                        + " · " + sourceText
        );

        CallFlowNode node = nodeById(event.nodeId());
        CallFlowNode previous = nodeById(event.previousNodeId());
        if (node == null) {
            transitionLabel.setText(
                    "? Runtime position is not mapped to a static node"
                            + confidenceSuffix(event.confidence())
            );
        } else {
            String route = previous == null
                    ? CallFlowPresentation.nodeTitle(node, null)
                    : CallFlowPresentation.nodeTitle(previous, null)
                            + "  →  " + CallFlowPresentation.nodeTitle(node, null);
            transitionLabel.setText(
                    liveTransition(event.viaEdgeKind()) + " · " + route
                            + confidenceSuffix(event.confidence())
            );
        }
        positionLabel.setToolTipText(positionLabel.getText());
        breadcrumbLabel.setToolTipText(breadcrumbLabel.getText());
        transitionLabel.setToolTipText(transitionLabel.getText());
        nodeList.repaint();
    }

    private CallFlowNode nodeById(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        for (int index = 0; index < nodeModel.size(); index++) {
            CallFlowNode node = nodeModel.get(index);
            if (Objects.equals(node.id(), nodeId)) {
                return node;
            }
        }
        return null;
    }

    private static String liveStateLabel(TraceRunState state) {
        return switch (state) {
            case IDLE -> "Idle";
            case WAITING_FOR_SESSION -> "Waiting for Debugger";
            case RUNNING -> "Running";
            case PAUSED -> "Paused";
            case COMPLETED -> "Completed";
        };
    }

    private static String liveTransition(EdgeKind kind) {
        if (kind == null) {
            return "● LIVE";
        }
        return switch (kind) {
            case NEXT -> "→ LIVE NEXT";
            case STEP_INTO -> "↳ LIVE INTO";
            case STEP_OVER -> "↷ LIVE OVER";
            case STEP_OUT, RETURN -> "↰ LIVE OUT";
            case BRANCH_TRUE, BRANCH_FALSE -> "◇ LIVE BRANCH";
            case CALLBACK -> "↪ LIVE CALLBACK";
            case ASYNC -> "⚡ LIVE ASYNC";
        };
    }

    private static String confidenceSuffix(TraceMatchConfidence confidence) {
        return confidence == null
                || confidence == TraceMatchConfidence.EXACT
                || confidence == TraceMatchConfidence.LINE
                || confidence == TraceMatchConfidence.ADJACENT
                ? ""
                : " · " + confidence.name().toLowerCase().replace('_', ' ');
    }

    private CallFlowPlayback.Visit previousVisit() {
        List<CallFlowPlayback.Visit> path = session.currentPath();
        return path.size() < 2 ? null : path.get(path.size() - 2);
    }

    private void updateNavigationStatus() {
        if (navigationError != null) {
            statusLabel.setText("Navigation failed: " + navigationError);
            return;
        }
        if (navigationResult == null) {
            statusLabel.setText("Ready");
            return;
        }

        String status = switch (navigationResult.anchorMatch()) {
            case NOT_REQUESTED -> "exact coordinates";
            case EXACT -> "anchor matched";
            case RELOCATED -> "relocated by unique anchor";
            case NOT_FOUND -> "anchor not found; used coordinates";
            case AMBIGUOUS -> "anchor ambiguous; used coordinates";
        };
        statusLabel.setText(
                status + " — " + navigationResult.line() + ":" + navigationResult.column()
        );
    }

    private void updateButtons() {
        boolean loaded = session.current() != null;
        previousButton.setEnabled(loaded && session.canPrevious());
        forwardButton.setEnabled(loaded && session.canForward());
        nextButton.setEnabled(loaded && !session.next().isEmpty());
        intoButton.setEnabled(loaded && !session.stepInto().isEmpty());
        overButton.setEnabled(loaded && !session.stepOver().isEmpty());
        outButton.setEnabled(loaded && !session.stepOut().isEmpty());

        previousEventButton.setEnabled(liveSnapshot.canPreviousEvent());
        nextEventButton.setEnabled(liveSnapshot.canNextEvent());
        pauseButton.setEnabled(liveSnapshot.canPause());
        resumeButton.setEnabled(liveSnapshot.canResume());
        liveIntoButton.setEnabled(liveSnapshot.canStep());
        liveOverButton.setEnabled(liveSnapshot.canStep());
        liveOutButton.setEnabled(liveSnapshot.canStep());
    }

    private void chooseCandidate(
            Supplier<List<CallFlowPlayback.Candidate>> supplier,
            JComponent owner
    ) {
        List<CallFlowPlayback.Candidate> candidates = supplier.get();
        if (candidates.isEmpty()) {
            statusLabel.setText("No matching outgoing step");
            return;
        }
        if (candidates.size() == 1) {
            session.choose(candidates.getFirst());
            return;
        }

        IPopupChooserBuilder<CallFlowPlayback.Candidate> builder = JBPopupFactory
                .getInstance()
                .createPopupChooserBuilder(candidates)
                .setTitle("Choose call-flow branch")
                .setRenderer(new CandidateRenderer())
                .setItemChosenCallback(session::choose);
        builder.createPopup().showUnderneathOf(owner);
    }

    private String candidateText(CallFlowPlayback.Candidate candidate) {
        String label = candidate.edge().label();
        String title = CallFlowPresentation.nodeTitle(
                candidate.node(),
                frameLabel(candidate.node(), null)
        );
        return label == null || label.isBlank() ? title : label + "  →  " + title;
    }

    private String frameLabel(
            CallFlowNode node,
            CallFlowPlayback.Visit latestVisit
    ) {
        if (latestVisit != null
                && latestVisit.execution().exact()
                && !latestVisit.execution().stack().isEmpty()) {
            return latestVisit.execution().stack().getLast();
        }
        CallFlowExecution execution = node.execution();
        if (execution == null || execution.stack() == null || execution.stack().isEmpty()) {
            return null;
        }
        return frameLabelsById.get(execution.stack().getLast());
    }

    private static JBColor color(int light, int dark) {
        return new JBColor(new Color(light), new Color(dark));
    }

    private static SimpleTextAttributes markerAttributes(
            JList<?> list,
            boolean selected,
            String marker
    ) {
        if (selected) {
            return new SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_BOLD,
                    list.getSelectionForeground()
            );
        }
        Color foreground = switch (marker) {
            case "▶" -> CURRENT_MARKER_COLOR;
            case "✓" -> VISITED_MARKER_COLOR;
            default -> PENDING_MARKER_COLOR;
        };
        return new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, foreground);
    }

    private static SimpleTextAttributes depthAttributes(JList<?> list, boolean selected) {
        if (selected) {
            return new SimpleTextAttributes(
                    selectedChipBackground(list),
                    list.getSelectionForeground(),
                    null,
                    SimpleTextAttributes.STYLE_BOLD
                            | SimpleTextAttributes.STYLE_SMALLER
                            | SimpleTextAttributes.STYLE_OPAQUE
            );
        }
        return new SimpleTextAttributes(
                DEPTH_BACKGROUND,
                DEPTH_FOREGROUND,
                null,
                SimpleTextAttributes.STYLE_BOLD
                        | SimpleTextAttributes.STYLE_SMALLER
                        | SimpleTextAttributes.STYLE_OPAQUE
        );
    }

    private static SimpleTextAttributes badgeAttributes(
            JList<?> list,
            boolean selected,
            CallFlowPresentation.BadgeTone tone
    ) {
        if (selected) {
            return new SimpleTextAttributes(
                    selectedChipBackground(list),
                    list.getSelectionForeground(),
                    null,
                    SimpleTextAttributes.STYLE_BOLD | SimpleTextAttributes.STYLE_OPAQUE
            );
        }
        Color foreground = switch (tone) {
            case ENTRY -> color(0x6F42C1, 0xD7BAFF);
            case FLOW -> color(0x0B57D0, 0x8AB4F8);
            case CALL -> color(0x5B3CC4, 0xB9A7FF);
            case RETURN -> color(0x137333, 0x81C995);
            case BRANCH -> color(0x8A5300, 0xFDD663);
            case ASYNC -> color(0x007B83, 0x78D9EC);
            case CALLBACK -> color(0x9C2C72, 0xF4A7D3);
            case OVER -> color(0xA14200, 0xFFB86C);
            case DIRECT -> color(0x00639B, 0x8AB4F8);
            case UNKNOWN -> color(0xB3261E, 0xF28B82);
            case PLANNED -> color(0x5F6368, 0xBDC1C6);
        };
        Color background = switch (tone) {
            case ENTRY -> color(0xF0E8FF, 0x45385A);
            case FLOW -> color(0xE7F0FF, 0x263B57);
            case CALL -> color(0xEEE9FF, 0x3C3457);
            case RETURN -> color(0xE4F5E9, 0x294536);
            case BRANCH -> color(0xFFF2D2, 0x4C4023);
            case ASYNC -> color(0xDFF7FA, 0x25444B);
            case CALLBACK -> color(0xFCE7F3, 0x513349);
            case OVER -> color(0xFFF0E1, 0x4C3825);
            case DIRECT -> color(0xE1F2FF, 0x2B3F50);
            case UNKNOWN -> color(0xFCE8E6, 0x512D2B);
            case PLANNED -> color(0xF1F3F4, 0x36383B);
        };
        return new SimpleTextAttributes(
                background,
                foreground,
                null,
                SimpleTextAttributes.STYLE_BOLD | SimpleTextAttributes.STYLE_OPAQUE
        );
    }

    private static Color selectedChipBackground(JList<?> list) {
        Color selection = list.getSelectionBackground();
        Color foreground = list.getSelectionForeground();
        double luminance = (0.2126 * foreground.getRed()
                + 0.7152 * foreground.getGreen()
                + 0.0722 * foreground.getBlue()) / 255.0;
        return luminance >= 0.5 ? selection.darker() : selection.brighter();
    }

    private static SimpleTextAttributes titleAttributes(
            JList<?> list,
            boolean selected,
            boolean current,
            boolean pending
    ) {
        int style = current || selected
                ? SimpleTextAttributes.STYLE_BOLD
                : SimpleTextAttributes.STYLE_PLAIN;
        Color foreground = selected
                ? list.getSelectionForeground()
                : pending ? JBColor.GRAY : list.getForeground();
        return new SimpleTextAttributes(style, foreground);
    }

    private final class NodeRenderer extends ColoredListCellRenderer<CallFlowNode> {
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends CallFlowNode> list,
                CallFlowNode value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            TraceEvent runtimeEvent = liveSnapshot.currentEvent();
            boolean runtimeAvailable = liveView && runtimeEvent != null;
            TraceRun runtimeRun = liveSnapshot.run();
            long runtimeHits = runtimeAvailable && runtimeRun != null
                    ? runtimeRun.hitCount(value.id())
                    : 0;
            boolean current = runtimeAvailable
                    ? Objects.equals(runtimeEvent.nodeId(), value.id())
                    : currentNode != null && Objects.equals(currentNode.id(), value.id());
            boolean visited = runtimeAvailable
                    ? runtimeHits > 0
                    : session.hasVisited(value.id());
            CallFlowPlayback.Visit latestVisit = runtimeAvailable
                    ? null
                    : current ? currentVisit : session.latestVisit(value.id());
            CallFlowPresentation.NodeRow row = CallFlowPresentation.nodeRow(
                    value,
                    latestVisit,
                    frameLabel(value, latestVisit),
                    visited,
                    current
            );
            setIpad(JBUI.insets(1, 6 + row.indentWidth(), 1, 4));
            if (row.indentTruncated()) {
                append("… ", SimpleTextAttributes.GRAYED_ATTRIBUTES, false);
            }
            String marker = row.marker()
                    + (runtimeHits > 1 ? "×" + runtimeHits : "");
            append(marker + "  ", markerAttributes(list, selected, row.marker()), false);
            append(
                    " " + row.level() + (row.exact() ? "" : "~") + " ",
                    depthAttributes(list, selected),
                    false
            );
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES, false);
            append(
                    " " + row.badge() + " ",
                    badgeAttributes(list, selected, row.badgeTone()),
                    false
            );
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES, false);
            append(
                    row.title(),
                    titleAttributes(
                            list,
                            selected,
                            current,
                            row.pending() && runtimeHits == 0
                    ),
                    true
            );
        }
    }

    private final class CandidateRenderer
            extends ColoredListCellRenderer<CallFlowPlayback.Candidate> {
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends CallFlowPlayback.Candidate> list,
                CallFlowPlayback.Candidate value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            append(candidateText(value));
            String transition = value.edge().transition() == null
                    ? ""
                    : " · " + value.edge().transition().kind().name();
            append(
                    "  [" + value.edge().kind() + transition + "]",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES
            );
        }
    }
}
