package cn.mythicland.framework.world;

import net.minecraft.server.v1_8_R3.NBTTagCompound;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
