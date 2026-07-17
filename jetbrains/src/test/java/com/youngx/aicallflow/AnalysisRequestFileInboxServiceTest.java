package com.youngx.aicallflow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class AnalysisRequestFileInboxServiceTest {
    private static final Gson GSON = new Gson();
    private static final long NOW = 1_700_000_100_000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesTheV3TempExchangeAndAcceptsOnlyAtomicallyPublishedRequests() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp")).toRealPath();
        AtomicInteger receiveCount = new AtomicInteger();
        AtomicReference<AnalysisRequest> received = new AtomicReference<>();
        AnalysisRequestFileInboxService service = new AnalysisRequestFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (AnalysisRequestFileInboxService.Registration ignored = service.register(
                successfulReceiver(receiveCount, received)
        )) {
            Path protocolRoot = protocolRoot(configuredTempRoot);
            assertEquals(protocolRoot.resolve("inbox"), service.inboxDirectory());
            assertEquals(protocolRoot.resolve("receipts"), service.receiptsDirectory());
            assertTrue(Files.isRegularFile(protocolRoot.resolve(".consumer.lock")));

            String requestId = "accepted-1";
            String sourceJson = requestJson(requestId, NOW - 1_000L, NOW + 60_000L);
            Path temporaryRequest = service.inboxDirectory().resolve(".request-" + requestId + ".tmp");
            Path finalRequest = service.inboxDirectory().resolve("request-" + requestId + ".json");
            Files.writeString(temporaryRequest, sourceJson);

            service.scanOnce();
            assertEquals(0, receiveCount.get());
            assertTrue(Files.isRegularFile(temporaryRequest));
            assertFalse(Files.exists(receiptPath(service, requestId)));

            Files.move(temporaryRequest, finalRequest, StandardCopyOption.ATOMIC_MOVE);
            service.scanOnce();
            awaitRegularFile(receiptPath(service, requestId));

            JsonObject receipt = readReceipt(service, requestId);
            assertEquals(Set.of(
                    "version", "requestId", "status", "completedAtEpochMs",
                    "topic", "entry", "generated"
            ), receipt.keySet());
            assertEquals("3.0", receipt.get("version").getAsString());
            assertEquals(requestId, receipt.get("requestId").getAsString());
            assertEquals("accepted", receipt.get("status").getAsString());
            assertEquals(NOW, receipt.get("completedAtEpochMs").getAsLong());
            assertEquals("MainActivity.onCreate startup", receipt.get("topic").getAsString());
            JsonObject entry = receipt.getAsJsonObject("entry");
            assertEquals(Set.of("path", "line", "column", "symbol"), entry.keySet());
            assertEquals("app/src/main/java/com/example/MainActivity.kt", entry.get("path").getAsString());
            assertEquals(17, entry.get("line").getAsInt());
            assertEquals(18, entry.get("column").getAsInt());
            assertEquals("com.example.MainActivity.onCreate", entry.get("symbol").getAsString());
            JsonObject generated = receipt.getAsJsonObject("generated");
            assertEquals(Set.of("nodeCount", "edgeCount", "entryNodeId"), generated.keySet());
            assertEquals(1, generated.get("nodeCount").getAsInt());
            assertEquals(0, generated.get("edgeCount").getAsInt());
            assertEquals("entry", generated.get("entryNodeId").getAsString());
            assertEquals(1, receiveCount.get());
            assertEquals(requestId, received.get().delivery().requestId());
            assertFalse(Files.exists(finalRequest));
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void acceptsAnAnalysisRequestLargerThanTwoMiB() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        AnalysisRequestFileInboxService service = new AnalysisRequestFileInboxService(FIXED_CLOCK, configuredTempRoot);
        try (AnalysisRequestFileInboxService.Registration ignored = service.register(
                successfulReceiver(new AtomicInteger(), new AtomicReference<>())
        )) {
            String requestId = "large-request";
            publish(
                    service,
                    requestId,
                    " ".repeat(2 * 1024 * 1024 + 1)
                            + requestJson(requestId, NOW - 1_000L, NOW + 60_000L)
            );
            service.scanOnce();
            awaitRegularFile(receiptPath(service, requestId));
            assertEquals("accepted", readReceipt(service, requestId).get("status").getAsString());
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void rejectsAmbiguousInvalidExpiredMismatchedAndOldCallFlowRequests() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        AnalysisRequestFileInboxService service = new AnalysisRequestFileInboxService(FIXED_CLOCK, configuredTempRoot);
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();

        try (AnalysisRequestFileInboxService.Registration first = service.register(
                successfulReceiver(firstCount, new AtomicReference<>())
        ); AnalysisRequestFileInboxService.Registration second = service.register(
                successfulReceiver(secondCount, new AtomicReference<>())
        )) {
            publish(service, "ambiguous", requestJson("ambiguous", NOW - 1_000L, NOW + 60_000L));
            service.scanOnce();
            assertRejected(service, "ambiguous", "AMBIGUOUS_PROJECT");
            assertEquals(0, firstCount.get());
            assertEquals(0, secondCount.get());

            second.close();
            publish(service, "invalid-json", "{");
            service.scanOnce();
            assertRejected(service, "invalid-json", "INVALID_DELIVERY");

            String oldFlowId = "old-flow";
            publish(service, oldFlowId, oldCallFlowJson(oldFlowId));
            service.scanOnce();
            assertRejected(service, oldFlowId, "INVALID_ANALYSIS_REQUEST");

            String invalidRequestId = "invalid-request";
            publish(
                    service,
                    invalidRequestId,
                    requestJson(invalidRequestId, NOW - 1_000L, NOW + 60_000L)
                            .replace("\"scope\":\"project-code\"", "\"scope\":\"all-code\"")
            );
            service.scanOnce();
            assertRejected(service, invalidRequestId, "INVALID_ANALYSIS_REQUEST");

            publish(service, "expired", requestJson("expired", NOW - 2_000L, NOW - 1_000L));
            service.scanOnce();
            assertRejected(service, "expired", "REQUEST_EXPIRED");

            publish(service, "filename-id", requestJson("document-id", NOW - 1_000L, NOW + 60_000L));
            service.scanOnce();
            assertRejected(service, "filename-id", "REQUEST_ID_MISMATCH");
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void reportsAnalysisFailuresAndAllowsAReplacementRegistration() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        AnalysisRequestFileInboxService service = new AnalysisRequestFileInboxService(FIXED_CLOCK, configuredTempRoot);
        try (AnalysisRequestFileInboxService.Registration registration = service.register(
                request -> CompletableFuture.failedFuture(new IOException("cannot analyze"))
        )) {
            publish(service, "analysis-failure", requestJson(
                    "analysis-failure", NOW - 1_000L, NOW + 60_000L
            ));
            service.scanOnce();
            assertRejected(service, "analysis-failure", "ANALYSIS_FAILED");
            registration.close();
            try (AnalysisRequestFileInboxService.Registration ignored = service.register(
                    successfulReceiver(new AtomicInteger(), new AtomicReference<>())
            )) {
                assertTrue(service.inboxDirectory().startsWith(configuredTempRoot.toRealPath()));
            }
        } finally {
            service.dispose();
        }

        assertThrows(IOException.class, () -> service.register(
                request -> CompletableFuture.completedFuture(generatedFlow())
        ));
    }

    @Test
    void consumerLockAndStaleClaimPreserveAtMostOnceReceiptSemantics() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        CompletableFuture<CallFlow> unfinished = new CompletableFuture<>();
        AnalysisRequestFileInboxService first = new AnalysisRequestFileInboxService(FIXED_CLOCK, configuredTempRoot);
        AnalysisRequestFileInboxService second = new AnalysisRequestFileInboxService(FIXED_CLOCK, configuredTempRoot);
        AnalysisRequestFileInboxService.Registration firstRegistration = first.register(request -> {
            firstCount.incrementAndGet();
            return unfinished;
        });
        AnalysisRequestReceiver secondReceiver = successfulReceiver(secondCount, new AtomicReference<>());

        try {
            IOException lockError = assertThrows(IOException.class, () -> second.register(secondReceiver));
            assertTrue(lockError.getMessage().contains("Another Android Studio process"));
            String requestId = "lock-takeover";
            Path pending = publish(first, requestId, requestJson(
                    requestId, NOW - 1_000L, NOW + 60_000L
            ));
            first.scanOnce();
            assertEquals(1, firstCount.get());
            first.dispose();
            firstRegistration.close();
            assertTrue(Files.isRegularFile(pending));

            try (AnalysisRequestFileInboxService.Registration ignored = second.register(secondReceiver)) {
                second.scanOnce();
                awaitRegularFile(receiptPath(second, requestId));
                assertEquals("accepted", readReceipt(second, requestId).get("status").getAsString());
                assertEquals(1, secondCount.get());

                String staleId = "crash-recovery";
                Path claimed = second.inboxDirectory().getParent()
                        .resolve("processing")
                        .resolve("request-" + staleId + ".json.claim-crashed_process");
                Files.writeString(claimed, requestJson(staleId, NOW - 1_000L, NOW + 60_000L));
                Files.setLastModifiedTime(claimed, FileTime.fromMillis(NOW - 11L * 60L * 1_000L));
                second.scanOnce();
                awaitRegularFile(receiptPath(second, staleId));
                assertEquals("accepted", readReceipt(second, staleId).get("status").getAsString());
                assertProcessingEmpty(second);
            }
        } finally {
            second.dispose();
            first.dispose();
            firstRegistration.close();
        }
    }

    @Test
    void rejectsUnsafeFixedMailboxRootsWithoutRandomFallback() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        Path channelRoot = configuredTempRoot.resolve(AnalysisRequestFileInboxService.CHANNEL_DIRECTORY);
        Files.writeString(channelRoot, "occupied");
        AnalysisRequestFileInboxService service = new AnalysisRequestFileInboxService(FIXED_CLOCK, configuredTempRoot);
        try {
            assertThrows(IOException.class, () -> service.register(
                    request -> CompletableFuture.completedFuture(generatedFlow())
            ));
            assertTrue(Files.isRegularFile(channelRoot, LinkOption.NOFOLLOW_LINKS));
            assertNoRandomFallback(configuredTempRoot);
        } finally {
            service.dispose();
        }
    }

    private static AnalysisRequestReceiver successfulReceiver(
            AtomicInteger receiveCount,
            AtomicReference<AnalysisRequest> received
    ) {
        return request -> {
            receiveCount.incrementAndGet();
            received.set(request);
            return CompletableFuture.completedFuture(generatedFlow());
        };
    }

    private static CallFlow generatedFlow() {
        return new CallFlow(
                "1.0",
                "generated",
                List.of(new CallFlowNode(
                        "entry",
                        NodeKind.ENTRY,
                        new CallFlowLocation("src/Main.kt", 1, 1, null, null, "Main.main", null),
                        "entry"
                )),
                List.of(),
                "entry"
        );
    }

    private static Path publish(
            AnalysisRequestFileInboxService service,
            String requestId,
            String sourceJson
    ) throws IOException {
        Path temporary = service.inboxDirectory().resolve(".request-" + requestId + ".tmp");
        Path target = service.inboxDirectory().resolve("request-" + requestId + ".json");
        Files.writeString(temporary, sourceJson);
        return Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void assertRejected(
            AnalysisRequestFileInboxService service,
            String requestId,
            String expectedCode
    ) throws Exception {
        awaitRegularFile(receiptPath(service, requestId));
        JsonObject receipt = readReceipt(service, requestId);
        assertEquals(Set.of("version", "requestId", "status", "completedAtEpochMs", "error"), receipt.keySet());
        assertEquals("3.0", receipt.get("version").getAsString());
        assertEquals("rejected", receipt.get("status").getAsString());
        assertEquals(requestId, receipt.get("requestId").getAsString());
        assertEquals(expectedCode, receipt.getAsJsonObject("error").get("code").getAsString());
        assertFalse(receipt.getAsJsonObject("error").get("message").getAsString().isBlank());
    }

    private static JsonObject readReceipt(AnalysisRequestFileInboxService service, String requestId)
            throws IOException {
        return JsonParser.parseString(Files.readString(receiptPath(service, requestId))).getAsJsonObject();
    }

    private static Path receiptPath(AnalysisRequestFileInboxService service, String requestId) {
        return service.receiptsDirectory().resolve("receipt-" + requestId + ".json");
    }

    private static Path protocolRoot(Path temporaryRoot) {
        return temporaryRoot
                .resolve(AnalysisRequestFileInboxService.CHANNEL_DIRECTORY)
                .resolve("file-ipc-v3");
    }

    private static void assertNoRandomFallback(Path temporaryRoot) throws IOException {
        try (var paths = Files.list(temporaryRoot)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(
                    AnalysisRequestFileInboxService.CHANNEL_DIRECTORY + "-"
            )));
        }
    }

    private static void assertProcessingEmpty(AnalysisRequestFileInboxService service) throws IOException {
        Path processing = service.inboxDirectory().getParent().resolve("processing");
        try (var paths = Files.list(processing)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().contains(".claim-")));
        }
    }

    private static void awaitRegularFile(Path path) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + path);
    }

    private static String requestJson(String requestId, long createdAtEpochMs, long expiresAtEpochMs) {
        return """
                {
                  "_delivery":{"version":"3.0","requestId":%s,"createdAtEpochMs":%d,"expiresAtEpochMs":%d},
                  "version":"1.0",
                  "type":"analysis-request",
                  "topic":"MainActivity.onCreate startup",
                  "entry":{"path":"app/src/main/java/com/example/MainActivity.kt","line":17,"column":18,"symbol":"com.example.MainActivity.onCreate"},
                  "strategy":{"mode":"static-and-live","scope":"project-code"}
                }
                """.formatted(GSON.toJson(requestId), createdAtEpochMs, expiresAtEpochMs);
    }

    private static String oldCallFlowJson(String requestId) {
        return """
                {
                  "_delivery":{"version":"3.0","requestId":%s,"createdAtEpochMs":%d,"expiresAtEpochMs":%d},
                  "version":"1.0",
                  "title":"old",
                  "nodes":[],
                  "edges":[],
                  "entry":"entry"
                }
                """.formatted(GSON.toJson(requestId), NOW - 1_000L, NOW + 60_000L);
    }
}
