package cn.mythicland.framework.world.v1_8_R3;

import cn.mythicland.framework.bukkit.task.TaskScheduler;
import cn.mythicland.framework.bukkit.task.TickWorkQueue;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import net.minecraft.server.v1_8_R3.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.minecraft.server.v1_8_R3.BlockPosition;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class SchematicWorkQueue implements Listener, AutoCloseable {
    private final TickWorkQueue ticks;
    private final Executor io;
    private Budget budget;
    private final Queue<Job> pending = new ArrayDeque<>();
    private final List<Job> active = new ArrayList<>();
    private final Map<ChunkKey, Integer> leases = new HashMap<>();
    private boolean scheduled;
    private boolean closed;
    private int cursor;

    public SchematicWorkQueue(TaskScheduler scheduler, Executor io, Budget budget) {
        this.io = io;
        this.budget = budget;
        this.ticks = new TickWorkQueue(scheduler, 1, budget.nanosPerTick);
    }

    public Task paste(World world, AlphaSchematic template, int x, int y, int z, Consumer<Progress> progress) {
        return submit(world, new Bounds(x, y, z, template.width(), template.height(), template.length()), template, null, false, progress);
    }

    public ChunkLease lease(World world, Bounds bounds) {
        if (this.closed) throw new IllegalStateException("Schematic work queue is closed");
        List<ChunkKey> held = new ArrayList<>();
        for (int x = bounds.x >> 4; x <= (bounds.x + bounds.width - 1) >> 4; x++) {
            for (int z = bounds.z >> 4; z <= (bounds.z + bounds.length - 1) >> 4; z++) {
                ChunkKey key = new ChunkKey(world, x, z);
                held.add(key);
                this.leases.merge(key, 1, Integer::sum);
            }
        }
        return new ChunkLease() {
            private boolean released;
            @Override public void close() {
                if (this.released) return;
                this.released = true;
                for (ChunkKey key : held) {
                    leases.computeIfPresent(key, (chunk, count) -> count == 1 ? null : count - 1);
                    if (!leases.containsKey(key)) key.world.unloadChunkRequest(key.x, key.z);
                }
            }
        };
    }

    public void configure(Budget budget) {
        this.budget = budget;
    }

    public Task clear(World world, Bounds bounds, Consumer<Progress> progress) {
        return submit(world, bounds, null, null, false, progress);
    }

    public Task prepare(World world, Bounds bounds, Consumer<Progress> progress) {
        return submit(world, bounds, null, null, true, progress);
    }

    public Task patch(World world, List<BlockChange> changes, Consumer<Progress> progress) {
        if (changes.isEmpty()) return new Task(CompletableFuture.completedFuture(Outcome.COMPLETED), () -> {});
        Map<BlockPosition, BlockChange> patches = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockChange change : changes) {
            patches.put(new BlockPosition(change.x, change.y, change.z), change);
            minX = Math.min(minX, change.x);
            minY = Math.min(minY, change.y);
            minZ = Math.min(minZ, change.z);
            maxX = Math.max(maxX, change.x);
            maxY = Math.max(maxY, change.y);
            maxZ = Math.max(maxZ, change.z);
        }
        return submit(world, new Bounds(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1),
                null, patches, false, progress);
    }

    private Task submit(
            World world,
            Bounds bounds,
            AlphaSchematic template,
            Map<BlockPosition, BlockChange> patches,
            boolean prepareOnly,
            Consumer<Progress> progress
    ) {
        if (this.closed) throw new IllegalStateException("Schematic work queue is closed");
        if (bounds.y < 0 || bounds.y + bounds.height > world.getMaxHeight())
            throw new IllegalArgumentException("Schematic edit exceeds world height");
        Job job = new Job(world, bounds, template, patches, prepareOnly, progress);
        this.pending.add(job);
        schedule();
        return new Task(job.result, () -> job.cancelled = true);
    }

    private void schedule() {
        if (this.scheduled || this.closed) return;
        this.scheduled = true;
        this.ticks.submit(() -> {
            this.scheduled = false;
            advance();
            if (!this.active.isEmpty() || !this.pending.isEmpty()) schedule();
            return null;
        });
    }

    private void advance() {
        long deadline = System.nanoTime() + this.budget.nanosPerTick;
        int positions = 0;
        int prepared = 0;
        int idle = 0;
        while (!this.pending.isEmpty() && this.active.size() < this.budget.activeTasks)
            this.active.add(this.pending.remove());
        while (!this.active.isEmpty() && positions < this.budget.positionsPerTick && System.nanoTime() < deadline) {
            if (this.cursor >= this.active.size()) this.cursor = 0;
            Job job = this.active.get(this.cursor++);
            try {
                if (job.saving != null) {
                    if (!job.saving.isDone()) {
                        // Legacy loaders can discard a submission while the same chunk is being written.
                        if (++job.saveTicks % 20 == 0) job.writer.requestSave();
                        if (++idle >= this.active.size()) break;
                        continue;
                    }
                    job.saving.join();
                    job.releaseChunk();
                    job.saving = null;
                    job.completedChunks++;
                    job.progress.accept(new Progress(job.completedChunks, job.totalChunks));
                    job.chunkX++;
                    if (job.chunkX > job.maxChunkX) {
                        job.chunkX = job.minChunkX;
                        job.chunkZ++;
                    }
                }
                if (job.cancelled || job.chunkZ > job.maxChunkZ) {
                    job.releaseChunk();
                    this.active.remove(job);
                    this.cursor--;
                    job.result.complete(job.cancelled ? Outcome.CANCELLED : Outcome.COMPLETED);
                    idle = 0;
                    continue;
                }
                if (job.writer == null) {
                    if (prepared >= this.budget.chunksPerTick) {
                        if (++idle >= this.active.size()) break;
                        continue;
                    }
                    if (job.patches != null && job.patches.keySet().stream().noneMatch(
                            position -> position.getX() >> 4 == job.chunkX && position.getZ() >> 4 == job.chunkZ)) {
                        job.chunkX++;
                        if (job.chunkX > job.maxChunkX) {
                            job.chunkX = job.minChunkX;
                            job.chunkZ++;
                        }
                        continue;
                    }
                    boolean inUse = this.active.stream().anyMatch(other -> other != job && other.writer != null
                            && other.world.equals(job.world) && other.chunkX == job.chunkX && other.chunkZ == job.chunkZ);
                    if (inUse) {
                        if (++idle >= this.active.size()) break;
                        continue;
                    }
                    job.prepareChunk();
                    prepared++;
                }
                idle = 0;
                job.place();
                positions++;
            } catch (RuntimeException failure) {
                job.releaseChunk();
                this.active.remove(job);
                this.cursor--;
                job.result.completeExceptionally(failure instanceof CompletionException ? failure.getCause() : failure);
            }
        }
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent event) {
        if (this.leases.containsKey(new ChunkKey(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ()))) {
            event.setCancelled(true);
            return;
        }
        for (Job job : this.active) {
            if (job.writer != null && event.getWorld().equals(job.world)
                    && event.getChunk().getX() == job.chunkX && event.getChunk().getZ() == job.chunkZ) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        for (Job job : this.active) {
            job.releaseChunk();
            job.result.complete(Outcome.CANCELLED);
        }
        for (Job job : this.pending) job.result.complete(Outcome.CANCELLED);
        for (ChunkKey key : this.leases.keySet()) key.world.unloadChunkRequest(key.x, key.z);
        this.leases.clear();
        this.active.clear();
        this.pending.clear();
    }

    public record Budget(int positionsPerTick, long nanosPerTick, int chunksPerTick, int activeTasks) {
        public Budget {
            if (positionsPerTick <= 0 || nanosPerTick <= 0 || chunksPerTick <= 0 || activeTasks <= 0)
                throw new IllegalArgumentException("World edit budgets must be positive");
        }
    }

    public record Bounds(int x, int y, int z, int width, int height, int length) {
        public Bounds {
            if (width <= 0 || height <= 0 || length <= 0) throw new IllegalArgumentException("Edit dimensions must be positive");
        }
    }

    public record BlockChange(int x, int y, int z, int id, int data, NBTTagCompound tile) {
        public BlockChange {
            if (id < 0 || id > 4095 || Block.getById(id) == null || data < 0 || data > 15) throw new IllegalArgumentException("Invalid block state");
            if (tile != null) tile = (NBTTagCompound) tile.clone();
        }
    }

    public record Progress(int completedChunks, int totalChunks) {
    }

    public enum Outcome { COMPLETED, CANCELLED }

    public record Task(CompletableFuture<Outcome> result, Runnable cancel) {
    }

    public interface ChunkLease extends AutoCloseable {
        @Override void close();
    }

    private record ChunkKey(World world, int x, int z) {}

    private final class Job {
        private final World world;
        private final Bounds bounds;
        private final AlphaSchematic template;
        private final Map<BlockPosition, BlockChange> patches;
        private final boolean prepareOnly;
        private final Consumer<Progress> progress;
        private final CompletableFuture<Outcome> result = new CompletableFuture<>();
        private final int minChunkX;
        private final int maxChunkX;
        private final int maxChunkZ;
        private final int totalChunks;
        private int chunkX;
        private int chunkZ;
        private int completedChunks;
        private int x;
        private int y;
        private int z;
        private int startX;
        private int endX;
        private int startZ;
        private int endZ;
        private ChunkBlockWriter writer;
        private CompletableFuture<Void> saving;
        private int saveTicks;
        private final List<NBTTagCompound> tiles = new ArrayList<>();
        private boolean cancelled;

        private Job(
                World world,
                Bounds bounds,
                AlphaSchematic template,
                Map<BlockPosition, BlockChange> patches,
                boolean prepareOnly,
                Consumer<Progress> progress
        ) {
            this.world = world;
            this.bounds = bounds;
            this.template = template;
            this.patches = patches;
            this.prepareOnly = prepareOnly;
            this.progress = progress;
            this.minChunkX = bounds.x >> 4;
            this.maxChunkX = (bounds.x + bounds.width - 1) >> 4;
            this.maxChunkZ = (bounds.z + bounds.length - 1) >> 4;
            this.chunkX = this.minChunkX;
            this.chunkZ = bounds.z >> 4;
            this.totalChunks = (this.maxChunkX - this.minChunkX + 1) * (this.maxChunkZ - this.chunkZ + 1);
        }

        private void prepareChunk() {
            this.writer = new ChunkBlockWriter(this.world.getChunkAt(this.chunkX, this.chunkZ));
            this.startX = Math.max(this.bounds.x, this.chunkX << 4);
            this.endX = Math.min(this.bounds.x + this.bounds.width - 1, (this.chunkX << 4) + 15);
            this.startZ = Math.max(this.bounds.z, this.chunkZ << 4);
            this.endZ = Math.min(this.bounds.z + this.bounds.length - 1, (this.chunkZ << 4) + 15);
            this.x = this.startX;
            this.y = this.bounds.y;
            this.z = this.startZ;
        }

        private void place() {
            if (this.prepareOnly) {
                this.saving = CompletableFuture.completedFuture(null);
                return;
            }
            if (this.patches != null) {
                BlockChange change = this.patches.get(new BlockPosition(this.x, this.y, this.z));
                if (change != null) {
                    this.writer.set(change.x, change.y, change.z, change.id, change.data);
                    if (change.tile != null) {
                        NBTTagCompound tile = (NBTTagCompound) change.tile.clone();
                        tile.setInt("x", change.x);
                        tile.setInt("y", change.y);
                        tile.setInt("z", change.z);
                        this.tiles.add(tile);
                    }
                }
            } else {
                int index = this.template == null ? 0 : this.template.index(this.x - this.bounds.x, this.y - this.bounds.y, this.z - this.bounds.z);
                this.writer.set(this.x, this.y, this.z, this.template == null ? 0 : this.template.id(index),
                        this.template == null ? 0 : this.template.data(index));
                if (this.template != null) {
                    NBTTagCompound tile = this.template.tile(this.x - this.bounds.x, this.y - this.bounds.y, this.z - this.bounds.z,
                            this.bounds.x, this.bounds.y, this.bounds.z);
                    if (tile != null) this.tiles.add(tile);
                }
            }
            if (++this.x <= this.endX) return;
            this.x = this.startX;
            if (++this.z <= this.endZ) return;
            this.z = this.startZ;
            if (++this.y < this.bounds.y + this.bounds.height) return;
            this.writer.commit();
            for (NBTTagCompound tile : this.tiles) this.writer.restoreTile(tile);
            this.tiles.clear();
            for (Player player : this.world.getPlayers()) {
                if (Math.abs((player.getLocation().getBlockX() >> 4) - this.chunkX) <= 10
                        && Math.abs((player.getLocation().getBlockZ() >> 4) - this.chunkZ) <= 10) this.writer.refresh(player);
            }
            this.saveTicks = 0;
            this.saving = this.writer.saveVerified(SchematicWorkQueue.this.io);
        }

        private void releaseChunk() {
            if (this.writer == null) return;
            this.writer = null;
            this.world.unloadChunkRequest(this.chunkX, this.chunkZ);
        }
    }
}
