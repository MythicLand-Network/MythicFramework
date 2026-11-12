package cn.mythicland.framework.bukkit.task;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs main-thread work with a shared, cooperative time budget. Individual steps cannot be interrupted.
 */
public final class TickWorkQueue implements Executor, AutoCloseable {
    private final TaskScheduler scheduler;
    private final int maxTasksPerTick;
    private long budgetNanos;
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final Queue<Runnable> lowPriority = new ArrayDeque<>();
    private final Set<CompletableFuture<?>> completions = new HashSet<>();
    private boolean scheduled;
    private boolean draining;
    private volatile boolean closed;
    private boolean executingLowPriority;
    private boolean yielded;
    private Thread executingThread;
    private long startedNanos;

    public TickWorkQueue(TaskScheduler scheduler, int maxTasksPerTick, long budgetNanos) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (maxTasksPerTick <= 0) throw new IllegalArgumentException("maxTasksPerTick must be positive");
        if (budgetNanos <= 0) throw new IllegalArgumentException("budgetNanos must be positive");
        this.maxTasksPerTick = maxTasksPerTick;
        this.budgetNanos = budgetNanos;
    }

    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = this.newCompletion();
        try {
            this.execute(() -> {
                if (result.isDone()) return;
                try {
                    result.complete(work.get());
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
            throw exception;
        }
        return result;
    }

    @Override
    public void execute(Runnable work) {
        this.execute(work, this.pending);
    }

    public void executeLowPriority(Runnable work) {
        this.execute(work, this.lowPriority);
    }

    private void execute(Runnable work, Queue<Runnable> queue) {
        Objects.requireNonNull(work, "work");
        synchronized (this.pending) {
            if (this.closed) throw new RejectedExecutionException("Tick work queue is closed");
            queue.add(work);
            if (!this.scheduled) {
                this.scheduled = true;
                this.scheduler.executeSyncLater(this::drain, 1L);
            }
        }
    }

    public boolean hasBudget() {
        return !this.closed && this.isExecuting() && !this.yielded && this.hasTimeBudget();
    }

    public boolean isExecuting() {
        return Thread.currentThread() == this.executingThread;
    }

    /**
     * Ends the current task's step loop without changing the budget available to other queued work.
     */
    public void yieldBudget() {
        this.yielded = true;
    }

    public void executeIfRunning(Runnable work, boolean lowPriority) {
        if (!this.hasBudget() || lowPriority && !this.executingLowPriority) {
            this.enqueue(work, lowPriority);
            return;
        }
        try {
            work.run();
        } catch (RuntimeException exception) {
            this.enqueue(() -> {
                throw exception;
            }, lowPriority);
        }
    }

    public CompletableFuture<Void> runSteps(BooleanSupplier step) {
        return this.runSteps(step, () -> true, () -> false);
    }

    /**
     * Runs steps until one returns true, yielding between ticks when the budget expires.
     * Eligibility and priority are rechecked for every continuation. Ineligible work completes normally;
     * cancelling the result prevents further steps. Predicates and steps run on the main thread, except
     * that priority is also read on the submitting thread for the initial enqueue.
     */
    public CompletableFuture<Void> runSteps(
            BooleanSupplier step,
            BooleanSupplier active,
            BooleanSupplier lowPriority
    ) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(lowPriority, "lowPriority");
        CompletableFuture<Void> completion = this.newCompletion();
        Runnable continuation = new Runnable() {
            @Override
            public void run() {
                if (completion.isDone()) return;
                try {
                    if (!active.getAsBoolean()) {
                        completion.complete(null);
                        return;
                    }
                    do {
                        if (completion.isDone()) return;
                        if (step.getAsBoolean()) {
                            completion.complete(null);
                            return;
                        }
                    } while (TickWorkQueue.this.hasBudget());
                    TickWorkQueue.this.enqueue(this, lowPriority.getAsBoolean());
                } catch (RuntimeException exception) {
                    completion.completeExceptionally(exception);
                }
            }
        };
        try {
            this.enqueue(continuation, lowPriority.getAsBoolean());
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
        return completion;
    }

    private void enqueue(Runnable work, boolean lowPriority) {
        if (lowPriority) {
            this.executeLowPriority(work);
        } else {
            this.execute(work);
        }
    }

    private <T> CompletableFuture<T> newCompletion() {
        CompletableFuture<T> completion = new CompletableFuture<>();
        synchronized (this.pending) {
            if (this.closed) throw new RejectedExecutionException("Tick work queue is closed");
            this.completions.add(completion);
        }
        completion.whenComplete((result, failure) -> {
            synchronized (this.pending) {
                this.completions.remove(completion);
            }
        });
        return completion;
    }

    @Override
    public void close() {
        Set<CompletableFuture<?>> cancelled;
        synchronized (this.pending) {
            this.closed = true;
            this.pending.clear();
            this.lowPriority.clear();
            cancelled = Set.copyOf(this.completions);
        }
        cancelled.forEach(completion -> completion.cancel(false));
    }

    public void setBudgetNanos(long budgetNanos) {
        if (budgetNanos <= 0) throw new IllegalArgumentException("budgetNanos must be positive");
        this.budgetNanos = budgetNanos;
    }

    private void drain() {
        this.startedNanos = System.nanoTime();
        this.draining = true;
        int normalCount;
        int lowCount;
        synchronized (this.pending) {
            normalCount = Math.min(this.maxTasksPerTick, this.pending.size());
            lowCount = Math.min(this.maxTasksPerTick - normalCount, this.lowPriority.size());
        }
        try {
            for (int processed = 0; processed < normalCount + lowCount; processed++) {
                Runnable work;
                synchronized (this.pending) {
                    work = processed < normalCount ? this.pending.poll() : this.lowPriority.poll();
                }
                if (work == null) break;
                this.executingThread = Thread.currentThread();
                this.executingLowPriority = processed >= normalCount;
                this.yielded = false;
                try {
                    work.run();
                } finally {
                    this.executingThread = null;
                    this.executingLowPriority = false;
                }
                if (!this.hasTimeBudget()) break;
            }
        } finally {
            this.draining = false;
            synchronized (this.pending) {
                if (this.pending.isEmpty() && this.lowPriority.isEmpty()) this.scheduled = false;
                else this.scheduler.executeSyncLater(this::drain, 1L);
            }
        }
    }

    private boolean hasTimeBudget() {
        return this.draining && System.nanoTime() - this.startedNanos < this.budgetNanos;
    }
}
