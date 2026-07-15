package com.youngx.aicallflow;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CallFlowFileInboxService implements Disposable {
    static final int MAX_RECEIPTS = 100;
    static final String CHANNEL_DIRECTORY = "youngx-ai-call-flow-navigator";

    private static final Logger LOG = Logger.getInstance(CallFlowFileInboxService.class);
    private static final String FILE_PROTOCOL_DIRECTORY = "file-ipc-v2";
    private static final long SCAN_INTERVAL_MILLIS = 500L;
    private static final long STALE_CLAIM_MILLIS = TimeUnit.MINUTES.toMillis(10L);

    private final Set<RegisteredTarget> targets = ConcurrentHashMap.newKeySet();
    private final Set<Path> inFlightClaims = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final Object scanLock = new Object();
    private final Clock clock;
    private final Path configuredTemporaryRoot;
    private volatile boolean disposed;
    private ScheduledExecutorService executor;
    private Path inboxDirectory;
    private Path processingDirectory;
    private Path receiptsDirectory;
    private UserPrincipal expectedOwner;
    private FileChannel consumerLockChannel;
    private FileLock consumerLock;

    public CallFlowFileInboxService() {
        this(Clock.systemUTC(), null);
    }

    CallFlowFileInboxService(Clock clock) {
        this(clock, null);
    }

    CallFlowFileInboxService(Clock clock, Path temporaryRoot) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configuredTemporaryRoot = temporaryRoot;
    }

    public static CallFlowFileInboxService getInstance() {
        return ApplicationManager.getApplication().getService(CallFlowFileInboxService.class);
    }

    Registration register(CallFlowFileReceiver receiver) throws IOException {
        Objects.requireNonNull(receiver, "receiver");
        RegisteredTarget target = new RegisteredTarget(receiver);

        synchronized (lifecycleLock) {
            if (disposed) {
                throw new IOException("Call Flow file inbox is disposed");
            }
            ensureStarted();
            targets.add(target);
        }
        requestScan();
        return new Registration(this, target);
    }

    String statusText() {
        Path inbox = inboxDirectory;
        return inbox == null
                ? "Preparing local JSON inbox"
                : "File inbox ready · waiting for AI";
    }

    Path inboxDirectory() {
        return inboxDirectory;
    }

    Path receiptsDirectory() {
        return receiptsDirectory;
    }

    void scanOnce() {
        synchronized (scanLock) {
            if (disposed
                    || inboxDirectory == null
                    || consumerLock == null
                    || !consumerLock.isValid()) {
                return;
            }
            refreshInFlightClaimLeases();
            recoverStaleClaims();
            try (var paths = Files.list(inboxDirectory)) {
                paths.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(this::processCandidateSafely);
            } catch (IOException | SecurityException error) {
                LOG.warn("Cannot scan AI Call Flow file inbox", error);
            }
        }
    }

    @Override
    public void dispose() {
        ScheduledExecutorService activeExecutor;
        synchronized (lifecycleLock) {
            if (disposed) {
                return;
            }
            disposed = true;
            targets.clear();
            activeExecutor = executor;
            executor = null;
        }
        if (activeExecutor != null) {
            activeExecutor.shutdownNow();
        }
        synchronized (scanLock) {
            for (Path claimed : Set.copyOf(inFlightClaims)) {
                restoreClaimSafely(claimed);
            }
            inFlightClaims.clear();
            releaseConsumerLock();
        }
    }

    private void ensureStarted() throws IOException {
        if (executor != null) {
            return;
        }

        Path temporaryRoot;
        if (configuredTemporaryRoot != null) {
            temporaryRoot = configuredTemporaryRoot.toRealPath();
        } else {
            String temporaryValue = System.getProperty("java.io.tmpdir");
            if (temporaryValue == null || temporaryValue.isBlank()) {
                throw new IllegalStateException("java.io.tmpdir is not configured");
            }
            temporaryRoot = Path.of(temporaryValue).toRealPath();
        }
        SecureFileSupport.ProcessIdentity identity = SecureFileSupport.currentProcessIdentity(temporaryRoot);
        expectedOwner = identity.owner();

        Path channelRoot = temporaryRoot.resolve(CHANNEL_DIRECTORY);
        SecureFileSupport.createPrivateDirectory(channelRoot, expectedOwner);
        Path protocolRoot = channelRoot.resolve(FILE_PROTOCOL_DIRECTORY);
        SecureFileSupport.createPrivateDirectory(protocolRoot, expectedOwner);
        inboxDirectory = protocolRoot.resolve("inbox");
        processingDirectory = protocolRoot.resolve("processing");
        receiptsDirectory = protocolRoot.resolve("receipts");
        SecureFileSupport.createPrivateDirectory(inboxDirectory, expectedOwner);
        SecureFileSupport.createPrivateDirectory(processingDirectory, expectedOwner);
        SecureFileSupport.createPrivateDirectory(receiptsDirectory, expectedOwner);
        acquireConsumerLock(protocolRoot);

        try {
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "AI Call Flow file inbox");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(
                    this::scanOnce,
                    0L,
                    SCAN_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            executor = null;
            releaseConsumerLock();
            throw error;
        }
    }

    private void acquireConsumerLock(Path protocolRoot) throws IOException {
        Path lockPath = protocolRoot.resolve(".consumer.lock");
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        );
        boolean acquired = false;
        try {
            SecureFileSupport.secureRegularFile(lockPath, expectedOwner);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException error) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException("Another Android Studio process is already using the Call Flow inbox");
            }
            consumerLockChannel = channel;
            consumerLock = lock;
            acquired = true;
        } finally {
            if (!acquired) {
                channel.close();
            }
        }
    }

    private void releaseConsumerLock() {
        FileLock lock = consumerLock;
        FileChannel channel = consumerLockChannel;
        consumerLock = null;
        consumerLockChannel = null;
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException error) {
            LOG.warn("Cannot release the Call Flow inbox lock", error);
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException error) {
                    LOG.warn("Cannot close the Call Flow inbox lock", error);
                }
            }
        }
    }

    private void requestScan() {
        ScheduledExecutorService activeExecutor = executor;
        if (activeExecutor == null || disposed) {
            return;
        }
        try {
            activeExecutor.execute(this::scanOnce);
        } catch (RejectedExecutionException ignored) {
            // Disposal raced with registration or completion.
        }
    }

    private void processCandidateSafely(Path requestPath) {
        String requestId = CallFlowDeliveryParser.requestIdFromFileName(requestPath);
        if (requestId == null) {
            return;
        }
        try {
            processCandidate(requestPath, requestId);
        } catch (Exception error) {
            LOG.warn("Cannot process AI Call Flow request " + requestPath, error);
        }
    }

    private void processCandidate(Path requestPath, String requestId) throws IOException {
        Path receipt = receiptPath(requestId);
        if (Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
            removeDuplicateRequest(requestPath);
            return;
        }

        String sourceJson;
        CallFlowDeliveryParser.Delivery delivery;
        try {
            sourceJson = readUtf8Request(requestPath);
            delivery = CallFlowDeliveryParser.parseDelivery(sourceJson);
        } catch (IllegalArgumentException | IOException error) {
            rejectClaimedRequest(requestPath, requestId, "INVALID_DELIVERY", safeMessage(error));
            return;
        }

        if (!requestId.equals(delivery.requestId())) {
            rejectClaimedRequest(
                    requestPath,
                    requestId,
                    "REQUEST_ID_MISMATCH",
                    "Request filename and _delivery.requestId do not match"
            );
            return;
        }
        if (clock.millis() > delivery.expiresAtEpochMs()) {
            rejectClaimedRequest(requestPath, requestId, "REQUEST_EXPIRED", "Call Flow request expired");
            return;
        }

        List<RegisteredTarget> candidates = List.copyOf(targets);
        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() != 1) {
            rejectClaimedRequest(
                    requestPath,
                    requestId,
                    "AMBIGUOUS_PROJECT",
                    "More than one Android Studio project is open; close extra projects before sending a Call Flow"
            );
            return;
        }
        RegisteredTarget target = candidates.getFirst();

        Path claimed;
        try {
            claimed = SecureFileSupport.claimAtomically(
                    requestPath,
                    processingDirectory,
                    expectedOwner
            );
            if (!markClaimed(claimed)) {
                restoreClaimSafely(claimed);
                return;
            }
        } catch (IOException error) {
            return;
        }
        if (disposed || !targets.contains(target)) {
            restoreClaimSafely(claimed);
            return;
        }

        CallFlowDeliveryParser.Request request;
        try {
            String claimedJson = readUtf8Request(claimed);
            request = CallFlowDeliveryParser.parse(claimedJson);
            if (!requestId.equals(request.delivery().requestId())) {
                throw new IllegalArgumentException("Request ID changed while claiming the file");
            }
            if (clock.millis() > request.delivery().expiresAtEpochMs()) {
                throw new IllegalArgumentException("Call Flow request expired");
            }
        } catch (Exception error) {
            finishRejected(claimed, requestId, "INVALID_CALL_FLOW", safeMessage(error));
            return;
        }
        if (!isOnlyTarget(target)) {
            finishRejected(
                    claimed,
                    requestId,
                    "AMBIGUOUS_PROJECT",
                    "More than one Android Studio project is open; close extra projects before sending a Call Flow"
            );
            return;
        }

        try {
            target.receiver().receive(request.callFlow(), request.sourceJson())
                    .whenComplete((storedPath, error) -> scheduleCompletion(() -> {
                        if (error == null) {
                            finishAccepted(claimed, request, storedPath);
                        } else {
                            finishRejected(
                                    claimed,
                                    requestId,
                                    "LOAD_FAILED",
                                    safeMessage(unwrap(error))
                            );
                        }
                    }));
        } catch (RuntimeException error) {
            finishRejected(claimed, requestId, "LOAD_FAILED", safeMessage(error));
        }
    }

    private boolean isOnlyTarget(RegisteredTarget expected) {
        return targets.size() == 1 && targets.contains(expected);
    }

    private void rejectClaimedRequest(
            Path requestPath,
            String requestId,
            String code,
            String message
    ) throws IOException {
        Path claimed;
        try {
            claimed = SecureFileSupport.claimAtomically(
                    requestPath,
                    processingDirectory,
                    expectedOwner
            );
            if (!markClaimed(claimed)) {
                restoreClaimSafely(claimed);
                return;
            }
        } catch (IOException error) {
            return;
        }
        finishRejected(claimed, requestId, code, message);
    }

    private void removeDuplicateRequest(Path requestPath) throws IOException {
        try {
            Path claimed = SecureFileSupport.claimAtomically(
                    requestPath,
                    processingDirectory,
                    expectedOwner
            );
            Files.deleteIfExists(claimed);
        } catch (IOException ignored) {
            // Another IDE process may already be handling the duplicate.
        }
    }

    private void finishAccepted(
            Path claimed,
            CallFlowDeliveryParser.Request request,
            Path storedPath
    ) {
        String requestId = request.delivery().requestId();
        try {
            if (storedPath == null || !Files.isRegularFile(storedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Call Flow JSON was not persisted as a regular file");
            }
            JsonObject receipt = baseReceipt(request.delivery(), "accepted");
            receipt.addProperty("callFlowFile", storedPath.toAbsolutePath().normalize().toString());
            receipt.addProperty("nodeCount", request.callFlow().nodes().size());
            receipt.addProperty("edgeCount", request.callFlow().edges().size());
            receipt.addProperty("entry", request.callFlow().entry());
            writeReceipt(requestId, receipt);
            Files.deleteIfExists(claimed);
        } catch (Exception error) {
            LOG.warn("Cannot finish accepted Call Flow request " + requestId, error);
        } finally {
            inFlightClaims.remove(claimed);
        }
    }

    private void finishRejected(Path claimed, String requestId, String code, String message) {
        try {
            JsonObject receipt = new JsonObject();
            receipt.addProperty("version", CallFlowDeliveryParser.DELIVERY_VERSION);
            receipt.addProperty("requestId", requestId);
            receipt.addProperty("status", "rejected");
            receipt.addProperty("completedAtEpochMs", clock.millis());
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", message);
            receipt.add("error", error);
            writeReceipt(requestId, receipt);
            Files.deleteIfExists(claimed);
        } catch (Exception receiptError) {
            LOG.warn("Cannot reject Call Flow request " + requestId, receiptError);
        } finally {
            inFlightClaims.remove(claimed);
        }
    }

    private JsonObject baseReceipt(CallFlowDeliveryParser.Delivery delivery, String status) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("version", CallFlowDeliveryParser.DELIVERY_VERSION);
        receipt.addProperty("requestId", delivery.requestId());
        receipt.addProperty("status", status);
        receipt.addProperty("completedAtEpochMs", clock.millis());
        return receipt;
    }

    private void writeReceipt(String requestId, JsonObject receipt) throws IOException {
        Path receiptPath = receiptPath(requestId);
        try {
            SecureFileSupport.writeAtomically(receiptPath, receipt.toString(), expectedOwner);
        } catch (FileAlreadyExistsException duplicate) {
            // A published receipt is immutable and is the idempotent result for this request ID.
            SecureFileSupport.secureRegularFile(receiptPath, expectedOwner);
        }
        pruneReceipts();
    }

    private Path receiptPath(String requestId) {
        return receiptsDirectory.resolve("receipt-" + requestId + ".json");
    }

    private String readUtf8Request(Path path) throws IOException {
        SecureFileSupport.secureRegularFile(path, expectedOwner);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            bytes = input.readAllBytes();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Call Flow request must be valid UTF-8", error);
        }
    }

    private void scheduleCompletion(Runnable completion) {
        ScheduledExecutorService activeExecutor = executor;
        if (activeExecutor == null || disposed) {
            return;
        }
        try {
            activeExecutor.execute(() -> {
                synchronized (scanLock) {
                    if (!disposed) {
                        completion.run();
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Disposal won the race; generation guards prevent a stale UI load.
        }
    }

    private void pruneReceipts() {
        try (var paths = Files.list(receiptsDirectory)) {
            var receipts = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("receipt-") && name.endsWith(".json");
                    })
                    .sorted(Comparator.comparingLong(this::lastModifiedSafely).reversed())
                    .toList();
            for (int index = MAX_RECEIPTS; index < receipts.size(); index++) {
                try {
                    Files.deleteIfExists(receipts.get(index));
                } catch (IOException | SecurityException ignored) {
                    // Best-effort cleanup only.
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Receipt was already published successfully.
        }
    }

    private long lastModifiedSafely(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException error) {
            return Long.MIN_VALUE;
        }
    }

    private boolean markClaimed(Path claimed) {
        inFlightClaims.add(claimed);
        try {
            Files.setLastModifiedTime(claimed, FileTime.fromMillis(clock.millis()));
            return true;
        } catch (IOException | SecurityException error) {
            inFlightClaims.remove(claimed);
            LOG.warn("Cannot timestamp Call Flow processing claim " + claimed, error);
            return false;
        }
    }

    private void refreshInFlightClaimLeases() {
        long now = clock.millis();
        for (Path claimed : Set.copyOf(inFlightClaims)) {
            try {
                if (Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setLastModifiedTime(claimed, FileTime.fromMillis(now));
                }
            } catch (IOException | SecurityException error) {
                LOG.warn("Cannot refresh Call Flow processing lease " + claimed, error);
            }
        }
    }

    private void recoverStaleClaims() {
        long now = clock.millis();
        try (var paths = Files.list(processingDirectory)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !inFlightClaims.contains(path))
                    .filter(path -> CallFlowDeliveryParser.requestIdFromClaimFileName(path) != null)
                    .filter(path -> {
                        long modified = lastModifiedSafely(path);
                        return modified != Long.MIN_VALUE
                                && now >= modified
                                && now - modified >= STALE_CLAIM_MILLIS;
                    })
                    .forEach(this::restoreClaimSafely);
        } catch (IOException | SecurityException error) {
            LOG.warn("Cannot recover stale Call Flow processing claims", error);
        }
    }

    private void restoreClaimSafely(Path claimed) {
        try {
            String requestId = CallFlowDeliveryParser.requestIdFromClaimFileName(claimed);
            if (requestId == null || !Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path receipt = receiptPath(requestId);
            if (Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(claimed);
                return;
            }

            Path pending = inboxDirectory.resolve("request-" + requestId + ".json");
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                // A fresh retry with this ID wins over a stale, unacknowledged claim.
                Files.deleteIfExists(claimed);
                return;
            }
            try {
                SecureFileSupport.moveAtomicallyWithoutReplace(claimed, pending, expectedOwner);
            } catch (FileAlreadyExistsException duplicate) {
                Files.deleteIfExists(claimed);
            }
        } catch (IOException | SecurityException error) {
            LOG.warn("Cannot restore Call Flow processing claim " + claimed, error);
        } finally {
            inFlightClaims.remove(claimed);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() <= 1_000 ? message : message.substring(0, 1_000);
    }

    private record RegisteredTarget(CallFlowFileReceiver receiver) {
    }

    static final class Registration implements AutoCloseable {
        private final CallFlowFileInboxService service;
        private final RegisteredTarget target;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(
                CallFlowFileInboxService service,
                RegisteredTarget target
        ) {
            this.service = service;
            this.target = target;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                service.targets.remove(target);
            }
        }
    }
}
