package cn.mythicland.framework.moderation;

import cn.mythicland.framework.cache.KeyedLoadingCache;
import cn.mythicland.framework.cache.LruCache;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

final class CachedNameModeration implements NameModeration {
    private static final long TTL_NANOS = Duration.ofMinutes(30).toNanos();
    private final LruCache<String, Result> results = new LruCache<>(4096);
    private final KeyedLoadingCache<String, Verdict> pending = new KeyedLoadingCache<>();
    private final NameModeration provider;
    private final LongSupplier clock;

    CachedNameModeration(NameModeration provider, LongSupplier clock) {
        this.provider = provider;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<Verdict> check(String name) {
        return this.pending.getOrStartLoading(
                name,
                () -> {
                    Result cached = this.results.get(name);
                    if (cached != null && this.clock.getAsLong() - cached.createdAtNanos() < TTL_NANOS) {
                        return CompletableFuture.completedFuture(cached.verdict());
                    }
                    return this.provider.check(name).thenApply(verdict -> {
                                if (verdict != Verdict.UNAVAILABLE) {
                                    this.results.put(name, new Result(verdict, this.clock.getAsLong()));
                                }
                                return verdict;
                            }
                    );
                }
        ).copy();
    }

    private record Result(Verdict verdict, long createdAtNanos) {
    }
}
