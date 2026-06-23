package com.ouyang.asbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginXmlTest {
    @Test
    void declaresAndroidStudioBridgeServicesAndSettings() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"));

        assertTrue(xml.contains("<id>com.ouyang.asbridge</id>"));
        assertTrue(xml.contains("<name>Android Studio Bridge</name>"));
        assertTrue(xml.contains("AndroidStudioBridgeSettings"));
        assertTrue(xml.contains("AndroidStudioBridgeProjectService"));
        assertTrue(xml.contains("AndroidStudioBridgeStartupActivity"));
        assertTrue(xml.contains("AndroidStudioBridgeConfigurable"));
        assertFalse(xml.contains("asrclinks"));
        assertFalse(xml.contains("Android Source Links"));
        assertFalse(xml.contains("org.intellij.plugins.markdown"));
    }
}
