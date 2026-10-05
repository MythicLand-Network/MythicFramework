package cn.mythicland.framework.minecraft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MinecraftProfilesTest {
    @Test
    void reusesSuccessfulLookupIgnoringCaseAndRefreshesAfterOneMinute() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Clock clock = mock(Clock.class);
            when(clock.millis()).thenReturn(0L);
            MinecraftProfiles profiles = new MinecraftProfiles(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    clock
            );
            assertEquals(profiles.findByName("Notch"), profiles.findByName("notch"));
            assertEquals(1, requests.get());
            when(clock.millis()).thenReturn(60_000L);
            assertTrue(profiles.findByName("Notch").isPresent());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void coalescesConcurrentLookupsAndDoesNotCacheRateLimitAsMissingName() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(429);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            MinecraftProfiles profiles = new MinecraftProfiles(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            CompletableFuture<?> first = CompletableFuture.supplyAsync(() -> profiles.findByName("Notch"));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertThrows(Exception.class, () -> first.get(2, TimeUnit.SECONDS));
            status.set(200);
            CompletableFuture<?> second = CompletableFuture.supplyAsync(() -> profiles.findByName("Notch"));
            CompletableFuture<?> third = CompletableFuture.supplyAsync(() -> profiles.findByName("notch"));
            assertEquals(second.get(2, TimeUnit.SECONDS), third.get(2, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void distinguishesMissingNamesFromServiceFailureAndValidatesIdentity() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring(1);
            int status = switch (name) {
                case "Missing" -> 404;
                case "Unavailable" -> 429;
                default -> 200;
            };
            String body = name.equals("Malformed") ? "{}"
                    : "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            MinecraftProfiles profiles = new MinecraftProfiles(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), profiles.findByName("Notch").orElseThrow().uuid());
            assertTrue(profiles.findByName("Missing").isEmpty());
            assertThrows(IllegalStateException.class, () -> profiles.findByName("Unavailable"));
            assertThrows(RuntimeException.class, () -> profiles.findByName("Malformed"));
            assertThrows(IllegalStateException.class, () -> profiles.findByName("Imposter"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rateLimitedMojangLookupUsesFallbackForPremiumAndMissingNames() throws Exception {
        AtomicInteger fallbackRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mojang/", exchange -> respond(exchange, 429, ""));
        server.createContext("/fallback/", exchange -> {
            fallbackRequests.incrementAndGet();
            String body = exchange.getRequestURI().getPath().endsWith("/Notch")
                    ? "{\"exists\":true,\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}"
                    : "{\"exists\":false,\"uuid\":null}";
            respond(exchange, 200, body);
        });
        server.start();
        try {
            MinecraftProfiles profiles = profilesWithFallback(server);
            assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                    profiles.findByName("Notch").orElseThrow().uuid());
            assertTrue(profiles.findByName("MagooCat").isEmpty());
            assertTrue(profiles.findByName("notch").isPresent());
            assertEquals(2, fallbackRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulMissingAndInvalidMojangResponsesDoNotQueryFallback() throws Exception {
        AtomicInteger fallbackRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mojang/", exchange -> {
            int status = exchange.getRequestURI().getPath().endsWith("/Missing") ? 404 : 200;
            respond(exchange, status, "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}");
        });
        server.createContext("/fallback/", exchange -> {
            fallbackRequests.incrementAndGet();
            respond(exchange, 200, "{\"exists\":false,\"uuid\":null}");
        });
        server.start();
        try {
            MinecraftProfiles profiles = profilesWithFallback(server);
            assertTrue(profiles.findByName("Notch").isPresent());
            assertTrue(profiles.findByName("Missing").isEmpty());
            assertThrows(IllegalStateException.class, () -> profiles.findByName("Imposter"));
            assertEquals(0, fallbackRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableAndMalformedFallbackResultsAreNotTreatedOrCachedAsMissingNames() throws Exception {
        AtomicInteger status = new AtomicInteger(429);
        AtomicReference<String> body = new AtomicReference<>("{}");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mojang/", exchange -> respond(exchange, 429, ""));
        server.createContext("/fallback/", exchange -> respond(exchange, status.get(), body.get()));
        server.start();
        try {
            MinecraftProfiles profiles = profilesWithFallback(server);
            for (int failureStatus : new int[]{429, 503, 404}) {
                status.set(failureStatus);
                assertThrows(IllegalStateException.class, () -> profiles.findByName("Notch"));
            }
            status.set(200);
            for (String invalidBody : new String[]{
                    "{}",
                    "{\"exists\":false}",
                    "{\"exists\":\"false\",\"uuid\":null}",
                    "{\"exists\":false,\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}",
                    "{\"exists\":true,\"uuid\":null}",
                    "{\"exists\":true,\"uuid\":\"1-1-1-1-1\"}",
                    "{\"exists\":true,\"uuid\":\"invalid\"}"
            }) {
                body.set(invalidBody);
                assertThrows(RuntimeException.class, () -> profiles.findByName("Notch"));
            }
            body.set("{\"exists\":true,\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}");
            assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                    profiles.findByName("Notch").orElseThrow().uuid());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mojangNetworkFailureUsesFallback() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"exists\":false,\"uuid\":null}");
        when(client.<String>send(any(), any())).thenThrow(new IOException("Connection failed")).thenReturn(response);
        MinecraftProfiles profiles = new MinecraftProfiles(
                client,
                URI.create("https://mojang.invalid/"),
                URI.create("https://fallback.invalid/"),
                Clock.systemUTC()
        );

        assertTrue(profiles.findByName("MagooCat").isEmpty());
        verify(client, times(2)).send(any(), any());
    }

    private static MinecraftProfiles profilesWithFallback(HttpServer server) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        return new MinecraftProfiles(
                HttpClient.newHttpClient(),
                base.resolve("mojang/"),
                base.resolve("fallback/"),
                Clock.systemUTC()
        );
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
