package cn.mythicland.framework.moderation;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@FunctionalInterface
public interface TextModeration {
    TextModeration UNAVAILABLE = (service, content) -> CompletableFuture.completedFuture(Result.unavailable());

    CompletableFuture<Result> check(String service, String content);

    static TextModeration aliyun(
            String accessKeyId,
            String accessKeySecret,
            Consumer<String> warningLogger,
            Executor executor
    ) {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) return UNAVAILABLE;
        return AliyunTextModeration.create(accessKeyId, accessKeySecret, warningLogger, executor);
    }

    record Detection(String label, Double confidence, String riskWords) {}

    record Result(boolean available, String riskLevel, List<Detection> detections, String requestId) {
        public Result {
            detections = List.copyOf(detections);
        }

        public static Result unavailable() {
            return new Result(false, "unavailable", List.of(), "");
        }
    }
}
