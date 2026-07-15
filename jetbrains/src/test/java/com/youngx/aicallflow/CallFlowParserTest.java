package com.youngx.aicallflow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallFlowParserTest {
    private static final Gson GSON = new Gson();

    @Test
    void parsesCompleteVersionOneFlow() {
        CallFlow flow = CallFlowParser.parse(validJson());

        assertEquals("1.0", flow.version());
        assertEquals("Login flow", flow.title());
        assertEquals("4c9f12a", flow.project().revision());
        assertEquals("n1", flow.entry());
        assertEquals(2, flow.nodes().size());
        assertEquals(NodeKind.ENTRY, flow.nodes().get(0).kind());
        assertEquals(NodeKind.CALL, flow.nodes().get(1).kind());

        CallFlowLocation location = flow.nodes().get(0).location();
        assertEquals("app/src/main/java/com/example/LoginViewModel.kt", location.path());
        assertEquals(42, location.line());
        assertEquals(9, location.column());
        assertEquals(42, location.endLine());
        assertEquals(38, location.endColumn());
        assertEquals("LoginViewModel.login", location.symbol());
        assertEquals("repository.login(account)", location.anchorText());

        CallFlowEdge edge = flow.edges().get(0);
        assertEquals("n1", edge.from());
        assertEquals("n2", edge.to());
        assertEquals(EdgeKind.STEP_INTO, edge.kind());
        assertEquals("Open repository", edge.label());
        assertThrows(UnsupportedOperationException.class, () -> flow.nodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> flow.edges().clear());
    }

    @Test
    void acceptsOptionalProjectRangeMetadataAndEdgeLabel() {
        String json = validJson()
                .replace("\"project\":{\"revision\":\"4c9f12a\"},", "")
                .replace(",\"endLine\":42,\"endColumn\":38", "")
                .replace(",\"symbol\":\"LoginViewModel.login\"", "")
                .replace(",\"anchorText\":\"repository.login(account)\"", "")
                .replace(",\"label\":\"Open repository\"", "");

        CallFlow flow = CallFlowParser.parse(json);

        assertNull(flow.project());
        assertNull(flow.nodes().get(0).location().endLine());
        assertNull(flow.nodes().get(0).location().endColumn());
        assertNull(flow.nodes().get(0).location().symbol());
        assertNull(flow.nodes().get(0).location().anchorText());
        assertNull(flow.edges().get(0).label());
    }

    @Test
    void acceptsSingleNodeFlowWithNoEdges() {
        String json = """
                {
                  "version":"1.0",
                  "title":"Single declaration",
                  "nodes":[
                    {"id":"only","kind":"declaration","location":{"path":"app/src/Main.kt","line":1,"column":1},"summary":"The selected declaration"}
                  ],
                  "edges":[],
                  "entry":"only"
                }
                """;

        CallFlow flow = CallFlowParser.parse(json);

        assertEquals(1, flow.nodes().size());
        assertTrue(flow.edges().isEmpty());
        assertEquals("only", flow.entry());
    }

    @Test
    void mapsEveryNodeKindFromItsProtocolValue() {
        for (NodeKind kind : NodeKind.values()) {
            String protocolValue = kind.name().toLowerCase();
            String json = validJson().replace("\"kind\":\"entry\"", "\"kind\":\"" + protocolValue + "\"");

            assertEquals(kind, CallFlowParser.parse(json).nodes().get(0).kind());
        }
    }

    @Test
    void mapsEveryEdgeKindFromItsProtocolValue() {
        for (EdgeKind kind : EdgeKind.values()) {
            String protocolValue = kind.name().toLowerCase();
            String json = validJson().replace("\"kind\":\"step_into\"", "\"kind\":\"" + protocolValue + "\"");

            assertEquals(kind, CallFlowParser.parse(json).edges().get(0).kind());
        }
    }

    @Test
    void rejectsMalformedOrUnsupportedDocuments() {
        assertInvalid("request body is required", null);
        assertInvalid("request body is required", "   ");
        assertInvalid("malformed JSON", "{");
        assertInvalid("version must be \"1.0\"", validJson().replace("\"version\":\"1.0\"", "\"version\":\"2.0\""));
        assertInvalid("malformed JSON", validJson() + " {}");
    }

    @Test
    void rejectsMissingAndBlankRequiredValues() {
        assertInvalid("title is required", validJson().replace("\"title\":\"Login flow\"", "\"title\":\" \""));
        assertInvalid("project.revision is required", validJson().replace("\"revision\":\"4c9f12a\"", "\"revision\":\"\""));
        assertInvalid("nodes must not be empty", validJson().replace(nodesJson(), "[]"));
        assertInvalid("edges is required", validJson().replace(edgesJson(), "null"));
        assertInvalid("entry is required", validJson().replace("\"entry\":\"n1\"", "\"entry\":\"\""));
        assertInvalid("summary is required", validJson().replace("Starts login", " "));
        assertInvalid("location is required", validJson().replace(locationJson(), "null"));
    }

    @Test
    void rejectsUnknownEnumValues() {
        assertInvalid(
                "nodes[0].kind is required and must be a supported value",
                validJson().replace("\"kind\":\"entry\"", "\"kind\":\"method\"")
        );
        assertInvalid(
                "edges[0].kind is required and must be a supported value",
                validJson().replace("\"kind\":\"step_into\"", "\"kind\":\"jump\"")
        );
    }

    @Test
    void rejectsJsonTypeCoercion() {
        assertInvalid("malformed JSON", validJson().replace("\"version\":\"1.0\"", "\"version\":1.0"));
        assertInvalid("malformed JSON", validJson().replace("\"title\":\"Login flow\"", "\"title\":123"));
        assertInvalid("malformed JSON", validJson().replace("\"id\":\"n1\"", "\"id\":1"));
        assertInvalid("malformed JSON", validJson().replace("\"kind\":\"entry\"", "\"kind\":1"));
        assertInvalid("malformed JSON", validJson().replace("\"line\":42", "\"line\":\"42\""));
        assertInvalid("malformed JSON", validJson().replace("\"line\":42", "\"line\":null"));
    }

    @Test
    void rejectsDuplicateNodeIdsAndDanglingReferences() {
        assertInvalid("duplicate node id: n1", validJson().replace("\"id\":\"n2\"", "\"id\":\"n1\""));
        assertInvalid("entry references an unknown node: missing", validJson().replace("\"entry\":\"n1\"", "\"entry\":\"missing\""));
        assertInvalid("from references an unknown node: missing", validJson().replace("\"from\":\"n1\"", "\"from\":\"missing\""));
        assertInvalid("to references an unknown node: missing", validJson().replace("\"to\":\"n2\"", "\"to\":\"missing\""));
    }

    @Test
    void rejectsNonPositiveAndBackwardsLocations() {
        assertInvalid("line must be a positive 1-based coordinate", validJson().replace("\"line\":42", "\"line\":0"));
        assertInvalid("column must be a positive 1-based coordinate", validJson().replace("\"column\":9", "\"column\":0"));
        assertInvalid("endLine and nodes[0].location.endColumn must be provided together", validJson().replace(",\"endColumn\":38", ""));
        assertInvalid("end position must not precede", validJson().replace("\"endColumn\":38", "\"endColumn\":8"));
        assertInvalid("end position must not precede", validJson().replace("\"endLine\":42", "\"endLine\":41"));
    }

    @Test
    void rejectsPathsThatAreNotNormalizedProjectRelativePaths() {
        List<String> invalidPaths = List.of(
                "/tmp/LoginViewModel.kt",
                "C:/project/LoginViewModel.kt",
                "C:\\project\\LoginViewModel.kt",
                "file:app/LoginViewModel.kt",
                "../LoginViewModel.kt",
                "app/../LoginViewModel.kt",
                "app/./LoginViewModel.kt",
                "app//LoginViewModel.kt",
                "app/LoginViewModel.kt/",
                " app/LoginViewModel.kt",
                "app/Login\nViewModel.kt"
        );

        for (String path : invalidPaths) {
            assertInvalid("path", withFirstPath(path));
        }
    }

    @Test
    void acceptsPayloadLargerThanTwoMiB() {
        String summary = "x".repeat(12_000);
        StringBuilder nodes = new StringBuilder("[");
        for (int index = 0; index < 200; index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("""
                    {"id":"n%d","kind":"%s","location":{"path":"app/Main.kt","line":1,"column":1},"summary":"%s"}
                    """.formatted(index, index == 0 ? "entry" : "call", summary).strip());
        }
        nodes.append(']');
        String largeJson = """
                {"version":"1.0","title":"Large flow","nodes":%s,"edges":[],"entry":"n0"}
                """.formatted(nodes);
        assertTrue(largeJson.length() > 2 * 1024 * 1024);

        CallFlow flow = CallFlowParser.parse(largeJson);

        assertEquals("Large flow", flow.title());
        assertEquals(200, flow.nodes().size());
    }

    @Test
    void rejectsOversizedListsAndStrings() {
        assertInvalid(
                "title must be at most 512 characters",
                validJson().replace("Login flow", "t".repeat(513))
        );

        CallFlow tooManyNodes = new CallFlow(
                "1.0",
                "Flow",
                null,
                Collections.nCopies(CallFlowValidation.MAX_NODES + 1, null),
                List.of(new CallFlowEdge("n1", "n1", EdgeKind.NEXT, null)),
                "n1"
        );
        IllegalArgumentException nodesError = assertThrows(
                IllegalArgumentException.class,
                () -> CallFlowValidation.validate(tooManyNodes)
        );
        assertTrue(nodesError.getMessage().contains("nodes must contain at most"));

        CallFlow tooManyEdges = new CallFlow(
                "1.0",
                "Flow",
                null,
                List.of(new CallFlowNode(
                        "n1",
                        NodeKind.ENTRY,
                        new CallFlowLocation("app/Main.kt", 1, 1, null, null, null, null),
                        "Entry"
                )),
                Collections.nCopies(CallFlowValidation.MAX_EDGES + 1, null),
                "n1"
        );
        IllegalArgumentException edgesError = assertThrows(
                IllegalArgumentException.class,
                () -> CallFlowValidation.validate(tooManyEdges)
        );
        assertTrue(edgesError.getMessage().contains("edges must contain at most"));
    }

    private static String withFirstPath(String path) {
        return validJson().replace(
                "\"app/src/main/java/com/example/LoginViewModel.kt\"",
                GSON.toJson(path)
        );
    }

    private static void assertInvalid(String expectedMessagePart, String json) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CallFlowParser.parse(json)
        );
        assertTrue(
                error.getMessage().contains(expectedMessagePart),
                () -> "Expected error containing <" + expectedMessagePart + "> but was <" + error.getMessage() + ">"
        );
    }

    private static String validJson() {
        return """
                {
                  "version":"1.0",
                  "title":"Login flow",
                  "project":{"revision":"4c9f12a"},
                  "nodes":%s,
                  "edges":%s,
                  "entry":"n1"
                }
                """.formatted(nodesJson(), edgesJson());
    }

    private static String nodesJson() {
        return """
                [
                  {"id":"n1","kind":"entry","location":%s,"summary":"Starts login"},
                  {"id":"n2","kind":"call","location":{"path":"app/src/main/java/com/example/AuthRepository.kt","line":18,"column":5},"summary":"Calls repository"}
                ]
                """.formatted(locationJson()).strip();
    }

    private static String locationJson() {
        return """
                {"path":"app/src/main/java/com/example/LoginViewModel.kt","line":42,"column":9,"endLine":42,"endColumn":38,"symbol":"LoginViewModel.login","anchorText":"repository.login(account)"}
                """.strip();
    }

    private static String edgesJson() {
        return """
                [
                  {"from":"n1","to":"n2","kind":"step_into","label":"Open repository"}
                ]
                """.strip();
    }
}
