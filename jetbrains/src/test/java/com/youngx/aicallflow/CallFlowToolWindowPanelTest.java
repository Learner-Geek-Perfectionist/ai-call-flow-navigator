package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class CallFlowToolWindowPanelTest {
    @Test
    void enterNavigatesTheSelectedNodeAndIgnoresAnEmptySelection() throws Exception {
        AtomicReference<CallFlowNode> navigated = new AtomicReference<>();
        AtomicInteger navigationCount = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            CallFlowNode first = node("first");
            CallFlowNode second = node("second");
            DefaultListModel<CallFlowNode> model = new DefaultListModel<>();
            model.addElement(first);
            model.addElement(second);
            JList<CallFlowNode> list = new JList<>(model);
            CallFlowToolWindowPanel.installEnterNavigation(list, node -> {
                navigated.set(node);
                navigationCount.incrementAndGet();
            });

            Object actionKey = list.getInputMap(JComponent.WHEN_FOCUSED).get(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
            );
            assertNotNull(actionKey);
            Action action = list.getActionMap().get(actionKey);
            assertNotNull(action);

            action.actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"));
            assertNull(navigated.get());
            assertEquals(0, navigationCount.get());

            list.setSelectedIndex(1);
            assertNull(navigated.get());
            action.actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"));
            assertSame(second, navigated.get());
            assertEquals(1, navigationCount.get());

            list.setSelectedIndex(0);
            assertSame(second, navigated.get());
            action.actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"));
            assertSame(first, navigated.get());
            assertEquals(2, navigationCount.get());
        });
    }

    private static CallFlowNode node(String id) {
        return new CallFlowNode(
                id,
                NodeKind.CALL,
                new CallFlowLocation("src/Main.kt", 1, 1, null, null, null, null),
                "Node " + id
        );
    }
}
