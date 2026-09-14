package cn.mythicland.framework.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeLibraryLoaderTest {
    private static final String MANIFEST = "META-INF/runtime-libraries.properties";

    @Test
    void shouldDownloadRepairReuseAndFilterLibraries(@TempDir Path temporaryDirectory) throws Exception {
        byte[] libraryBytes = "verified-library".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(libraryBytes, requests);
        List<String> infoMessages = new ArrayList<>();
        try (URLClassLoader resources = resources(
                temporaryDirectory,
                server,
                sha256(libraryBytes)
        )) {
            Path dataDirectory = temporaryDirectory.resolve("data");
            Path unknownFile = dataDirectory.resolve("libs/unknown.jar");
            Path inactiveManagedFile = dataDirectory.resolve("libs/other-1.0.jar");
            Files.createDirectories(unknownFile.getParent());
            Files.writeString(unknownFile, "unmanaged");
            Files.writeString(inactiveManagedFile, "obsolete");
            RuntimeLibraryLoader loader = new RuntimeLibraryLoader(
                    dataDirectory,
                    resources,
                    MANIFEST,
                    infoMessages::add,
                    ignored -> {
                    }
            );

            loader.resolveLibraries(Set.of("common"));
            Path library = dataDirectory.resolve("libs/example-1.0.jar");
            assertArrayEquals(libraryBytes, Files.readAllBytes(library));
            assertFalse(Files.exists(inactiveManagedFile));
            assertTrue(Files.exists(unknownFile));

            Files.writeString(library, "corrupt");
            loader.resolveLibraries(Set.of("common"));
            assertArrayEquals(libraryBytes, Files.readAllBytes(library));
            assertEquals(2, requests.get());

            server.stop(0);
            loader.resolveLibraries(Set.of("common"));
            assertEquals(2, requests.get());
            assertTrue(infoMessages.contains("Downloading dependency jar: example-1.0.jar"));
            assertTrue(infoMessages.contains("Loading dependency jar: example-1.0.jar"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectDownloadedLibraryWithUnexpectedHash(@TempDir Path temporaryDirectory) throws Exception {
        byte[] libraryBytes = "wrong-library".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(libraryBytes, new AtomicInteger());
        try (URLClassLoader resources = resources(
                temporaryDirectory,
                server,
                sha256("expected".getBytes(StandardCharsets.UTF_8))
        )) {
            RuntimeLibraryLoader loader = new RuntimeLibraryLoader(
                    temporaryDirectory.resolve("data"),
                    resources,
                    MANIFEST,
                    ignored -> {
                    },
                    ignored -> {
                    }
            );

            assertThrows(
                    IllegalStateException.class,
                    () -> loader.resolveLibraries(Set.of("common"))
            );
        } finally {
            server.stop(0);
        }
    }

    private URLClassLoader resources(
            Path temporaryDirectory,
            HttpServer server,
            String hash
    ) throws Exception {
        Path resources = temporaryDirectory.resolve("resources-" + hash);
        Path manifest = resources.resolve(MANIFEST);
        Files.createDirectories(manifest.getParent());
        Files.writeString(
                manifest,
                "library.count=2\n"
                        + "library.0.coordinate=test:example:1.0\n"
                        + "library.0.fileName=example-1.0.jar\n"
                        + "library.0.downloadUrls=http://127.0.0.1:"
                        + server.getAddress().getPort() + "/example.jar\n"
                        + "library.0.sha256=" + hash + "\n"
                        + "library.0.platform=common\n"
                        + "library.1.coordinate=test:other:1.0\n"
                        + "library.1.fileName=other-1.0.jar\n"
                        + "library.1.downloadUrls=http://127.0.0.1:1/other.jar\n"
                        + "library.1.sha256=" + hash + "\n"
                        + "library.1.platform=other\n"
        );
        return new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, null);
    }

    private static HttpServer server(byte[] response, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/example.jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format("%02x", value));
        return hash.toString();
    }
}
