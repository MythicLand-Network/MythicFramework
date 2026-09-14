package cn.mythicland.framework.world;

import net.minecraft.server.v1_8_R3.NBTTagCompound;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSnapshotStoreTest {
    @TempDir
    Path directory;

    @Test void saveAndRestore_replacesOnlySanitizedTerrainFiles() throws Exception {
        Path storage = this.directory.resolve("storage");
        TerrainSnapshotStore store = new TerrainSnapshotStore(storage);
        Path sourceRegion = this.directory.resolve("source/region");
        TerrainTestRegion.write(sourceRegion, "saved", true);
        store.write("world", World.Environment.NORMAL, store.capture("world", sourceRegion));
        assertEquals(Map.of("world", World.Environment.NORMAL), store.snapshots());

        Path worldDirectory = this.directory.resolve("server/world");
        Path targetRegion = worldDirectory.resolve("region");
        TerrainTestRegion.write(targetRegion, "changed", false);
        Path playerData = worldDirectory.resolve("playerdata/player.dat");
        Files.createDirectories(playerData.getParent());
        Files.writeString(playerData, "player-data");

        assertTrue(store.restore("world", targetRegion));
        NBTTagCompound level = TerrainTestRegion.read(targetRegion.resolve("r.0.0.mca")).getCompound("Level");
        assertEquals("saved", level.getString("Marker"));
        assertFalse(level.hasKey("Entities"));
        assertEquals("Chest", level.getList("TileEntities", 10).get(0).getString("id"));
        assertEquals("player-data", Files.readString(playerData));
    }

    @Test void damagedSnapshot_doesNotReplaceCurrentTerrain() throws Exception {
        Path storage = this.directory.resolve("storage");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("world.terrain"), "not a zip");
        Path targetRegion = this.directory.resolve("server/world/region");
        TerrainTestRegion.write(targetRegion, "current", false);

        assertThrows(IOException.class, () -> new TerrainSnapshotStore(storage).restore("world", targetRegion));
        assertEquals(
                "current",
                TerrainTestRegion.read(targetRegion.resolve("r.0.0.mca")).getCompound("Level").getString("Marker")
        );
    }

    @Test void missingSnapshot_leavesWorldUntouched() throws Exception {
        Path storage = this.directory.resolve("storage");
        Path targetRegion = this.directory.resolve("server/world/region");
        assertFalse(new TerrainSnapshotStore(storage).restore("world", targetRegion));
        assertFalse(Files.exists(targetRegion));
        assertFalse(Files.exists(storage));
    }
}
