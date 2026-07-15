package com.youngx.aicallflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

final class SecureFileSupport {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Object ATOMIC_WRITE_LOCK = new Object();
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    record ProcessIdentity(UserPrincipal owner, Long unixUid) {
    }

    private static final class ForeignOwnerException extends IOException {
        private ForeignOwnerException(Path path) {
            super("Private path is owned by another user: " + path);
        }
    }

    private SecureFileSupport() {
    }

    static ProcessIdentity currentProcessIdentity(Path writableDirectory) throws IOException {
        Path probe = Files.createTempFile(writableDirectory, ".youngx-owner-", ".tmp");
        try {
            UserPrincipal owner;
            try {
                owner = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException error) {
                owner = null;
            }

            Long unixUid = null;
            try {
                Object value = Files.getAttribute(probe, "unix:uid", LinkOption.NOFOLLOW_LINKS);
                if (value instanceof Integer integer) {
                    unixUid = Integer.toUnsignedLong(integer);
                } else if (value instanceof Number number && number.longValue() >= 0) {
                    unixUid = number.longValue();
                }
            } catch (IOException | IllegalArgumentException | UnsupportedOperationException ignored) {
                // Windows and non-Unix file systems do not expose unix:uid.
            }
            return new ProcessIdentity(owner, unixUid);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    static Path createPrivateRoot(
            Path trustedParent,
            String directoryName,
            ProcessIdentity identity
    ) throws IOException {
        Path primary = trustedParent.resolve(directoryName);
        try {
            createPrivateDirectory(primary, identity.owner());
            return primary;
        } catch (IOException primaryError) {
            String userPart = identity.unixUid() == null
                    ? "user"
                    : "user-" + identity.unixUid();
            Path fallback = null;
            boolean ready = false;
            try {
                fallback = Files.createTempDirectory(
                        trustedParent,
                        directoryName + "-" + userPart + "-"
                );
                createPrivateDirectory(fallback, identity.owner());
                ready = true;
                return fallback;
            } catch (IOException fallbackError) {
                fallbackError.addSuppressed(primaryError);
                throw fallbackError;
            } finally {
                if (fallback != null && !ready) {
                    try {
                        Files.deleteIfExists(fallback);
                    } catch (IOException | SecurityException ignored) {
                        // The original initialization failure is more useful to the caller.
                    }
                }
            }
        }
    }

    static void createPrivateDirectory(Path directory, UserPrincipal expectedOwner) throws IOException {
        try {
            try {
                FileAttribute<Set<PosixFilePermission>> attribute =
                        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS);
                Files.createDirectory(directory, attribute);
            } catch (UnsupportedOperationException error) {
                Files.createDirectory(directory);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Another project or IDE process may have created the shared directory.
        }

        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isDirectory()) {
            throw new IOException("Invalid private directory: " + directory);
        }
        verifyOwner(directory, expectedOwner);
        setAndVerifyPosixPermissions(directory, DIRECTORY_PERMISSIONS);
    }

    static void secureRegularFile(Path path, UserPrincipal expectedOwner) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile()) {
            throw new IOException("Invalid private file: " + path);
        }
        verifyOwner(path, expectedOwner);
        setAndVerifyPosixPermissions(path, FILE_PERMISSIONS);
    }

    static void writeAtomically(
            Path target,
            String content,
            UserPrincipal expectedOwner
    ) throws IOException {
        Path temporary = createPrivateTemporaryFile(target.getParent(), expectedOwner);
        boolean moved = false;
        boolean published = false;
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
            secureRegularFile(temporary, expectedOwner);
            synchronized (ATOMIC_WRITE_LOCK) {
                Path lockPath = target.getParent().resolve(".youngx-publish.lock");
                try (FileChannel lockChannel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    secureRegularFile(lockPath, expectedOwner);
                    try (FileLock ignored = lockChannel.lock()) {
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            throw new FileAlreadyExistsException(target.toString());
                        }
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                        moved = true;
                        secureRegularFile(target, expectedOwner);
                        published = true;
                    }
                }
            }
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            } else if (!published) {
                Files.deleteIfExists(target);
            }
        }
    }

    static Path claimAtomically(
            Path source,
            Path processingDirectory,
            UserPrincipal expectedOwner
    ) throws IOException {
        secureRegularFile(source, expectedOwner);
        for (int attempt = 0; attempt < 10; attempt++) {
            Path target = processingDirectory.resolve(
                    source.getFileName() + ".claim-" + randomUrlSafeString(12)
            );
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                secureRegularFile(target, expectedOwner);
                return target;
            } catch (FileAlreadyExistsException collision) {
                // A random claim-name collision is extraordinarily unlikely; retry safely.
            }
        }
        throw new IOException("Cannot allocate a unique Call Flow processing claim");
    }

    static Path moveAtomicallyWithoutReplace(
            Path source,
            Path target,
            UserPrincipal expectedOwner
    ) throws IOException {
        secureRegularFile(source, expectedOwner);
        synchronized (ATOMIC_WRITE_LOCK) {
            Path lockPath = target.getParent().resolve(".youngx-publish.lock");
            try (FileChannel lockChannel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                secureRegularFile(lockPath, expectedOwner);
                try (FileLock ignored = lockChannel.lock()) {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new FileAlreadyExistsException(target.toString());
                    }
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    secureRegularFile(target, expectedOwner);
                    return target;
                }
            }
        }
    }

    static String projectDirectoryName(String canonicalProjectRoot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalProjectRoot.getBytes(StandardCharsets.UTF_8));
            return "project-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    static String randomUrlSafeString(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Path createPrivateTemporaryFile(
            Path directory,
            UserPrincipal expectedOwner
    ) throws IOException {
        Path temporary;
        try {
            FileAttribute<Set<PosixFilePermission>> attribute =
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS);
            temporary = Files.createTempFile(directory, ".youngx-", ".tmp", attribute);
        } catch (UnsupportedOperationException error) {
            temporary = Files.createTempFile(directory, ".youngx-", ".tmp");
        }
        secureRegularFile(temporary, expectedOwner);
        return temporary;
    }

    private static void verifyOwner(Path path, UserPrincipal expectedOwner) throws IOException {
        if (expectedOwner == null) {
            return;
        }
        UserPrincipal actualOwner;
        try {
            actualOwner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException error) {
            return;
        }
        if (!expectedOwner.equals(actualOwner)) {
            throw new ForeignOwnerException(path);
        }
    }

    private static void setAndVerifyPosixPermissions(
            Path path,
            Set<PosixFilePermission> permissions
    ) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            return;
        }
        view.setPermissions(permissions);
        if (!view.readAttributes().permissions().equals(permissions)) {
            throw new IOException("Cannot secure private path permissions: " + path);
        }
    }

}
