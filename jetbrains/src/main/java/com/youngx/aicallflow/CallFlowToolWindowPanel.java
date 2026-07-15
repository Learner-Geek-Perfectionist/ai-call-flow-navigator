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

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Swing UI for reviewing and playing an AI-produced source call flow. */
public final class CallFlowToolWindowPanel extends JPanel
        implements Disposable, CallFlowSessionService.Listener {
    private final AiCallFlowProjectService projectService;
    private final CallFlowSessionService session;
    private final DefaultListModel<CallFlowNode> nodeModel = new DefaultListModel<>();
    private final JBList<CallFlowNode> nodeList = new JBList<>(nodeModel);
    private final JBLabel titleLabel = new JBLabel("Waiting for AI call flow");
    private final JBLabel statusLabel = new JBLabel("Connected automatically to the current project");
    private final JBTextArea detailsArea = new JBTextArea();
    private final JButton previousButton = new JButton("Previous");
    private final JButton forwardButton = new JButton("Forward");
    private final JButton nextButton = new JButton("Next");
    private final JButton intoButton = new JButton("Into");
    private final JButton overButton = new JButton("Over");
    private final JButton outButton = new JButton("Out");

    private CallFlowNode currentNode;
    private CallFlowNavigator.NavigationResult navigationResult;
    private String navigationError;

    public CallFlowToolWindowPanel(@NotNull Project project) {
        super(new BorderLayout());
        projectService = project.getService(AiCallFlowProjectService.class);
        session = CallFlowSessionService.getInstance(project);
        setBorder(JBUI.Borders.empty(8));
        configureHeader();
        configureContent();
        configureActions();
        session.addListener(this, this);
        initializeFromSession();
    }

    @Override
    public void flowLoaded(@NotNull CallFlow flow) {
        replaceFlow(flow);
    }

    @Override
    public void currentNodeChanged(
            @NotNull CallFlowNode node,
            CallFlowNavigator.NavigationResult result,
            String error
    ) {
        currentNode = node;
        navigationResult = result;
        navigationError = error;
        selectNode(node.id());
        showDetails(node);
        updateButtons();
    }

    @Override
    public void dispose() {
        nodeModel.clear();
    }

    private void configureHeader() {
        JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD));
        header.add(titleLabel, BorderLayout.NORTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        controls.add(previousButton);
        controls.add(forwardButton);
        controls.add(nextButton);
        controls.add(intoButton);
        controls.add(overButton);
        controls.add(outButton);
        header.add(controls, BorderLayout.CENTER);

        statusLabel.setForeground(JBColor.GRAY);
        header.add(statusLabel, BorderLayout.SOUTH);
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
        nodeList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2) {
                    return;
                }
                int index = nodeList.locationToIndex(event.getPoint());
                if (index >= 0
                        && nodeList.getCellBounds(index, index) != null
                        && nodeList.getCellBounds(index, index).contains(event.getPoint())) {
                    session.jumpTo(nodeModel.get(index).id());
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

    private void configureActions() {
        previousButton.addActionListener(event -> session.previous());
        forwardButton.addActionListener(event -> session.forward());
        nextButton.addActionListener(event -> chooseCandidate(session::next, nextButton));
        intoButton.addActionListener(event -> chooseCandidate(session::stepInto, intoButton));
        overButton.addActionListener(event -> chooseCandidate(session::stepOver, overButton));
        outButton.addActionListener(event -> chooseCandidate(session::stepOut, outButton));
        updateButtons();
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
        } else {
            statusLabel.setText(projectService.connectionStatus());
            detailsArea.setText(
                    "Ask your local AI to analyze a code path and deliver a Call Flow JSON file.\n\n"
                            + "The plugin listens for the next local Call Flow and binds it to "
                            + "this project automatically."
            );
            updateButtons();
        }
    }

    private void replaceFlow(CallFlow flow) {
        titleLabel.setText(flow.title());
        nodeModel.clear();
        for (CallFlowNode node : flow.nodes()) {
            nodeModel.addElement(node);
        }
        currentNode = session.current();
        navigationResult = session.lastNavigationResult();
        navigationError = session.lastNavigationError();
        if (currentNode != null) {
            selectNode(currentNode.id());
            showDetails(currentNode);
        }
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
        CallFlowLocation location = node.location();
        StringBuilder text = new StringBuilder();
        text.append(node.summary()).append("\n\n");
        text.append("ID: ").append(node.id()).append('\n');
        text.append("Kind: ").append(node.kind()).append('\n');
        if (location != null) {
            text.append("Source: ")
                    .append(location.path())
                    .append(':')
                    .append(location.line())
                    .append(':')
                    .append(location.column())
                    .append('\n');
            if (location.symbol() != null) {
                text.append("Symbol: ").append(location.symbol()).append('\n');
            }
            if (location.anchorText() != null) {
                text.append("Anchor: ").append(location.anchorText()).append('\n');
            }
        }

        if (currentNode != null && Objects.equals(currentNode.id(), node.id())) {
            text.append("\nNavigation: ").append(navigationDescription());
        }
        detailsArea.setText(text.toString());
        detailsArea.setCaretPosition(0);
    }

    private String navigationDescription() {
        if (navigationError != null) {
            statusLabel.setText("Navigation failed: " + navigationError);
            return "failed — " + navigationError;
        }
        if (navigationResult == null) {
            statusLabel.setText("Ready");
            return "ready";
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
        return status;
    }

    private void updateButtons() {
        boolean loaded = session.current() != null;
        previousButton.setEnabled(loaded && session.canPrevious());
        forwardButton.setEnabled(loaded && session.canForward());
        nextButton.setEnabled(loaded && !session.next().isEmpty());
        intoButton.setEnabled(loaded && !session.stepInto().isEmpty());
        overButton.setEnabled(loaded && !session.stepOver().isEmpty());
        outButton.setEnabled(loaded && !session.stepOut().isEmpty());
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

    private static String candidateText(CallFlowPlayback.Candidate candidate) {
        String label = candidate.edge().label();
        if (label == null || label.isBlank()) {
            label = candidate.node().summary();
        }
        return label + "  →  " + candidate.node().id();
    }

    private static final class NodeRenderer extends ColoredListCellRenderer<CallFlowNode> {
        @Override
        protected void customizeCellRenderer(
                @NotNull JList<? extends CallFlowNode> list,
                CallFlowNode value,
                int index,
                boolean selected,
                boolean hasFocus
        ) {
            append(value.id(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append("  " + value.summary(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }

    private static final class CandidateRenderer
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
            append("  [" + value.edge().kind() + "]", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }
}
