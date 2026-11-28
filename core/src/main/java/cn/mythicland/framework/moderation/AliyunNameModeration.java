package cn.mythicland.framework.moderation;

import com.aliyun.green20220302.Client;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

final class AliyunNameModeration implements NameModeration {
    private static final Set<String> BLOCKED_LABELS = Set.of(
            "pornographic_adult", "inappropriate_profanity", "inappropriate_discrimination",
            "violent_extremist", "political_p", "political_n", "contraband_gambling",
            "pt_to_sites", "pt_by_recruitment", "pt_to_contact", "customized"
    );

    private final TextModeration moderation;

    AliyunNameModeration(Client client, Logger logger, Executor executor) {
        this(new AliyunTextModeration(client, logger::warning, executor));
    }

    private AliyunNameModeration(TextModeration moderation) {
        this.moderation = moderation;
    }

    static NameModeration create(
            String accessKeyId,
            String accessKeySecret,
            Logger logger,
            Executor executor
    ) {
        var provider = new AliyunNameModeration(
                TextModeration.aliyun(accessKeyId, accessKeySecret, logger::warning, executor)
        );
        return new CachedNameModeration(provider, System::nanoTime);
    }

    @Override
    public CompletableFuture<Verdict> check(String name) {
        return this.moderation.check("nickname_detection_pro", name).thenApply(AliyunNameModeration::classify);
    }

    private static Verdict classify(TextModeration.Result result) {
        if (!result.available()) return Verdict.UNAVAILABLE;
        return switch (result.riskLevel()) {
            case "none", "low" -> Verdict.ALLOW;
            case "medium" -> Verdict.REVIEW;
            case "high" -> classifyHighRisk(result);
            default -> Verdict.UNAVAILABLE;
        };
    }

    private static Verdict classifyHighRisk(TextModeration.Result result) {
        for (var detection : result.detections()) {
            if (detection.label().isBlank()) return Verdict.UNAVAILABLE;
            if (BLOCKED_LABELS.contains(detection.label())) return Verdict.REJECT;
        }
        return Verdict.REVIEW;
    }
}
