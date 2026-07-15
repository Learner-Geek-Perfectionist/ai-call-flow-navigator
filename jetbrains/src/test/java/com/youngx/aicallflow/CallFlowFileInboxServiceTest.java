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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class CallFlowFileInboxServiceTest {
    private static final Gson GSON = new Gson();
    private static final long NOW = 1_700_000_100_000L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(NOW),
            ZoneOffset.UTC
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesTheV2JavaIoTempExchangeDirectoryAndAcceptsAtomicPublication() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp")).toRealPath();
        Path stored = temporaryDirectory.resolve("stored-call-flow.json");
        AtomicInteger receiveCount = new AtomicInteger();
        AtomicReference<String> receivedJson = new AtomicReference<>();
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (CallFlowFileInboxService.Registration ignored = service.register(
                persistingReceiver(stored, receiveCount, receivedJson)
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
            assertEquals("2.0", receipt.get("version").getAsString());
            assertEquals(requestId, receipt.get("requestId").getAsString());
            assertEquals("accepted", receipt.get("status").getAsString());
            assertFalse(receipt.has("projectRoot"));
            assertEquals(NOW, receipt.get("completedAtEpochMs").getAsLong());
            assertEquals(stored.toAbsolutePath().normalize().toString(), receipt.get("callFlowFile").getAsString());
            assertEquals(2, receipt.get("nodeCount").getAsInt());
            assertEquals(1, receipt.get("edgeCount").getAsInt());
            assertEquals("entry", receipt.get("entry").getAsString());
            assertEquals(1, receiveCount.get());
            assertEquals(sourceJson, receivedJson.get());
            assertEquals(sourceJson, Files.readString(stored));
            assertFalse(Files.exists(finalRequest));
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void registeredReceiverDirectlyHandlesARequestWithoutProjectMetadata() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        Path stored = temporaryDirectory.resolve("call-flow.json");
        AtomicInteger receiveCount = new AtomicInteger();
        AtomicReference<String> receivedJson = new AtomicReference<>();
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (CallFlowFileInboxService.Registration ignored = service.register(
                persistingReceiver(stored, receiveCount, receivedJson)
        )) {
            String requestId = "single-receiver";
            String sourceJson = requestJson(requestId, NOW - 1_000L, NOW + 60_000L);
            publish(service, requestId, sourceJson);

            service.scanOnce();
            awaitRegularFile(receiptPath(service, requestId));

            JsonObject receipt = readReceipt(service, requestId);
            assertEquals("accepted", receipt.get("status").getAsString());
            assertFalse(receipt.has("projectRoot"));
            assertEquals(1, receiveCount.get());
            assertEquals(sourceJson, receivedJson.get());
            assertEquals(sourceJson, Files.readString(stored));
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void acceptsPublishedRequestLargerThanTwoMiB() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        Path stored = temporaryDirectory.resolve("large-call-flow.json");
        AtomicReference<String> receivedJson = new AtomicReference<>();
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (CallFlowFileInboxService.Registration ignored = service.register(
                persistingReceiver(stored, new AtomicInteger(), receivedJson)
        )) {
            String requestId = "large-request";
            String sourceJson = " ".repeat(2 * 1024 * 1024 + 1)
                    + requestJson(requestId, NOW - 1_000L, NOW + 60_000L);
            publish(service, requestId, sourceJson);

            service.scanOnce();
            awaitRegularFile(receiptPath(service, requestId));

            assertEquals("accepted", readReceipt(service, requestId).get("status").getAsString());
            assertEquals(sourceJson, receivedJson.get());
            assertEquals(sourceJson, Files.readString(stored));
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void rejectsARequestWhenMultipleReceiversAreRegistered() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (CallFlowFileInboxService.Registration ignoredFirst = service.register(
                persistingReceiver(
                        temporaryDirectory.resolve("must-not-store-first.json"),
                        firstCount,
                        new AtomicReference<>()
                )
        ); CallFlowFileInboxService.Registration ignoredSecond = service.register(
                persistingReceiver(
                        temporaryDirectory.resolve("must-not-store-second.json"),
                        secondCount,
                        new AtomicReference<>()
                )
        )) {
            String requestId = "ambiguous-receiver";
            publish(service, requestId, requestJson(requestId, NOW - 1_000L, NOW + 60_000L));

            service.scanOnce();
            assertRejected(service, requestId, "AMBIGUOUS_PROJECT");

            assertEquals(0, firstCount.get());
            assertEquals(0, secondCount.get());
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void rejectsInvalidExpiredAndMismatchedRequestsWithV2Receipts() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (CallFlowFileInboxService.Registration ignored = service.register(
                persistingReceiver(
                        temporaryDirectory.resolve("never-used.json"),
                        new AtomicInteger(),
                        new AtomicReference<>()
                )
        )) {
            publish(service, "invalid-json", "{");
            service.scanOnce();
            assertRejected(service, "invalid-json", "INVALID_DELIVERY");

            String expiredId = "expired-1";
            publish(service, expiredId, requestJson(expiredId, NOW - 2_000L, NOW - 1_000L));
            service.scanOnce();
            assertRejected(service, expiredId, "REQUEST_EXPIRED");

            String fileId = "filename-id";
            publish(service, fileId, requestJson("document-id", NOW - 1_000L, NOW + 60_000L));
            service.scanOnce();
            assertRejected(service, fileId, "REQUEST_ID_MISMATCH");

            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void reportsReceiverFailuresAndAllowsAReplacementRegistration() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (CallFlowFileInboxService.Registration registration = service.register(
                (flow, sourceJson) -> CompletableFuture.failedFuture(new IOException("cannot store"))
        )) {
            String requestId = "load-failure";
            publish(service, requestId, requestJson(requestId, NOW - 1_000L, NOW + 60_000L));
            service.scanOnce();
            assertRejected(service, requestId, "LOAD_FAILED");
            assertProcessingEmpty(service);

            registration.close();
            try (CallFlowFileInboxService.Registration ignored = service.register(
                    persistingReceiver(
                            temporaryDirectory.resolve("after-close.json"),
                            new AtomicInteger(),
                            new AtomicReference<>()
                    )
            )) {
                assertTrue(service.inboxDirectory().startsWith(configuredTempRoot.toRealPath()));
            }
        } finally {
            service.dispose();
        }

        assertThrows(
                IOException.class,
                () -> service.register((flow, sourceJson) -> CompletableFuture.completedFuture(configuredTempRoot))
        );
    }

    @Test
    void consumerLockBlocksASecondServiceUntilTheFirstIsDisposed() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        Path stored = temporaryDirectory.resolve("taken-over.json");
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        CompletableFuture<Path> unfinishedLoad = new CompletableFuture<>();
        CallFlowFileInboxService first = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);
        CallFlowFileInboxService second = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);
        CallFlowFileInboxService.Registration firstRegistration = first.register((flow, sourceJson) -> {
            firstCount.incrementAndGet();
            return unfinishedLoad;
        });
        CallFlowFileReceiver secondReceiver = persistingReceiver(
                stored,
                secondCount,
                new AtomicReference<>()
        );

        try {
            IOException lockError = assertThrows(IOException.class, () -> second.register(secondReceiver));
            assertTrue(lockError.getMessage().contains("Another Android Studio process"));
            assertTrue(Files.isRegularFile(protocolRoot(configuredTempRoot).resolve(".consumer.lock")));

            String requestId = "lock-takeover";
            String sourceJson = requestJson(requestId, NOW - 1_000L, NOW + 60_000L);
            Path pending = publish(first, requestId, sourceJson);

            second.scanOnce();
            assertEquals(0, secondCount.get());
            first.scanOnce();
            assertEquals(1, firstCount.get());
            assertFalse(Files.exists(receiptPath(first, requestId)));

            first.dispose();
            firstRegistration.close();
            assertTrue(Files.isRegularFile(pending));

            try (CallFlowFileInboxService.Registration ignored = second.register(secondReceiver)) {
                second.scanOnce();
                awaitRegularFile(receiptPath(second, requestId));
                assertEquals("accepted", readReceipt(second, requestId).get("status").getAsString());
                assertEquals(1, secondCount.get());
                assertEquals(sourceJson, Files.readString(stored));
                assertProcessingEmpty(second);
            }
        } finally {
            second.dispose();
            first.dispose();
            firstRegistration.close();
        }
    }

    @Test
    void recoversAStaleClaimLeftByAnAbnormallyTerminatedIde() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        Path stored = temporaryDirectory.resolve("recovered-after-crash.json");
        String requestId = "crash-recovery";
        String sourceJson = requestJson(requestId, NOW - 1_000L, NOW + 60_000L);
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try (CallFlowFileInboxService.Registration ignored = service.register(
                persistingReceiver(stored, new AtomicInteger(), new AtomicReference<>())
        )) {
            Path claimed = service.inboxDirectory().getParent()
                    .resolve("processing")
                    .resolve("request-" + requestId + ".json.claim-crashed_process");
            Files.writeString(claimed, sourceJson);
            Files.setLastModifiedTime(
                    claimed,
                    FileTime.fromMillis(NOW - 11L * 60L * 1_000L)
            );

            service.scanOnce();
            awaitRegularFile(receiptPath(service, requestId));

            assertEquals("accepted", readReceipt(service, requestId).get("status").getAsString());
            assertEquals(sourceJson, Files.readString(stored));
            assertProcessingEmpty(service);
        } finally {
            service.dispose();
        }
    }

    @Test
    void failsWhenTheFixedMailboxRootIsAnOrdinaryFileWithoutRandomFallback() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        Path channelRoot = configuredTempRoot.resolve(CallFlowFileInboxService.CHANNEL_DIRECTORY);
        Files.writeString(channelRoot, "occupied");
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try {
            assertThrows(
                    IOException.class,
                    () -> service.register((flow, sourceJson) -> CompletableFuture.completedFuture(channelRoot))
            );
            assertTrue(Files.isRegularFile(channelRoot, LinkOption.NOFOLLOW_LINKS));
            assertNoRandomFallback(configuredTempRoot);
        } finally {
            service.dispose();
        }
    }

    @Test
    void failsWhenTheFixedMailboxRootIsASymbolicLinkWithoutRandomFallback() throws Exception {
        Path configuredTempRoot = Files.createDirectory(temporaryDirectory.resolve("system-temp"));
        Path linkTarget = Files.createDirectory(temporaryDirectory.resolve("link-target"));
        Path channelRoot = configuredTempRoot.resolve(CallFlowFileInboxService.CHANNEL_DIRECTORY);
        Files.createSymbolicLink(channelRoot, linkTarget);
        CallFlowFileInboxService service = new CallFlowFileInboxService(FIXED_CLOCK, configuredTempRoot);

        try {
            assertThrows(
                    IOException.class,
                    () -> service.register((flow, sourceJson) -> CompletableFuture.completedFuture(linkTarget))
            );
            assertTrue(Files.isSymbolicLink(channelRoot));
            assertNoRandomFallback(configuredTempRoot);
        } finally {
            service.dispose();
        }
    }

    private static CallFlowFileReceiver persistingReceiver(
            Path stored,
            AtomicInteger receiveCount,
            AtomicReference<String> receivedJson
    ) {
        return (flow, sourceJson) -> {
            receiveCount.incrementAndGet();
            receivedJson.set(sourceJson);
            try {
                Files.writeString(stored, sourceJson);
                return CompletableFuture.completedFuture(stored);
            } catch (IOException error) {
                return CompletableFuture.failedFuture(error);
            }
        };
    }

    private static Path publish(
            CallFlowFileInboxService service,
            String requestId,
            String sourceJson
    ) throws IOException {
        Path temporary = service.inboxDirectory().resolve(".request-" + requestId + ".tmp");
        Path target = service.inboxDirectory().resolve("request-" + requestId + ".json");
        Files.writeString(temporary, sourceJson);
        return Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void assertRejected(
            CallFlowFileInboxService service,
            String requestId,
            String expectedCode
    ) throws Exception {
        awaitRegularFile(receiptPath(service, requestId));
        JsonObject receipt = readReceipt(service, requestId);
        assertEquals("2.0", receipt.get("version").getAsString());
        assertEquals("rejected", receipt.get("status").getAsString());
        assertEquals(requestId, receipt.get("requestId").getAsString());
        assertEquals(expectedCode, receipt.getAsJsonObject("error").get("code").getAsString());
        assertFalse(receipt.getAsJsonObject("error").get("message").getAsString().isBlank());
    }

    private static JsonObject readReceipt(
            CallFlowFileInboxService service,
            String requestId
    ) throws IOException {
        return JsonParser.parseString(Files.readString(receiptPath(service, requestId)))
                .getAsJsonObject();
    }

    private static Path receiptPath(CallFlowFileInboxService service, String requestId) {
        return service.receiptsDirectory().resolve("receipt-" + requestId + ".json");
    }

    private static Path protocolRoot(Path temporaryRoot) {
        return temporaryRoot
                .resolve(CallFlowFileInboxService.CHANNEL_DIRECTORY)
                .resolve("file-ipc-v2");
    }

    private static void assertNoRandomFallback(Path temporaryRoot) throws IOException {
        try (var paths = Files.list(temporaryRoot)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(
                    CallFlowFileInboxService.CHANNEL_DIRECTORY + "-"
            )));
        }
    }

    private static void assertProcessingEmpty(CallFlowFileInboxService service) throws IOException {
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

    private static String requestJson(
            String requestId,
            long createdAtEpochMs,
            long expiresAtEpochMs
    ) {
        return """
                {
                  "_delivery":{"version":"2.0","requestId":%s,"createdAtEpochMs":%d,"expiresAtEpochMs":%d},
                  "version":"1.0",
                  "title":"MainActivity.onCreate",
                  "nodes":[
                    {"id":"entry","kind":"entry","location":{"path":"app/src/main/java/com/example/MainActivity.kt","line":10,"column":5},"summary":"Enter onCreate"},
                    {"id":"call","kind":"call","location":{"path":"app/src/main/java/com/example/MainActivity.kt","line":11,"column":9},"summary":"Call setContentView"}
                  ],
                  "edges":[{"from":"entry","to":"call","kind":"step_into"}],
                  "entry":"entry"
                }
                """.formatted(
                GSON.toJson(requestId),
                createdAtEpochMs,
                expiresAtEpochMs
        );
    }
}
