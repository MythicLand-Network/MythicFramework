package cn.mythicland.framework.world;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorldSaveQueueTest {
    @Test void capturesOnCallerThreadRejectsDuplicateAndWaitsForWritesOnClose() throws Exception {
        WorldSaveQueue queue = new WorldSaveQueue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> snapshotThread = new AtomicReference<>();
        CompletableFuture<Void> save = queue.save("world", () -> {
            snapshotThread.set(Thread.currentThread());
            return () -> { started.countDown(); release.await(); };
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(Thread.currentThread(), snapshotThread.get());
        assertThrows(IllegalStateException.class, () -> queue.save("world", () -> () -> {}));
        CompletableFuture<Void> close = CompletableFuture.runAsync(queue::close);
        assertFalse(save.isDone());
        assertFalse(close.isDone());
        release.countDown();
        save.get(5, TimeUnit.SECONDS); close.get(5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> queue.save("world", () -> () -> {}));
    }

    @Test void reportsWriteFailureAndAllowsRetryWithoutFalseSuccess() throws Exception {
        try (WorldSaveQueue queue = new WorldSaveQueue()) {
            CompletableFuture<Void> failure = queue.save("world", () -> () -> { throw new IOException("disk full"); });
            assertThrows(ExecutionException.class, () -> failure.get(5, TimeUnit.SECONDS));
            queue.save("world", () -> () -> {}).get(5, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class, () -> queue.save("world", () -> { throw new IllegalArgumentException("snapshot failed"); }));
            queue.save("world", () -> () -> {}).get(5, TimeUnit.SECONDS);
        }
    }
    @Test void completesExceptionallyWhenNativeCompressionCannotLink() throws Exception {
        try (WorldSaveQueue queue = new WorldSaveQueue()) {
            CompletableFuture<Void> failure = queue.save("world", () -> () -> {
                throw new UnsatisfiedLinkError("compression library unavailable");
            });
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> failure.get(5, TimeUnit.SECONDS));
            assertInstanceOf(UnsatisfiedLinkError.class, exception.getCause());
            queue.save("world", () -> () -> {}).get(5, TimeUnit.SECONDS);
        }
    }

}
