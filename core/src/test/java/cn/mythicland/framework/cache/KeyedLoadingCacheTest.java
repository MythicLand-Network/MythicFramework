package cn.mythicland.framework.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeyedLoadingCacheTest {
    @Test
    void getOrStartLoadingDeduplicatesConcurrentLoads() {
        KeyedLoadingCache<String, String> cache = new KeyedLoadingCache<>();
        CompletableFuture<String> load = new CompletableFuture<>();
        AtomicInteger supplierCalls = new AtomicInteger();

        CompletableFuture<String> first = cache.getOrStartLoading(
                "player",
                () -> {
                    supplierCalls.incrementAndGet();
                    return load;
                }
        );
        CompletableFuture<String> second = cache.getOrStartLoading(
                "player",
                () -> {
                    supplierCalls.incrementAndGet();
                    return CompletableFuture.completedFuture("unexpected");
                }
        );

        assertSame(first, second);
        assertEquals(1, supplierCalls.get());
        assertTrue(cache.isLoading("player"));

        load.complete("loaded");

        assertEquals("loaded", first.join());
        assertFalse(cache.isLoading("player"));
    }

    @Test
    void completedLoadCanBeStartedAgain() {
        KeyedLoadingCache<String, String> cache = new KeyedLoadingCache<>();
        CompletableFuture<String> first = cache.getOrStartLoading(
                "player",
                () -> CompletableFuture.completedFuture("first")
        );

        assertEquals("first", first.join());
        assertEquals("second", cache.getOrStartLoading(
                "player",
                () -> CompletableFuture.completedFuture("second")
        ).join());
    }

    @Test
    void failedLoadCanBeRetried() {
        KeyedLoadingCache<String, String> cache = new KeyedLoadingCache<>();
        IllegalStateException failure = new IllegalStateException("load failed");

        CompletableFuture<String> failed = cache.getOrStartLoading(
                "player",
                () -> CompletableFuture.failedFuture(failure)
        );

        CompletionException exception = assertThrows(CompletionException.class, failed::join);
        assertSame(failure, exception.getCause());
        assertFalse(cache.isLoading("player"));
        assertEquals("recovered", cache.getOrStartLoading(
                "player",
                () -> CompletableFuture.completedFuture("recovered")
        ).join());
    }

    @Test
    void synchronousSupplierFailureDoesNotLeaveLoadInFlight() {
        KeyedLoadingCache<String, String> cache = new KeyedLoadingCache<>();
        IllegalArgumentException failure = new IllegalArgumentException("cannot start");

        CompletableFuture<String> result = cache.getOrStartLoading(
                "player",
                () -> {
                    throw failure;
                }
        );

        CompletionException exception = assertThrows(CompletionException.class, result::join);
        assertSame(failure, exception.getCause());
        assertFalse(cache.isLoading("player"));
    }

    @Test
    void oldOwnerCannotFinishOrRemoveReplacementLoad() {
        KeyedLoadingCache<String, String> cache = new KeyedLoadingCache<>();
        Object previousOwner = new Object();
        Object currentOwner = new Object();
        CompletableFuture<String> previousLoad = new CompletableFuture<>();
        CompletableFuture<String> currentLoad = new CompletableFuture<>();

        cache.getOrStartLoading("player", previousOwner, () -> previousLoad);
        assertTrue(cache.isCurrentLoading("player", previousOwner));
        cache.cancelLoading("player", previousOwner);
        assertFalse(cache.isLoading("player"));

        cache.getOrStartLoading("player", currentOwner, () -> currentLoad);
        cache.finishLoading("player", previousOwner);
        assertTrue(cache.isCurrentLoading("player", currentOwner));

        previousLoad.complete("stale");
        assertTrue(cache.isCurrentLoading("player", currentOwner));
        currentLoad.complete("current");
        assertFalse(cache.isLoading("player"));
    }
}
