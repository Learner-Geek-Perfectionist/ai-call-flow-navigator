package com.youngx.aicallflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProjectPathResolverTest {
    @TempDir
    Path root;

    @Test
    void resolvesRelativePathInsideRoot() {
        Path resolved = ProjectPathResolver.resolveInsideRoot(root, "app/src/main/AndroidManifest.xml");

        assertEquals(root.resolve("app/src/main/AndroidManifest.xml").normalize(), resolved);
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThrows(IllegalArgumentException.class, () ->
                ProjectPathResolver.resolveInsideRoot(root, root.resolve("app/src/main/Foo.kt").toString())
        );
        assertThrows(IllegalArgumentException.class, () ->
                ProjectPathResolver.resolveInsideRoot(root, "C:\\src\\Foo.kt")
        );
    }

    @Test
    void rejectsParentDirectoryTraversal() {
        assertThrows(IllegalArgumentException.class, () ->
                ProjectPathResolver.resolveInsideRoot(root, "../outside/Foo.kt")
        );
    }
}
