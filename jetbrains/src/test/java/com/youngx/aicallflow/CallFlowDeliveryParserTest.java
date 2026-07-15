package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CallFlowDeliveryParserTest {
    @Test
    void parsesDeliveryMetadataAndCallFlowFromTheSameDocument() {
        String json = validJson();

        CallFlowDeliveryParser.Request request = CallFlowDeliveryParser.parse(json);

        assertEquals(CallFlowDeliveryParser.DELIVERY_VERSION, request.delivery().version());
        assertEquals("request_ABC-123", request.delivery().requestId());
        assertEquals(1_700_000_000_000L, request.delivery().createdAtEpochMs());
        assertEquals(1_700_000_060_000L, request.delivery().expiresAtEpochMs());
        assertEquals("1.0", request.callFlow().version());
        assertEquals("Start flow", request.callFlow().title());
        assertEquals("entry", request.callFlow().entry());
        assertEquals(1, request.callFlow().nodes().size());
        assertSame(json, request.sourceJson());
    }

    @Test
    void rejectsProjectRootBecauseThePluginSuppliesItsRegisteredProject() {
        String json = validJson().replace(
                "\"createdAtEpochMs\"",
                "\"projectRoot\":\"/workspace/sample\",\"createdAtEpochMs\""
        );

        assertInvalid("projectRoot", json);
    }

    @Test
    void rejectsEveryUnknownDeliveryField() {
        String json = validJson().replace(
                "\"createdAtEpochMs\"",
                "\"traceId\":\"not-part-of-the-protocol\",\"createdAtEpochMs\""
        );

        assertInvalid("traceId", json);
    }

    @Test
    void parsesDeliveryWithoutRequiringTheCallFlowToBeValid() {
        String json = validJson().replace("\"entry\":\"entry\"", "\"entry\":\"missing\"");

        CallFlowDeliveryParser.Delivery delivery = CallFlowDeliveryParser.parseDelivery(json);

        assertEquals("request_ABC-123", delivery.requestId());
        assertThrows(IllegalArgumentException.class, () -> CallFlowDeliveryParser.parse(json));
    }

    @Test
    void rejectsMissingOrMalformedDeliveryMetadata() {
        assertInvalid("_delivery object is required", validCallFlowJson());
        assertInvalid("request JSON is required", null);
        assertInvalid("request JSON is required", "  ");
        assertInvalid("malformed JSON", "{");
        assertInvalid("malformed JSON", validJson() + " {}");
        assertInvalid(
                "_delivery.version must be a string",
                validJson().replace("\"version\":\"2.0\",\"requestId\"", "\"version\":2.0,\"requestId\"")
        );
        assertInvalid(
                "_delivery.version must be \"2.0\"",
                validJson().replace("\"version\":\"2.0\",\"requestId\"", "\"version\":\"1.0\",\"requestId\"")
        );
        assertInvalid("_delivery.requestId is required", validJson().replace("request_ABC-123", " "));
        assertInvalid("requestId has an invalid format", validJson().replace("request_ABC-123", "request.with.dots"));
        assertInvalid("timestamps must not be negative", validJson().replace("1700000000000", "-1"));
        assertInvalid("expiresAtEpochMs must be after", validJson().replace("1700000060000", "1700000000000"));
        assertInvalid("createdAtEpochMs must be a 64-bit integer", validJson().replace("1700000000000", "1.5"));
    }

    @Test
    void validatesTheEmbeddedCallFlowAfterDeliveryMetadata() {
        assertInvalid(
                "Invalid call flow: title is required",
                validJson().replace("\"title\":\"Start flow\"", "\"title\":\" \"")
        );
        assertInvalid(
                "Invalid call flow: entry references an unknown node",
                validJson().replace("\"entry\":\"entry\"", "\"entry\":\"missing\"")
        );
    }

    @Test
    void extractsOnlyValidFinalRequestFileNames() {
        assertEquals(
                "abc_DEF-123",
                CallFlowDeliveryParser.requestIdFromFileName(Path.of("request-abc_DEF-123.json"))
        );
        assertNull(CallFlowDeliveryParser.requestIdFromFileName(Path.of(".request-abc.tmp")));
        assertNull(CallFlowDeliveryParser.requestIdFromFileName(Path.of("request-abc.json.tmp")));
        assertNull(CallFlowDeliveryParser.requestIdFromFileName(Path.of("request-.json")));
        assertNull(CallFlowDeliveryParser.requestIdFromFileName(Path.of("request-bad.id.json")));
        assertNull(CallFlowDeliveryParser.requestIdFromFileName(Path.of("other-abc.json")));
        assertEquals(
                "abc_DEF-123",
                CallFlowDeliveryParser.requestIdFromClaimFileName(
                        Path.of("request-abc_DEF-123.json.claim-random_123")
                )
        );
        assertNull(CallFlowDeliveryParser.requestIdFromClaimFileName(
                Path.of("request-abc.json")
        ));
    }

    private static void assertInvalid(String expectedMessagePart, String json) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CallFlowDeliveryParser.parse(json)
        );
        assertTrue(
                error.getMessage().contains(expectedMessagePart),
                () -> "Expected error containing <" + expectedMessagePart + "> but was <" + error.getMessage() + ">"
        );
    }

    private static String validJson() {
        return """
                {
                  "_delivery":{"version":"2.0","requestId":"request_ABC-123","createdAtEpochMs":1700000000000,"expiresAtEpochMs":1700000060000},
                  "version":"1.0",
                  "title":"Start flow",
                  "nodes":[
                    {"id":"entry","kind":"entry","location":{"path":"app/src/Main.kt","line":1,"column":1},"summary":"Start"}
                  ],
                  "edges":[],
                  "entry":"entry"
                }
                """;
    }

    private static String validCallFlowJson() {
        return """
                {
                  "version":"1.0",
                  "title":"Start flow",
                  "nodes":[
                    {"id":"entry","kind":"entry","location":{"path":"app/src/Main.kt","line":1,"column":1},"summary":"Start"}
                  ],
                  "edges":[],
                  "entry":"entry"
                }
                """;
    }
}
