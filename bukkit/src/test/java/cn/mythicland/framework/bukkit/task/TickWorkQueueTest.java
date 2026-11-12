package cn.mythicland.framework.bukkit.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void sharesTheDrainBudgetWithWorkAndClearsItAfterFailure() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        assertFalse(queue.hasBudget());
        queue.execute(() -> {
            assertTrue(queue.hasBudget());
            throw new IllegalStateException("invalid initialization");
        });
        CompletableFuture<Boolean> next = queue.submit(queue::hasBudget);

        assertThrows(IllegalStateException.class, this::tick);
        assertFalse(queue.hasBudget());
        assertFalse(next.isDone());
        this.tick();
        assertTrue(next.join());
        assertFalse(queue.hasBudget());
    }

    @Test
    void yieldsWhenTheTimeBudgetIsExhausted() {
        TickWorkQueue queue = this.queue(4, 1L);
        CompletableFuture<Integer> first = queue.submit(() -> {
            assertFalse(queue.hasBudget());
            return 1;
        });
        CompletableFuture<Integer> second = queue.submit(() -> 2);
        this.tick();
        assertEquals(1, first.join());
        assertFalse(second.isDone());
        this.tick();
        assertEquals(2, second.join());
    }

    @Test
    void executorSharesTheBudgetAndReschedulesAfterAnUncaughtFailure() {
        TickWorkQueue queue = this.queue(1, TimeUnit.SECONDS.toNanos(10));
        List<Integer> completed = new ArrayList<>();
        queue.execute(() -> completed.add(1));
        queue.execute(() -> {
            throw new IllegalStateException("invalid initialization");
        });
        CompletableFuture<Integer> last = queue.submit(() -> 3);
        this.tick();
        assertEquals(List.of(1), completed);
        assertFalse(last.isDone());
        assertThrows(IllegalStateException.class, this::tick);
        assertFalse(last.isDone());
        this.tick();
        assertEquals(3, last.join());
        assertTrue(this.ticks.isEmpty());
    }

    @Test
    void runsNormalWorkBeforeLowPriorityWorkAndDefersNestedSubmissions() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        List<Integer> completed = new ArrayList<>();
        queue.executeLowPriority(() -> completed.add(3));
        queue.execute(() -> {
            completed.add(1);
            queue.execute(() -> completed.add(4));
        });
        queue.execute(() -> completed.add(2));
        this.tick();
        assertEquals(List.of(1, 2, 3), completed);
        this.tick();
        assertEquals(List.of(1, 2, 3, 4), completed);
    }

    @Test
    void sharesTheTimeBudgetBetweenPriorities() {
        TickWorkQueue queue = this.queue(4, 1L);
        List<Integer> completed = new ArrayList<>();
        queue.executeLowPriority(() -> completed.add(2));
        queue.execute(() -> completed.add(1));
        this.tick();
        assertEquals(List.of(1), completed);
        this.tick();
        assertEquals(List.of(1, 2), completed);
        assertTrue(this.ticks.isEmpty());
    }

    @Test
    void appliesTheChangedBudgetToTheNextDrain() {
        TickWorkQueue queue = this.queue(4, 1L);
        List<Integer> completed = new ArrayList<>();
        queue.execute(() -> completed.add(1));
        queue.execute(() -> completed.add(2));
        queue.executeLowPriority(() -> completed.add(3));
        this.tick();
        assertEquals(List.of(1), completed);
        queue.setBudgetNanos(TimeUnit.SECONDS.toNanos(10));
        this.tick();
        assertEquals(List.of(1, 2, 3), completed);
        assertThrows(IllegalArgumentException.class, () -> queue.setBudgetNanos(0));
    }

    private TickWorkQueue queue(int maxTasks, long budgetNanos) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(call -> {
            this.ticks.add(call.getArgument(0));
            return null;
        }).when(scheduler).executeSyncLater(any(), eq(1L));
        return new TickWorkQueue(scheduler, maxTasks, budgetNanos);
    }

    @Test
    void stepsYieldWithoutConsumingTheNextTasksBudget() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        AtomicInteger steps = new AtomicInteger();
        CompletableFuture<Void> completion = queue.runSteps(() -> {
            queue.yieldBudget();
            return steps.incrementAndGet() == 2;
        });
        CompletableFuture<Boolean> following = queue.submit(queue::hasBudget);

        this.tick();
        assertEquals(1, steps.get());
        assertFalse(completion.isDone());
        assertTrue(following.join());
        this.tick();
        assertEquals(2, steps.get());
        assertTrue(completion.isDone());
    }

    @Test
    void stopsStaleOrCancelledStepsAndPropagatesStepFailures() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger steps = new AtomicInteger();
        CompletableFuture<Void> stale = queue.runSteps(() -> {
            steps.incrementAndGet();
            queue.yieldBudget();
            return false;
        }, active::get, () -> false);
        this.tick();
        active.set(false);
        this.tick();
        assertEquals(1, steps.get());
        assertTrue(stale.isDone());

        CompletableFuture<Void> cancelled = queue.runSteps(() -> {
            steps.incrementAndGet();
            return true;
        });
        cancelled.cancel(false);
        CompletableFuture<Void> failed = queue.runSteps(() -> {
            throw new IllegalStateException("Invalid step");
        });
        this.tick();
        assertEquals(1, steps.get());
        assertTrue(failed.isCompletedExceptionally());
    }

    @Test
    void rechecksPriorityAndKeepsLowPriorityInlineWorkBehindNormalTasks() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        AtomicBoolean lowPriority = new AtomicBoolean();
        List<String> completed = new ArrayList<>();
        queue.runSteps(() -> {
            completed.add("step");
            lowPriority.set(true);
            queue.yieldBudget();
            return completed.size() > 1;
        }, () -> true, lowPriority::get);
        this.tick();
        queue.execute(() -> {
            completed.add("normal");
            queue.executeIfRunning(() -> completed.add("inline"), false);
            queue.executeIfRunning(() -> completed.add("low"), true);
        });
        this.tick();
        assertEquals(List.of("step", "normal", "inline", "step"), completed);
        this.tick();
        assertEquals("low", completed.getLast());
    }

    @Test
    void closeCancelsResultsAndDiscardsScheduledWork() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        AtomicInteger steps = new AtomicInteger();
        CompletableFuture<Void> result = queue.runSteps(() -> steps.incrementAndGet() == 2);
        CompletableFuture<Integer> submitted = queue.submit(steps::incrementAndGet);
        queue.close();
        queue.close();
        this.tick();
        assertEquals(0, steps.get());
        assertTrue(result.isCancelled());
        assertTrue(submitted.isCancelled());
        assertTrue(this.ticks.isEmpty());
        assertThrows(RejectedExecutionException.class, () -> queue.execute(() -> {
        }));
    }

    @Test
    void closingFromQueuedWorkAlsoRejectsInlineContinuations() {
        TickWorkQueue queue = this.queue(4, TimeUnit.SECONDS.toNanos(10));
        queue.execute(() -> {
            queue.close();
            assertFalse(queue.hasBudget());
            assertThrows(RejectedExecutionException.class, () -> queue.executeIfRunning(
                    () -> fail("Closed work must not continue inline"), false
            ));
        });
        this.tick();
        assertTrue(this.ticks.isEmpty());
    }

    private void tick() {
        this.ticks.removeFirst().run();
    }
}
