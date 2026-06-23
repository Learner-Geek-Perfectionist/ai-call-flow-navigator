package com.ouyang.asbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AndroidStudioBridgeSettingsTest {
    @Test
    void defaultsToEnabledBridgeOnDefaultPortUsingProjectBasePath() {
        AndroidStudioBridgeSettings settings = new AndroidStudioBridgeSettings();

        BridgeRuntimeConfig config = settings.toRuntimeConfig("/tmp/MyApplication");

        assertEquals("/tmp/MyApplication", config.projectRoot());
        assertEquals(17321, config.port());
        assertTrue(config.enabled());
    }

    @Test
    void configuredProjectRootOverridesProjectBasePath() {
        AndroidStudioBridgeSettings settings = new AndroidStudioBridgeSettings();
        settings.getState().projectRoot = "/tmp/AndroidRoot";
        settings.getState().port = 18181;

        BridgeRuntimeConfig config = settings.toRuntimeConfig("/tmp/MyApplication");

        assertEquals("/tmp/AndroidRoot", config.projectRoot());
        assertEquals(18181, config.port());
    }
}
