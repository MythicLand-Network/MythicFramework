package cn.mythicland.framework.world.v1_8_R3;

import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagList;
import net.minecraft.server.v1_8_R3.RegionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ChunkSaveVerifierTest {
    @TempDir Path world;

    @Test
    void diskNibblesShouldMatchInternalSectionIdsAtNegativeChunkCoordinates() throws Exception {
        Path folder = Files.createDirectories(this.world.resolve("region"));
        byte[] blocks = new byte[4096];
        byte[] data = new byte[2048];
        byte[] high = new byte[2048];
        char[] expected = new char[4096];
        int position = 275;
        int id = 0xabc;
        int metadata = 7;
        blocks[position] = (byte) id;
        data[position >> 1] = (byte) (metadata << 4);
        high[position >> 1] = (byte) ((id >> 8) << 4);
        expected[position] = (char) ((id << 4) | metadata);
        NBTTagCompound section = new NBTTagCompound();
        section.setByte("Y", (byte) 0);
        section.setByteArray("Blocks", blocks);
        section.setByteArray("Data", data);
        section.setByteArray("Add", high);
        NBTTagList sections = new NBTTagList();
        sections.add(section);
        NBTTagCompound level = new NBTTagCompound();
        level.set("Sections", sections);
        level.set("TileEntities", new NBTTagList());
        NBTTagCompound root = new NBTTagCompound();
        root.set("Level", level);
        RegionFile region = new RegionFile(folder.resolve("r.-1.-1.mca").toFile());
        try (DataOutputStream output = region.b(31, 31)) {
            NBTCompressedStreamTools.a(root, (java.io.DataOutput) output);
        } finally {
            region.c();
        }

        assertDoesNotThrow(() -> ChunkSaveVerifier.verify(this.world, -1, -1,
                Map.of(0, expected), Map.of(), Runnable::run).join());
    }
}
