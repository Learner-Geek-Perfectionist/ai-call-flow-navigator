package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginXmlTest {
    @Test
    void declaresZeroConfigurationAiCallFlowServices() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"));

        assertTrue(xml.contains("<id>com.youngx.aicallflow</id>"));
        assertTrue(xml.contains("<name>AI Call Flow Navigator</name>"));
        assertTrue(xml.contains(">YoungX</vendor>"));
        assertTrue(xml.contains("<applicationService serviceImplementation=\"com.youngx.aicallflow.CallFlowFileInboxService\"/>"));
        assertTrue(xml.contains("com.youngx.aicallflow.AiCallFlowProjectService"));
        assertTrue(xml.contains("com.youngx.aicallflow.AiCallFlowStartupActivity"));
        assertTrue(xml.contains("com.youngx.aicallflow.CallFlowSessionService"));
        assertTrue(xml.contains("com.youngx.aicallflow.CallFlowToolWindowFactory"));
        assertTrue(xml.contains("id=\"Call Flow\""));
        assertFalse(xml.contains("AndroidStudioBridgeSettings"));
        assertFalse(xml.contains("AndroidStudioBridgeConfigurable"));
        assertFalse(xml.contains("projectConfigurable"));
        assertFalse(xml.contains("asrclinks"));
        assertFalse(xml.contains("Android Source Links"));
        assertFalse(xml.contains("org.intellij.plugins.markdown"));
        assertFalse(xml.contains("com.ouyang.asbridge"));
        assertFalse(xml.contains("BridgeHttpServer"));
        assertFalse(xml.contains("BridgeRuntimeConfig"));
        assertFalse(xml.contains("BridgeDiscovery"));
    }
}
