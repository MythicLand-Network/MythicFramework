package cn.mythicland.framework.bukkit.task;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class TickWorkQueue {
    private final TaskScheduler scheduler;
    private final int maxTasksPerTick;
    private final long budgetNanos;
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private boolean scheduled;

    public TickWorkQueue(TaskScheduler scheduler, int maxTasksPerTick, long budgetNanos) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (maxTasksPerTick <= 0) throw new IllegalArgumentException("maxTasksPerTick must be positive");
        if (budgetNanos <= 0) throw new IllegalArgumentException("budgetNanos must be positive");
        this.maxTasksPerTick = maxTasksPerTick;
        this.budgetNanos = budgetNanos;
    }

    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (this.pending) {
            this.pending.add(() -> {
                try {
                    result.complete(work.get());
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
            if (!this.scheduled) {
                this.scheduled = true;
                this.scheduler.executeSyncLater(this::drain, 1L);
            }
        }
        return result;
    }

    private void drain() {
        long started = System.nanoTime();
        int taskCount;
        synchronized (this.pending) {
            taskCount = Math.min(this.maxTasksPerTick, this.pending.size());
        }
        try {
            for (int processed = 0; processed < taskCount; processed++) {
                Runnable work;
                synchronized (this.pending) {
                    work = this.pending.poll();
                }
                if (work == null) break;
                work.run();
                if (System.nanoTime() - started >= this.budgetNanos) break;
            }
        } finally {
            synchronized (this.pending) {
                if (this.pending.isEmpty()) this.scheduled = false;
                else this.scheduler.executeSyncLater(this::drain, 1L);
            }
        }
    }
}
