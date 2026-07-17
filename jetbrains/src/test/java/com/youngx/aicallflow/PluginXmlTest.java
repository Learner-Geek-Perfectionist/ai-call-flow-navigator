package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PluginXmlTest {
    @Test
    void declaresOnlyTheCurrentCallFlowComponents() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(
                Path.of("src/main/resources/META-INF/plugin.xml").toFile()
        );

        assertEquals("com.youngx.aicallflow", text(document, "id"));
        assertEquals("AI Call Flow Navigator", text(document, "name"));
        assertEquals("YoungX", text(document, "vendor"));
        assertEquals(0, document.getElementsByTagName("action").getLength());

        Element extensions = null;
        for (int index = 0; index < document.getElementsByTagName("extensions").getLength(); index++) {
            Element candidate = (Element) document.getElementsByTagName("extensions").item(index);
            if ("com.intellij".equals(candidate.getAttribute("defaultExtensionNs"))) {
                extensions = candidate;
                break;
            }
        }
        if (extensions == null) {
            throw new AssertionError("Missing com.intellij extension registrations");
        }
        List<String> registrations = new ArrayList<>();
        for (Node child = extensions.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element extension)) {
                continue;
            }
            registrations.add(switch (extension.getTagName()) {
                case "applicationService", "projectService" -> extension.getTagName()
                        + ":" + extension.getAttribute("serviceImplementation");
                case "postStartupActivity" -> extension.getTagName()
                        + ":" + extension.getAttribute("implementation");
                case "toolWindow" -> extension.getTagName()
                        + ":" + extension.getAttribute("id")
                        + ":" + extension.getAttribute("factoryClass");
                default -> extension.getTagName();
            });
        }

        assertEquals(List.of(
                "applicationService:com.youngx.aicallflow.AnalysisRequestFileInboxService",
                "projectService:com.youngx.aicallflow.AiCallFlowProjectService",
                "projectService:com.youngx.aicallflow.CallFlowSessionService",
                "projectService:com.youngx.aicallflow.StaticCallFlowGenerationService",
                "postStartupActivity:com.youngx.aicallflow.AiCallFlowStartupActivity",
                "toolWindow:Call Flow:com.youngx.aicallflow.CallFlowToolWindowFactory"
        ), registrations);
    }

    private static String text(Document document, String tagName) {
        return document.getElementsByTagName(tagName).item(0).getTextContent().trim();
    }
}
