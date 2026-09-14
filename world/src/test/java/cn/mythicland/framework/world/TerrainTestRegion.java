package cn.mythicland.framework.world;

import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagList;
import net.minecraft.server.v1_8_R3.RegionFile;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TerrainTestRegion {
    private TerrainTestRegion() {
    }

    static Path write(Path directory, String marker, boolean includeEntity) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("r.0.0.mca");
        RegionFile region = new RegionFile(path.toFile());
        try (DataOutputStream output = region.b(0, 0)) {
            NBTTagCompound level = new NBTTagCompound();
            level.setString("Marker", marker);
            NBTTagList entities = new NBTTagList();
            if (includeEntity) {
                NBTTagCompound entity = new NBTTagCompound();
                entity.setString("id", "Pig");
                entities.add(entity);
            }
            level.set("Entities", entities);
            NBTTagCompound chest = new NBTTagCompound();
            chest.setString("id", "Chest");
            NBTTagList tileEntities = new NBTTagList();
            tileEntities.add(chest);
            level.set("TileEntities", tileEntities);
            NBTTagCompound root = new NBTTagCompound();
            root.set("Level", level);
            NBTCompressedStreamTools.a(root, (DataOutput) output);
        } finally {
            region.c();
        }
        return path;
    }

    static NBTTagCompound read(Path path) throws IOException {
        RegionFile region = new RegionFile(path.toFile());
        try (DataInputStream input = region.a(0, 0)) {
            if (input == null) throw new IOException("Test region has no chunk");
            return NBTCompressedStreamTools.a(input);
        } finally {
            region.c();
        }
    }
}
