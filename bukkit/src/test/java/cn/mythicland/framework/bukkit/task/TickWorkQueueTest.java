package cn.mythicland.framework.bukkit.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

final class TickWorkQueueTest {
    private final List<Runnable> ticks = new ArrayList<>();

    @Test
    void defersWorkAndPreservesOrderAcrossTicks() {
        TickWorkQueue queue = this.queue(2, TimeUnit.SECONDS.toNanos(10));
        List<Integer> completed = new ArrayList<>();
        List<CompletableFuture<Integer>> results = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            int value = index;
            results.add(queue.submit(() -> {
                completed.add(value);
                return value;
            }));
        }
        assertEquals(1, this.ticks.size());
        assertTrue(completed.isEmpty());
        this.tick();
        assertEquals(List.of(0, 1), completed);
        assertFalse(results.get(2).isDone());
        this.tick();
        assertEquals(List.of(0, 1, 2, 3), completed);
        this.tick();
        assertEquals(List.of(0, 1, 2, 3, 4), completed);
        assertTrue(this.ticks.isEmpty());
        for (int index = 0; index < results.size(); index++) assertEquals(index, results.get(index).join());
    }

    @Test
    void keepsNestedSubmissionsForTheNextTickAndContinuesAfterFailure() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        List<CompletableFuture<Integer>> nested = new ArrayList<>();
        CompletableFuture<Integer> failed = queue.submit(() -> {
            nested.add(queue.submit(() -> 7));
            throw new IllegalStateException("invalid payload");
        });
        CompletableFuture<Integer> next = queue.submit(() -> 3);
        this.tick();
        assertTrue(failed.isCompletedExceptionally());
        assertEquals(3, next.join());
        assertFalse(nested.getFirst().isDone());
        this.tick();
        assertEquals(7, nested.getFirst().join());
        assertTrue(this.ticks.isEmpty());
        CompletableFuture<Integer> later = queue.submit(() -> 9);
        this.tick();
        assertEquals(9, later.join());
    }

    @Test
    void yieldsWhenTheTimeBudgetIsExhausted() {
        TickWorkQueue queue = this.queue(4, 1L);
        CompletableFuture<Integer> first = queue.submit(() -> 1);
        CompletableFuture<Integer> second = queue.submit(() -> 2);
        this.tick();
        assertEquals(1, first.join());
        assertFalse(second.isDone());
        this.tick();
        assertEquals(2, second.join());
    }

    private TickWorkQueue queue(int maxTasks, long budgetNanos) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(call -> {
            this.ticks.add(call.getArgument(0));
            return null;
        }).when(scheduler).executeSyncLater(any(), eq(1L));
        return new TickWorkQueue(scheduler, maxTasks, budgetNanos);
    }

    private void tick() {
        this.ticks.removeFirst().run();
    }
}
