package cn.mythicland.framework.moderation;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationPlusRequest;
import com.aliyun.green20220302.models.TextModerationPlusResponseBody.TextModerationPlusResponseBodyData;
import com.aliyun.teaopenapi.models.Config;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

final class AliyunNameModeration implements NameModeration {
    private static final Set<String> BLOCKED_LABELS = Set.of(
            "pornographic_adult", "inappropriate_profanity", "inappropriate_discrimination",
            "violent_extremist", "political_p", "political_n", "contraband_gambling",
            "pt_to_sites", "pt_by_recruitment", "pt_to_contact", "customized"
    );

    private final Client client;
    private final Logger logger;
    private final Executor executor;
    private final Semaphore requests = new Semaphore(32);

    AliyunNameModeration(Client client, Logger logger, Executor executor) {
        this.client = client;
        this.logger = logger;
        this.executor = executor;
    }

    static NameModeration create(
            String accessKeyId,
            String accessKeySecret,
            Logger logger,
            Executor executor
    ) {
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setRegionId("cn-shanghai")
                .setEndpoint("green-cip.cn-shanghai.aliyuncs.com")
                .setConnectTimeout(2000)
                .setReadTimeout(4000);
        try {
            var provider = new AliyunNameModeration(new Client(config), logger, executor);
            return new CachedNameModeration(provider, System::nanoTime);
        } catch (Exception exception) {
            logger.warning("Failed to initialize name moderation: " + exception.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    @Override
    public CompletableFuture<Verdict> check(String name) {
        if (!this.requests.tryAcquire()) return CompletableFuture.completedFuture(Verdict.UNAVAILABLE);
        var result = new CompletableFuture<Verdict>().completeOnTimeout(Verdict.UNAVAILABLE, 7, TimeUnit.SECONDS);
        try {
            this.executor.execute(() -> {
                try {
                    if (!result.isDone()) result.complete(this.request(name));
                } catch (Exception exception) {
                    this.logger.warning("Name moderation request failed: " + exception.getClass().getSimpleName());
                    result.complete(Verdict.UNAVAILABLE);
                } finally {
                    this.requests.release();
                }
            });
        } catch (RuntimeException exception) {
            this.requests.release();
            this.logger.warning("Could not schedule name moderation: " + exception.getClass().getSimpleName());
            result.complete(Verdict.UNAVAILABLE);
        }
        return result;
    }

    private Verdict request(String name) throws Exception {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("content", name);
        var request = new TextModerationPlusRequest()
                .setService("nickname_detection_pro")
                .setServiceParameters(parameters.toString());
        var response = this.client.textModerationPlus(request);
        var body = response.getBody();
        if (Integer.valueOf(200).equals(response.getStatusCode()) && body != null
                && Integer.valueOf(200).equals(body.getCode())) {
            return classify(body.getData());
        }
        String failure = "Name moderation failed with HTTP " + response.getStatusCode();
        if (body != null) failure += ", code " + body.getCode() + ", request " + body.getRequestId();
        this.logger.warning(failure);
        return Verdict.UNAVAILABLE;
    }

    static Verdict classify(TextModerationPlusResponseBodyData data) {
        if (data == null || data.getRiskLevel() == null) return Verdict.UNAVAILABLE;
        return switch (data.getRiskLevel()) {
            case "none", "low" -> Verdict.ALLOW;
            case "medium" -> Verdict.REVIEW;
            case "high" -> classifyHighRisk(data);
            default -> Verdict.UNAVAILABLE;
        };
    }

    private static Verdict classifyHighRisk(TextModerationPlusResponseBodyData data) {
        var results = data.getResult();
        if (results == null || results.isEmpty()) return Verdict.UNAVAILABLE;
        for (var result : results) {
            if (result == null || result.getLabel() == null || result.getLabel().isBlank()) return Verdict.UNAVAILABLE;
            if (BLOCKED_LABELS.contains(result.getLabel())) return Verdict.REJECT;
        }
        return Verdict.REVIEW;
    }
}
