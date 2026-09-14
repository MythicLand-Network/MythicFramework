package cn.mythicland.framework.world;

import net.minecraft.server.v1_8_R3.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerrainRegionFileTest {
    @TempDir
    Path directory;

    @Test void sanitize_removesOrdinaryEntitiesAndKeepsTerrainData() throws Exception {
        Path source = TerrainTestRegion.write(this.directory.resolve("source"), "saved", true);
        Path target = this.directory.resolve("sanitized/r.0.0.mca");
        TerrainRegionFile.sanitize(source, target);
        TerrainRegionFile.validate(target);

        NBTTagCompound level = TerrainTestRegion.read(target).getCompound("Level");
        assertFalse(level.hasKey("Entities"));
        assertEquals("saved", level.getString("Marker"));
        assertEquals("Chest", level.getList("TileEntities", 10).get(0).getString("id"));
    }
}
