package com.ouyang.asbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeHtmlScriptTest {
    @Test
    void helperScriptUsesDataTargetsWithoutNavigableHrefs() {
        String script = BridgeHtmlScript.content();

        assertTrue(script.contains("document.currentScript"));
        assertTrue(script.contains("data-as-path"));
        assertTrue(script.contains("composedPath"));
        assertTrue(script.contains("auxclick"));
        assertTrue(script.contains("keydown"));
        assertTrue(script.contains("stopImmediatePropagation"));
        assertTrue(script.contains("sendBeacon"));
        assertFalse(script.toLowerCase().contains("href"));
        assertFalse(script.contains("as-src"));
    }
}
