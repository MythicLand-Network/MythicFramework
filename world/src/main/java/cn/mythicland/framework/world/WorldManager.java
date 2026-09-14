package cn.mythicland.framework.world;

import net.minecraft.server.v1_8_R3.FileIOThread;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class WorldManager implements AutoCloseable {
    private final JavaPlugin plugin;
    private final WorldSaveQueue saves = new WorldSaveQueue();
    private final TerrainSnapshotStore store;
    private final boolean resetEnabled;
    private Map<String, World.Environment> restoredWorlds = Map.of();
    private boolean prepared;
    private boolean loaded;
    private boolean closed;

    public WorldManager(JavaPlugin plugin, Path directory, boolean resetEnabled) {
        this.plugin = Objects.requireNonNull(plugin);
        this.store = new TerrainSnapshotStore(directory);
        this.resetEnabled = resetEnabled;
    }

    public void prepare() throws IOException {
        if (this.prepared || this.closed) throw new IllegalStateException("World manager cannot be prepared again");
        if (!this.resetEnabled) {
            this.prepared = true;
            return;
        }
        if (!this.plugin.getServer().getWorlds().isEmpty()) {
            throw new IllegalStateException("World terrain must be restored before server world loading");
        }
        Map<String, World.Environment> snapshots = this.store.snapshots();
        if (snapshots.isEmpty()) {
            throw new IOException(
                    "World reset is enabled, but no terrain snapshot exists. "
                            + "Run /pit world save <world> before enabling world-reset."
            );
        }
        Path worldContainer = this.plugin.getServer().getWorldContainer().toPath();
        for (Map.Entry<String, World.Environment> snapshot : snapshots.entrySet()) {
            Path regionDirectory = regionDirectory(
                    worldContainer.resolve(snapshot.getKey()), snapshot.getValue()
            );
            this.store.restore(snapshot.getKey(), regionDirectory);
        }
        this.restoredWorlds = Map.copyOf(snapshots);
        this.prepared = true;
    }

    public void loadAdditionalWorlds() {
        if (!this.prepared || this.loaded || this.closed) throw new IllegalStateException("Invalid world loading phase");
        for (Map.Entry<String, World.Environment> restored : this.restoredWorlds.entrySet()) {
            World world = this.plugin.getServer().getWorld(restored.getKey());
            if (world == null) {
                world = new WorldCreator(restored.getKey()).environment(restored.getValue()).createWorld();
            }
            if (world == null) throw new IllegalStateException("Cannot load restored world: " + restored.getKey());
            if (world.getEnvironment() != restored.getValue()) {
                throw new IllegalStateException("Restored world environment differs from its snapshot: " + restored.getKey());
            }
        }
        this.loaded = true;
    }

    public boolean resetEnabled() {
        return this.resetEnabled;
    }

    public Set<String> savedWorlds() {
        try {
            return Set.copyOf(this.store.snapshots().keySet());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read saved world snapshots", exception);
        }
    }

    public CompletableFuture<Void> save(String name) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World snapshot must be captured on the server thread");
        if (!this.loaded || this.closed) throw new IllegalStateException("World manager is not running");
        World world = this.plugin.getServer().getWorld(name);
        if (world == null) throw new IllegalArgumentException("World is not loaded: " + name);
        return this.saves.save(name, () -> {
            world.save();
            flushChunkWrites();
            Path regionDirectory = regionDirectory(world.getWorldFolder().toPath(), world.getEnvironment());
            Path capture;
            try {
                capture = this.store.capture(name, regionDirectory);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot capture world terrain: " + name, exception);
            }
            World.Environment environment = world.getEnvironment();
            return () -> this.store.write(name, environment, capture);
        });
    }

    private static Path regionDirectory(Path worldDirectory, World.Environment environment) {
        Path normalized = worldDirectory.toAbsolutePath().normalize();
        return switch (environment) {
            case NORMAL -> normalized.resolve("region");
            case NETHER -> normalized.resolve("DIM-1/region");
            case THE_END -> normalized.resolve("DIM1/region");
        };
    }

    private static void flushChunkWrites() {
        try {
            FileIOThread.a().b();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while flushing world chunks", exception);
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.saves.close();
        this.restoredWorlds = Map.of();
    }
}
