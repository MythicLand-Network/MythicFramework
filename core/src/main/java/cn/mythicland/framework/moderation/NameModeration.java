package cn.mythicland.framework.moderation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

@FunctionalInterface
public interface NameModeration {
    NameModeration DISABLED = name -> CompletableFuture.completedFuture(Verdict.ALLOW);
    NameModeration UNAVAILABLE = name -> CompletableFuture.completedFuture(Verdict.UNAVAILABLE);

    enum Verdict {
        ALLOW, REJECT, REVIEW, UNAVAILABLE
    }

    CompletableFuture<Verdict> check(String name);

    static NameModeration aliyun(
            String accessKeyId,
            String accessKeySecret,
            Logger logger,
            Executor executor
    ) {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            if (accessKeyId.isBlank()) logger.warning("Missing name-moderation.access-key-id");
            if (accessKeySecret.isBlank()) logger.warning("Missing name-moderation.access-key-secret");
            return UNAVAILABLE;
        }
        return AliyunNameModeration.create(accessKeyId, accessKeySecret, logger, executor);
    }
}
