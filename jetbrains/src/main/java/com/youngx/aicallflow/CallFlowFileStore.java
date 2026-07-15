package com.youngx.aicallflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class CallFlowFileStore implements AutoCloseable {
    static final int MAX_PERSISTED_CALL_FLOWS_PER_PROJECT = 50;

    private static final String TEMPORARY_ROOT = "youngx-ai-call-flow-navigator-archive";
    private static final String CALL_FLOWS_DIRECTORY = "call-flows";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path callFlowDirectory;
    private final UserPrincipal expectedOwner;
    private boolean closed;

    private CallFlowFileStore(Path callFlowDirectory, UserPrincipal expectedOwner) {
        this.callFlowDirectory = callFlowDirectory;
        this.expectedOwner = expectedOwner;
    }

    static CallFlowFileStore create(Path canonicalProjectRoot) throws IOException {
        String temporary = System.getProperty("java.io.tmpdir");
        if (temporary == null || temporary.isBlank()) {
            throw new IllegalStateException("java.io.tmpdir is not configured");
        }
        return createIn(Path.of(temporary), canonicalProjectRoot.toString());
    }

    static CallFlowFileStore createIn(
            Path systemTemporaryDirectory,
            String canonicalProjectRoot
    ) throws IOException {
        Objects.requireNonNull(systemTemporaryDirectory, "systemTemporaryDirectory");
        if (canonicalProjectRoot == null || canonicalProjectRoot.isBlank()) {
            throw new IllegalArgumentException("canonicalProjectRoot is required");
        }

        Path trustedTemporaryDirectory = systemTemporaryDirectory.toRealPath();
        SecureFileSupport.ProcessIdentity identity =
                SecureFileSupport.currentProcessIdentity(trustedTemporaryDirectory);
        Path temporaryRoot = SecureFileSupport.createPrivateRoot(
                trustedTemporaryDirectory,
                TEMPORARY_ROOT,
                identity
        );
        Path allCallFlows = temporaryRoot.resolve(CALL_FLOWS_DIRECTORY);
        Path projectCallFlows = allCallFlows.resolve(
                SecureFileSupport.projectDirectoryName(canonicalProjectRoot)
        );
        SecureFileSupport.createPrivateDirectory(allCallFlows, identity.owner());
        SecureFileSupport.createPrivateDirectory(projectCallFlows, identity.owner());
        return new CallFlowFileStore(projectCallFlows, identity.owner());
    }

    synchronized Path persist(String sourceJson) throws IOException {
        Objects.requireNonNull(sourceJson, "sourceJson");
        if (closed) {
            throw new IllegalStateException("Call Flow file store is closed");
        }
        if (sourceJson.isBlank()) {
            throw new IllegalArgumentException("Call Flow JSON must not be blank");
        }

        Path target = callFlowDirectory.resolve(
                "call-flow-"
                        + System.currentTimeMillis()
                        + "-"
                        + randomUrlSafeString(12)
                        + ".json"
        );
        SecureFileSupport.writeAtomically(target, sourceJson, expectedOwner);
        pruneOldCallFlows(target);
        return target;
    }

    Path directory() {
        return callFlowDirectory;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private void pruneOldCallFlows(Path newest) {
        try (var paths = Files.list(callFlowDirectory)) {
            List<Path> older = paths
                    .filter(path -> !path.equals(newest))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("call-flow-") && name.endsWith(".json");
                    })
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            int excess = Math.max(
                    0,
                    older.size() - (MAX_PERSISTED_CALL_FLOWS_PER_PROJECT - 1)
            );
            for (int index = 0; index < excess; index++) {
                try {
                    Files.deleteIfExists(older.get(index));
                } catch (IOException | SecurityException ignored) {
                    // Retention is best effort after the new Call Flow is safely stored.
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Storage succeeded; cleanup must not turn an accepted request into a failure.
        }
    }

    private static String randomUrlSafeString(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
