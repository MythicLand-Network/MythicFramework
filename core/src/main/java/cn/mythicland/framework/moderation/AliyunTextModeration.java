package cn.mythicland.framework.moderation;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationPlusRequest;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class AliyunTextModeration implements TextModeration {
    private static final Set<String> RISK_LEVELS = Set.of("none", "low", "medium", "high");
    private final Client client;
    private final Consumer<String> warningLogger;
    private final Executor executor;
    private final Semaphore requests = new Semaphore(32);

    AliyunTextModeration(Client client, Consumer<String> warningLogger, Executor executor) {
        this.client = client;
        this.warningLogger = warningLogger;
        this.executor = executor;
    }

    static TextModeration create(
            String accessKeyId,
            String accessKeySecret,
            Consumer<String> warningLogger,
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
            return new AliyunTextModeration(new Client(config), warningLogger, executor);
        } catch (Exception exception) {
            warningLogger.accept("Failed to initialize text moderation: " + exception.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    @Override
    public CompletableFuture<Result> check(String service, String content) {
        if (service.isBlank() || content.isBlank()) {
            throw new IllegalArgumentException("Moderation service and content must not be blank");
        }
        if (!this.requests.tryAcquire()) return CompletableFuture.completedFuture(Result.unavailable());
        var result = new CompletableFuture<Result>().completeOnTimeout(Result.unavailable(), 7, TimeUnit.SECONDS);
        try {
            this.executor.execute(() -> {
                try {
                    if (!result.isDone()) result.complete(this.request(service, content));
                } catch (Exception exception) {
                    this.warningLogger.accept("Text moderation request failed: " + exception.getClass().getSimpleName());
                    result.complete(Result.unavailable());
                } finally {
                    this.requests.release();
                }
            });
        } catch (RuntimeException exception) {
            this.requests.release();
            this.warningLogger.accept("Could not schedule text moderation: " + exception.getClass().getSimpleName());
            result.complete(Result.unavailable());
        }
        return result;
    }

    private Result request(String service, String content) throws Exception {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("content", content);
        var request = new TextModerationPlusRequest().setService(service).setServiceParameters(parameters.toString());
        var response = this.client.textModerationPlusWithOptions(
                request,
                new RuntimeOptions().setAutoretry(false).setMaxAttempts(1)
        );
        var body = response.getBody();
        if (!Integer.valueOf(200).equals(response.getStatusCode()) || body == null || !Integer.valueOf(200).equals(body.getCode())) {
            String failure = "Text moderation failed with HTTP " + response.getStatusCode();
            if (body != null) failure += ", code " + body.getCode() + ", request " + body.getRequestId();
            this.warningLogger.accept(failure);
            return Result.unavailable();
        }
        var data = body.getData();
        if (data == null
                || data.getRiskLevel() == null
                || !RISK_LEVELS.contains(data.getRiskLevel())) {
            return Result.unavailable();
        }
        var detections = new ArrayList<Detection>();
        if (data.getResult() != null) {
            for (var detection : data.getResult()) {
                if (detection == null || detection.getLabel() == null || detection.getLabel().isBlank()) {
                    detections.add(new Detection("", null, null));
                    continue;
                }
                Double confidence = null;
                if (detection.getConfidence() != null) confidence = detection.getConfidence().doubleValue();
                detections.add(new Detection(detection.getLabel(), confidence, detection.getRiskWords()));
            }
        }
        if (data.getRiskLevel().equals("high") && detections.isEmpty()) {
            return Result.unavailable();
        }
        return new Result(true, data.getRiskLevel(), detections, body.getRequestId());
    }
}
