package com.youngx.aicallflow;

import java.nio.file.Path;

final class ProjectPathResolver {
    private ProjectPathResolver() {
    }

    static Path resolveInsideRoot(Path projectRoot, String relativePath) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path contains an invalid character");
        }
        if (isAnyPlatformAbsolute(relativePath)) {
            throw new IllegalArgumentException("Only relative paths are allowed");
        }

        Path root = projectRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath.replace('\\', '/')).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes project root");
        }
        return resolved;
    }

    private static boolean isAnyPlatformAbsolute(String value) {
        return Path.of(value).isAbsolute()
                || value.startsWith("/")
                || value.startsWith("\\\\")
                || value.matches("^[A-Za-z]:[\\\\/].*");
    }
}
