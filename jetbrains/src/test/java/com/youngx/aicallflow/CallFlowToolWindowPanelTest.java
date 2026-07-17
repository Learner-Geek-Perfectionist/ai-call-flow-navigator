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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallFlowToolWindowPanelTest {
    @Test
    void enterNavigatesWithoutFocusingEditorAndRequestsListFocusAgain() throws Exception {
        AtomicReference<CallFlowNode> navigated = new AtomicReference<>();
        AtomicInteger navigationCount = new AtomicInteger();
        AtomicBoolean focusEditor = new AtomicBoolean(true);

        SwingUtilities.invokeAndWait(() -> {
            CallFlowNode first = node("first");
            CallFlowNode second = node("second");
            DefaultListModel<CallFlowNode> model = new DefaultListModel<>();
            model.addElement(first);
            model.addElement(second);
            FocusTrackingList list = new FocusTrackingList(model);
            CallFlowToolWindowPanel.installEnterNavigation(list, (node, shouldFocusEditor) -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                navigated.set(node);
                focusEditor.set(shouldFocusEditor);
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
            assertEquals(0, list.focusRequests());

            list.setSelectedIndex(1);
            assertNull(navigated.get());
            action.actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"));
            assertSame(second, navigated.get());
            assertEquals(1, navigationCount.get());
            assertFalse(focusEditor.get());
            assertEquals(1, list.focusRequests());
            assertEquals(1, list.getSelectedIndex());
            assertSame(second, list.getSelectedValue());

            list.setSelectedIndex(0);
            assertSame(second, navigated.get());
            action.actionPerformed(new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"));
            assertSame(first, navigated.get());
            assertEquals(2, navigationCount.get());
            assertFalse(focusEditor.get());
            assertEquals(2, list.focusRequests());
            assertEquals(0, list.getSelectedIndex());
            assertSame(first, list.getSelectedValue());
        });
    }

    @Test
    void enterRequestsListFocusEvenWhenNavigationFails() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CallFlowNode selected = node("selected");
            DefaultListModel<CallFlowNode> model = new DefaultListModel<>();
            model.addElement(selected);
            FocusTrackingList list = new FocusTrackingList(model);
            list.setSelectedIndex(0);
            CallFlowToolWindowPanel.installEnterNavigation(list, (node, focusEditor) -> {
                throw new IllegalStateException("navigation failed");
            });

            Object actionKey = list.getInputMap(JComponent.WHEN_FOCUSED).get(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
            );
            Action action = list.getActionMap().get(actionKey);
            assertNotNull(action);

            assertThrows(
                    IllegalStateException.class,
                    () -> action.actionPerformed(
                            new ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter")
                    )
            );
            assertEquals(1, list.focusRequests());
            assertEquals(0, list.getSelectedIndex());
            assertSame(selected, list.getSelectedValue());
        });
    }

    private static final class FocusTrackingList extends JList<CallFlowNode> {
        private int focusRequests;

        private FocusTrackingList(DefaultListModel<CallFlowNode> model) {
            super(model);
        }

        @Override
        public boolean requestFocusInWindow() {
            focusRequests++;
            return true;
        }

        private int focusRequests() {
            return focusRequests;
        }
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
