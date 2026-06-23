package com.ouyang.asbridge;

import java.awt.Component;
import java.util.Objects;

final class EditorFocusSupport {
    private final FocusRequester focusRequester;

    EditorFocusSupport(FocusRequester focusRequester) {
        this.focusRequester = Objects.requireNonNull(focusRequester);
    }

    void focus(Component component) {
        focusRequester.requestFocus(component, true);
    }

    @FunctionalInterface
    interface FocusRequester {
        void requestFocus(Component component, boolean forced);
    }
}
