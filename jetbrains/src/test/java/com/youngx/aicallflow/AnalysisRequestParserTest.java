package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AnalysisRequestParserTest {
    @Test
    void parsesTheOnlySupportedAnalysisRequestShape() {
        AnalysisRequest request = parse(validJson());

        assertEquals("3.0", request.delivery().version());
        assertEquals("request_ABC-123", request.delivery().requestId());
        assertEquals(1_700_000_000_000L, request.delivery().createdAtEpochMs());
        assertEquals(1_700_000_060_000L, request.delivery().expiresAtEpochMs());
        assertEquals("1.0", request.version());
        assertEquals("analysis-request", request.type());
        assertEquals("分析 MainActivity.onCreate 的启动流程", request.topic());
        assertEquals("app/src/main/java/com/example/MainActivity.kt", request.entry().path());
        assertEquals(17, request.entry().line());
        assertEquals(18, request.entry().column());
        assertEquals("com.example.MainActivity.onCreate", request.entry().symbol());
        assertEquals("static-and-live", request.strategy().mode());
        assertEquals("project-code", request.strategy().scope());
    }

    @Test
    void rejectsOldCallFlowAndProjectRootPayloads() {
        assertInvalid("root.nodes is not supported", validJson().replace(
                "\"strategy\":",
                "\"nodes\":[],\"strategy\":"
        ));
        assertInvalid("root.projectRoot is not supported", validJson().replace(
                "\"strategy\":",
                "\"projectRoot\":\"/tmp/project\",\"strategy\":"
        ));
    }

    @Test
    void rejectsUnknownDuplicateAndMissingFieldsAtEveryLevel() {
        assertInvalid("root.extra is not supported", validJson().replace(
                "\"topic\":",
                "\"extra\":true,\"topic\":"
        ));
        assertInvalid("_delivery.extra is not supported", validJson().replace(
                "\"createdAtEpochMs\":",
                "\"extra\":true,\"createdAtEpochMs\":"
        ));
        assertInvalid("entry.extra is not supported", validJson().replace(
                "\"line\":17",
                "\"extra\":true,\"line\":17"
        ));
        assertInvalid("strategy.extra is not supported", validJson().replace(
                "\"scope\":",
                "\"extra\":true,\"scope\":"
        ));
        assertInvalid("duplicate JSON field: topic", validJson().replace(
                "\"topic\":\"分析",
                "\"topic\":\"重复\",\"topic\":\"分析"
        ));
        assertInvalid("entry.symbol is required", validJson().replace(
                "com.example.MainActivity.onCreate",
                " "
        ));
    }

    @Test
    void enforcesVersionsTypeStrategyAndPositiveCoordinates() {
        assertInvalid("_delivery.version must be \"3.0\"", validJson().replace(
                "\"version\":\"3.0\",\"requestId\"",
                "\"version\":\"2.0\",\"requestId\""
        ));
        assertInvalid("version must be \"1.0\"", validJson().replace(
                "\"version\":\"1.0\"",
                "\"version\":\"2.0\""
        ));
        assertInvalid("type must be \"analysis-request\"", validJson().replace(
                "analysis-request",
                "call-flow"
        ));
        assertInvalid("strategy.mode must be \"static-and-live\"", validJson().replace(
                "static-and-live",
                "static"
        ));
        assertInvalid("strategy.scope must be \"project-code\"", validJson().replace(
                "project-code",
                "all-code"
        ));
        assertInvalid("entry.line must be a positive", validJson().replace("\"line\":17", "\"line\":0"));
        assertInvalid("entry.column must be a positive", validJson().replace("\"column\":18", "\"column\":-1"));
        assertInvalid("entry.line must be a 64-bit integer", validJson().replace("\"line\":17", "\"line\":1.5"));
    }

    @Test
    void acceptsOnlyNormalizedForwardSlashProjectRelativePaths() {
        assertInvalid("entry.path must be project-relative", withPath("/tmp/Main.kt"));
        assertInvalid("entry.path must be project-relative", withPath("C:/project/Main.kt"));
        assertInvalid("entry.path must be project-relative", withPath("file:Main.kt"));
        assertInvalid("entry.path must use '/' separators", withPath("app\\\\src\\\\Main.kt"));
        assertInvalid("entry.path must be a normalized", withPath("app/src/../Main.kt"));
        assertInvalid("entry.path must be a normalized", withPath("../Main.kt"));
        assertInvalid("entry.path must not have leading", withPath(" app/src/Main.kt"));
    }

    @Test
    void matchesPublisherStringLimitsAndIdentifierRules() {
        String maximumTopic = "题".repeat(16_384);
        assertEquals(maximumTopic, parse(validJson().replace(
                "分析 MainActivity.onCreate 的启动流程",
                maximumTopic
        )).topic());
        assertInvalid("topic is too long", validJson().replace(
                "分析 MainActivity.onCreate 的启动流程",
                maximumTopic + "题"
        ));

        String maximumSymbol = "a".repeat(512);
        assertEquals(maximumSymbol, parse(validJson().replace(
                "com.example.MainActivity.onCreate",
                maximumSymbol
        )).entry().symbol());
        assertInvalid("entry.symbol is too long", validJson().replace(
                "com.example.MainActivity.onCreate",
                maximumSymbol + "a"
        ));
        assertInvalid("entry.symbol must not have leading", validJson().replace(
                "com.example.MainActivity.onCreate",
                " com.example.MainActivity.onCreate"
        ));
    }

    @Test
    void rejectsMalformedDeliveryAndExtractsOnlyFinalFileNames() {
        assertInvalid("request JSON is required", null);
        assertInvalid("malformed JSON", "{");
        assertInvalid("_delivery.requestId has an invalid format", validJson().replace(
                "request_ABC-123",
                "bad.id"
        ));
        assertInvalid("expiresAtEpochMs must be after", validJson().replace(
                "1700000060000",
                "1700000000000"
        ));

        assertEquals(
                "abc_DEF-123",
                AnalysisRequestParser.requestIdFromFileName(Path.of("request-abc_DEF-123.json"))
        );
        assertNull(AnalysisRequestParser.requestIdFromFileName(Path.of(".request-abc.tmp")));
        assertNull(AnalysisRequestParser.requestIdFromFileName(Path.of("request-bad.id.json")));
        assertEquals(
                "abc_DEF-123",
                AnalysisRequestParser.requestIdFromClaimFileName(
                        Path.of("request-abc_DEF-123.json.claim-random_123")
                )
        );
    }

    private static String withPath(String path) {
        return validJson().replace(
                "app/src/main/java/com/example/MainActivity.kt",
                path
        );
    }

    private static AnalysisRequest parse(String json) {
        return AnalysisRequestParser.parseRequest(AnalysisRequestParser.parseEnvelope(json));
    }

    private static void assertInvalid(String expectedMessagePart, String json) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parse(json)
        );
        assertTrue(
                error.getMessage().contains(expectedMessagePart),
                () -> "Expected error containing <" + expectedMessagePart + "> but was <"
                        + error.getMessage() + ">"
        );
    }

    private static String validJson() {
        return """
                {
                  "_delivery":{"version":"3.0","requestId":"request_ABC-123","createdAtEpochMs":1700000000000,"expiresAtEpochMs":1700000060000},
                  "version":"1.0",
                  "type":"analysis-request",
                  "topic":"分析 MainActivity.onCreate 的启动流程",
                  "entry":{"path":"app/src/main/java/com/example/MainActivity.kt","line":17,"column":18,"symbol":"com.example.MainActivity.onCreate"},
                  "strategy":{"mode":"static-and-live","scope":"project-code"}
                }
                """;
    }
}
