package cn.mythicland.framework.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared artifact hashing and atomic download for both runtime loading entry points.
 */
final class RuntimeArtifacts {
    private RuntimeArtifacts() {
    }

    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             DigestInputStream digest = new DigestInputStream(input, digest())) {
            digest.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        }
    }

    static void download(URI source, Path target, String expectedHash, String userAgent) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        try {
            URLConnection connection = source.toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", userAgent);
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, temporary);
            }
            if (!expectedHash.equalsIgnoreCase(sha256(temporary))) {
                throw new IOException("SHA-256 mismatch for " + target.getFileName());
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 implementation is unavailable", exception);
        }
    }
}
