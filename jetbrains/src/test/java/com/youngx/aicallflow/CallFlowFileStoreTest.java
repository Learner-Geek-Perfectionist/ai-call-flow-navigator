package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class CallFlowFileStoreTest {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsTheExactJsonInAProjectSpecificTemporaryDirectory() throws Exception {
        String sourceJson = " {\n  \"version\": \"1.0\"\n}\n";

        try (CallFlowFileStore store = CallFlowFileStore.createIn(
                temporaryDirectory,
                "/workspace/first-project"
        )) {
            Path stored = store.persist(sourceJson);

            assertEquals(sourceJson, Files.readString(stored));
            assertEquals(store.directory(), stored.getParent());
            assertTrue(stored.getFileName().toString().startsWith("call-flow-"));
            assertTrue(stored.getFileName().toString().endsWith(".json"));
            assertTrue(store.directory().getFileName().toString().startsWith("project-"));
            try (var paths = Files.list(store.directory())) {
                assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        }
    }

    @Test
    void isolatesFilesForDifferentCanonicalProjectRoots() throws Exception {
        try (CallFlowFileStore first = CallFlowFileStore.createIn(
                temporaryDirectory,
                "/workspace/first-project"
        ); CallFlowFileStore second = CallFlowFileStore.createIn(
                temporaryDirectory,
                "/workspace/second-project"
        )) {
            Path firstFile = first.persist("{\"project\":\"first\"}");
            Path secondFile = second.persist("{\"project\":\"second\"}");

            assertNotEquals(first.directory(), second.directory());
            assertEquals(first.directory(), firstFile.getParent());
            assertEquals(second.directory(), secondFile.getParent());
            assertEquals("{\"project\":\"first\"}", Files.readString(firstFile));
            assertEquals("{\"project\":\"second\"}", Files.readString(secondFile));
        }
    }

    @Test
    void retainsOnlyTheNewestConfiguredNumberOfCallFlows() throws Exception {
        try (CallFlowFileStore store = CallFlowFileStore.createIn(
                temporaryDirectory,
                "/workspace/project"
        )) {
            Path newest = null;
            int writes = CallFlowFileStore.MAX_PERSISTED_CALL_FLOWS_PER_PROJECT + 7;
            for (int index = 0; index < writes; index++) {
                newest = store.persist("{\"write\":" + index + "}");
            }

            assertTrue(Files.isRegularFile(newest));
            assertEquals("{\"write\":" + (writes - 1) + "}", Files.readString(newest));
            try (var paths = Files.list(store.directory())) {
                assertEquals(
                        CallFlowFileStore.MAX_PERSISTED_CALL_FLOWS_PER_PROJECT,
                        paths.filter(Files::isRegularFile)
                                .filter(path -> {
                                    String name = path.getFileName().toString();
                                    return name.startsWith("call-flow-") && name.endsWith(".json");
                                })
                                .count()
                );
            }
        }
    }

    @Test
    void usesPrivatePosixPermissionsWhenTheFileSystemSupportsThem() throws Exception {
        try (CallFlowFileStore store = CallFlowFileStore.createIn(
                temporaryDirectory,
                "/workspace/project"
        )) {
            Path stored = store.persist("{\"version\":\"1.0\"}");
            if (Files.getFileAttributeView(store.directory(), PosixFileAttributeView.class) != null) {
                assertEquals(DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(store.directory()));
                assertEquals(FILE_PERMISSIONS, Files.getPosixFilePermissions(stored));
            }
        }
    }

    @Test
    void usesAnUnpredictableUidFallbackWhenTheSharedTemporaryNameIsUnsafe() throws Exception {
        SecureFileSupport.ProcessIdentity identity =
                SecureFileSupport.currentProcessIdentity(temporaryDirectory);
        assumeTrue(identity.unixUid() != null);
        Files.createFile(temporaryDirectory.resolve("youngx-ai-call-flow-navigator-archive"));

        try (CallFlowFileStore store = CallFlowFileStore.createIn(
                temporaryDirectory,
                "/workspace/project"
        )) {
            Path relative = temporaryDirectory.toRealPath().relativize(store.directory());
            assertTrue(relative.getName(0).toString().startsWith(
                    "youngx-ai-call-flow-navigator-archive-user-" + identity.unixUid() + "-"
            ));
            assertTrue(Files.isRegularFile(store.persist("{\"version\":\"1.0\"}")));
        }
    }

    @Test
    void atomicPublicationNeverOverwritesAnExistingResult() throws Exception {
        SecureFileSupport.ProcessIdentity identity =
                SecureFileSupport.currentProcessIdentity(temporaryDirectory);
        Path target = temporaryDirectory.resolve("receipt.json");

        SecureFileSupport.writeAtomically(target, "first", identity.owner());
        assertThrows(
                FileAlreadyExistsException.class,
                () -> SecureFileSupport.writeAtomically(target, "second", identity.owner())
        );

        assertEquals("first", Files.readString(target));
    }

    @Test
    void rejectsBlankJsonAndWritesAfterClose() throws Exception {
        CallFlowFileStore store = CallFlowFileStore.createIn(
                temporaryDirectory,
                "/workspace/project"
        );

        assertThrows(IllegalArgumentException.class, () -> store.persist("  "));
        store.close();
        assertThrows(IllegalStateException.class, () -> store.persist("{}"));
        assertTrue(Files.isDirectory(store.directory()));
    }
}
