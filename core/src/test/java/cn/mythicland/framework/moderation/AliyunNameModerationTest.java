package cn.mythicland.framework.moderation;

import com.aliyun.green20220302.Client;
import com.aliyun.teaopenapi.models.Config;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static cn.mythicland.framework.moderation.NameModeration.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class AliyunNameModerationTest {
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> submittedForm = new AtomicReference<>();
    private HttpServer server;
    private Client client;

    @BeforeEach
    void startServer() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        this.requests.incrementAndGet();
                        String form = exchange.getRequestURI().getRawQuery() + "&"
                                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        this.submittedForm.set(URLDecoder.decode(form, StandardCharsets.UTF_8));
                        byte[] bytes = this.response.get().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                }
        );
        this.server.start();
        var config = new Config()
                .setAccessKeyId("local-test-id")
                .setAccessKeySecret("local-test-secret")
                .setRegionId("cn-shanghai")
                .setProtocol("http")
                .setEndpoint("127.0.0.1:" + this.server.getAddress().getPort());
        this.client = new Client(config);
    }

    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    @Test
    void repeatedNamesShareOneHttpRequestAndFaultsCanBeRetried() {
        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"high\",\"Result\":[{\"Label\":\"customized\"}]}}");
        var scheduled = new ArrayDeque<Runnable>();
        var provider = new AliyunNameModeration(this.client, Logger.getAnonymousLogger(), scheduled::addLast);
        var clock = new AtomicLong();
        var moderation = new CachedNameModeration(provider, clock::get);

        var first = moderation.check("Maple");
        var duplicate = moderation.check("Maple");
        assertTrue(moderation.check("Maple").cancel(false));
        assertEquals(1, scheduled.size());
        scheduled.removeFirst().run();
        assertEquals(REJECT, first.join());
        assertEquals(REJECT, duplicate.join());
        assertEquals(REJECT, moderation.check("Maple").getNow(null));

        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"medium\"}}");
        var differentCase = moderation.check("maple");
        scheduled.removeFirst().run();
        assertEquals(REVIEW, differentCase.join());
        assertEquals(REVIEW, moderation.check("maple").getNow(null));

        clock.set(TimeUnit.MINUTES.toNanos(30) - 1);
        assertEquals(REJECT, moderation.check("Maple").getNow(null));
        clock.incrementAndGet();
        this.response.set("{\"Code\":408}");
        var expired = moderation.check("Maple");
        scheduled.removeFirst().run();
        assertEquals(UNAVAILABLE, expired.join());
        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"none\"}}");
        var retry = moderation.check("Maple");
        scheduled.removeFirst().run();
        assertEquals(ALLOW, retry.join());
        assertEquals(ALLOW, moderation.check("Maple").getNow(null));
        assertEquals(4, this.requests.get());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void sharedTextTransportPreservesConfidenceAndNicknamePolicy() {
        this.response.set(
                "{\"Code\":200,\"RequestId\":\"local-id\",\"Data\":{\"RiskLevel\":\"high\",\"Result\":[{\"Label\":\"political_n\",\"Confidence\":96.5,\"RiskWords\":\"text\"}]}}"
        );
        var text = new AliyunTextModeration(
                this.client,
                ignored -> {
                },
                Runnable::run
        );
        var result = text.check("comment_detection_pro", "第一句\n第二句").join();
        assertTrue(result.available());
        assertEquals(96.5, result.detections().getFirst().confidence());
        assertEquals("local-id", result.requestId());
        assertTrue(this.submittedForm.get().contains("comment_detection_pro"));
        var names = new AliyunNameModeration(this.client, Logger.getAnonymousLogger(), Runnable::run);
        this.response.set(
                "{\"Code\":200,\"Data\":{\"RiskLevel\":\"high\",\"Result\":[{\"Label\":\"customized\"},{}]}}"
        );
        assertEquals(REJECT, names.check("Tester").join());
        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"none\",\"Result\":[{}]}}");
        assertEquals(ALLOW, names.check("Tester").join());
    }

    @Test
    void sdkSubmitsTheOriginalNameAndMapsServiceResponses() throws Exception {
        var moderation = new AliyunNameModeration(this.client, Logger.getAnonymousLogger(), Runnable::run);
        String name = "星河_A";
        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"none\"}}");
        assertEquals(ALLOW, moderation.check(name).get(8, TimeUnit.SECONDS));
        assertTrue(this.submittedForm.get().contains("nickname_detection_pro"));
        assertTrue(this.submittedForm.get().contains(name));
        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"high\",\"Result\":[{\"Label\":\"customized\"}]}}");
        assertEquals(REJECT, moderation.check(name).join());
        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"high\",\"Result\":[{\"Label\":\"unknown\"}]}}");
        assertEquals(REVIEW, moderation.check(name).join());
        this.response.set("{\"Code\":200,\"Data\":{\"RiskLevel\":\"medium\"}}");
        assertEquals(REVIEW, moderation.check(name).join());
        this.response.set("{\"Code\":408}");
        assertEquals(UNAVAILABLE, moderation.check(name).join());
        this.response.set("{\"Code\":200,\"Data\":{}}");
        assertEquals(UNAVAILABLE, moderation.check(name).join());
    }
}
