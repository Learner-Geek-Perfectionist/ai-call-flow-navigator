package com.ouyang.asbridge;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorFocusSupportTest {
    @Test
    void requestsForcedFocusWhenEditorFocusIsEnabled() {
        List<FocusRequest> requests = new ArrayList<>();
        EditorFocusSupport focusSupport = new EditorFocusSupport((component, forced) ->
                requests.add(new FocusRequest(component, forced))
        );
        JPanel editorComponent = new JPanel();

        focusSupport.focusIfRequested(editorComponent, true);

        assertEquals(1, requests.size());
        assertSame(editorComponent, requests.get(0).component());
        assertTrue(requests.get(0).forced());
    }

    @Test
    void doesNotRequestFocusWhenEditorFocusIsDisabled() {
        List<FocusRequest> requests = new ArrayList<>();
        EditorFocusSupport focusSupport = new EditorFocusSupport((component, forced) ->
                requests.add(new FocusRequest(component, forced))
        );

        focusSupport.focusIfRequested(new JPanel(), false);

        assertTrue(requests.isEmpty());
    }

    private record FocusRequest(Component component, boolean forced) {
    }
}
