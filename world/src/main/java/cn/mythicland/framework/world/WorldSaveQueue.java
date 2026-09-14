package cn.mythicland.framework.world;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class WorldSaveQueue implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "MythicFramework-World-Save"));
    private final Set<String> pending = new HashSet<>();
    private boolean closed;

    synchronized CompletableFuture<Void> save(String name, Supplier<SaveOperation> snapshot) {
        if (this.closed) throw new IllegalStateException("World manager is closed");
        if (!this.pending.add(name)) throw new IllegalStateException("World save is already in progress: " + name);
        try {
            SaveOperation operation = snapshot.get();
            CompletableFuture<Void> result = new CompletableFuture<>();
            this.executor.execute(() -> {
                Throwable failure = null;
                try {
                    operation.write();
                } catch (Throwable exception) {
                    failure = exception;
                } finally {
                    synchronized (this) {
                        this.pending.remove(name);
                    }
                }
                if (failure == null) result.complete(null);
                else result.completeExceptionally(failure);
            });
            return result;
        } catch (RuntimeException | Error exception) {
            this.pending.remove(name);
            throw exception;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            this.closed = true;
            this.executor.shutdown();
        }
        boolean interrupted = false;
        try {
            while (!this.executor.isTerminated()) {
                try {
                    this.executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface SaveOperation {
        void write() throws Exception;
    }
}
