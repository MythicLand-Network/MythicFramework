package cn.mythicland.framework.world;

import net.minecraft.server.v1_8_R3.ChunkCoordIntPair;
import net.minecraft.server.v1_8_R3.ChunkRegionLoader;
import net.minecraft.server.v1_8_R3.FileIOThread;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.RegionFileCache;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorldManagerTest {
    @TempDir
    Path directory;

    @Test void disabledManager_doesNotAccessTheServerOrCreateStorage() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Path storage = this.directory.resolve("worlds");
        try (WorldManager manager = new WorldManager(plugin, storage, false)) {
            manager.prepare();
            manager.loadAdditionalWorlds();
            assertFalse(manager.resetEnabled());
        }
        verifyNoInteractions(plugin);
        assertFalse(Files.exists(storage));
    }

    @Test void prepare_restoresSavedTerrainBeforeWorldLoading() throws Exception {
        Path storage = this.directory.resolve("storage");
        TerrainSnapshotStore store = new TerrainSnapshotStore(storage);
        Path sourceRegion = this.directory.resolve("source/region");
        TerrainTestRegion.write(sourceRegion, "baseline", true);
        store.write("world", World.Environment.NORMAL, store.capture("world", sourceRegion));

        Path serverRoot = this.directory.resolve("server");
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of());
        when(server.getWorldContainer()).thenReturn(serverRoot.toFile());
        try (WorldManager manager = new WorldManager(plugin, storage, true)) {
            manager.prepare();
            assertTrue(manager.savedWorlds().contains("world"));
        }

        Path restored = serverRoot.resolve("world/region/r.0.0.mca");
        NBTTagCompound level = TerrainTestRegion.read(restored).getCompound("Level");
        assertTrue(Files.isRegularFile(restored));
        assertEquals("baseline", level.getString("Marker"));
        assertFalse(level.hasKey("Entities"));
    }

    @Test void enabledManager_requiresASavedWorld() {
        Path storage = this.directory.resolve("storage");
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of());
        try (WorldManager manager = new WorldManager(plugin, storage, true)) {
            assertThrows(java.io.IOException.class, manager::prepare);
        }
    }

    @Test void save_preservesEditedTerrainWhenAnOlderChunkWriteIsInProgress() throws Exception {
        Path worldDirectory = this.directory.resolve("world");
        Path regionDirectory = worldDirectory.resolve("region");
        Path region = TerrainTestRegion.write(regionDirectory, "before-edit", false);
        Path editedRegion = TerrainTestRegion.write(this.directory.resolve("edited"), "after-edit", false);
        NBTTagCompound editedChunk = TerrainTestRegion.read(editedRegion);
        ChunkRegionLoader loader = new ChunkRegionLoader(worldDirectory.toFile());
        var writingField = ChunkRegionLoader.class.getDeclaredField("c");
        writingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<ChunkCoordIntPair> writing = (Set<ChunkCoordIntPair>) writingField.get(loader);
        ChunkCoordIntPair position = new ChunkCoordIntPair(0, 0);
        writing.add(position);
        var enqueue = ChunkRegionLoader.class.getDeclaredMethod("a", ChunkCoordIntPair.class, NBTTagCompound.class);
        enqueue.setAccessible(true);

        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld("world")).thenReturn(world);
        when(world.getWorldFolder()).thenReturn(worldDirectory.toFile());
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        doAnswer(invocation -> {
            enqueue.invoke(loader, position, editedChunk);
            return null;
        }).when(world).save();

        FileIOThread writer = mock(FileIOThread.class);
        doAnswer(invocation -> {
            writing.remove(position);
            while (loader.c()) {
            }
            return null;
        }).when(writer).b();
        Path storage = this.directory.resolve("storage");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<FileIOThread> fileIO = mockStatic(FileIOThread.class);
             WorldManager manager = new WorldManager(plugin, storage, false)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            fileIO.when(FileIOThread::a).thenReturn(writer);
            manager.prepare();
            manager.loadAdditionalWorlds();
            manager.save("world").get(10, TimeUnit.SECONDS);
        } finally {
            RegionFileCache.a();
        }

        TerrainTestRegion.write(regionDirectory, "runtime-changes", false);
        when(server.getWorlds()).thenReturn(List.of());
        when(server.getWorldContainer()).thenReturn(this.directory.toFile());
        try (WorldManager restarted = new WorldManager(plugin, storage, true)) {
            restarted.prepare();
        }
        assertEquals("after-edit", TerrainTestRegion.read(region).getCompound("Level").getString("Marker"));
    }
}
