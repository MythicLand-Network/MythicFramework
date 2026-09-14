package cn.mythicland.framework.world;

import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.RegionFile;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TerrainRegionFile {
    private TerrainRegionFile() {
    }

    static void sanitize(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        RegionFile input = new RegionFile(source.toFile());
        RegionFile output = new RegionFile(target.toFile());
        try {
            for (int chunkX = 0; chunkX < 32; chunkX++) {
                for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                    try (DataInputStream chunkInput = input.a(chunkX, chunkZ)) {
                        if (chunkInput == null) continue;
                        NBTTagCompound root = readChunk(chunkInput, source, chunkX, chunkZ);
                        root.getCompound("Level").remove("Entities");
                        try (DataOutputStream chunkOutput = output.b(chunkX, chunkZ)) {
                            NBTCompressedStreamTools.a(root, (DataOutput) chunkOutput);
                        }
                    }
                }
            }
        } finally {
            input.c();
            output.c();
        }
    }

    static void validate(Path region) throws IOException {
        RegionFile input = new RegionFile(region.toFile());
        try {
            for (int chunkX = 0; chunkX < 32; chunkX++) {
                for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                    try (DataInputStream chunkInput = input.a(chunkX, chunkZ)) {
                        if (chunkInput == null) continue;
                        NBTTagCompound root = readChunk(chunkInput, region, chunkX, chunkZ);
                        if (root.getCompound("Level").hasKey("Entities")) {
                            throw new IOException("Terrain snapshot contains entities: " + region);
                        }
                    }
                }
            }
        } finally {
            input.c();
        }
    }

    private static NBTTagCompound readChunk(
            DataInputStream input,
            Path region,
            int chunkX,
            int chunkZ
    ) throws IOException {
        NBTTagCompound root = NBTCompressedStreamTools.a(input);
        if (!root.hasKeyOfType("Level", 10)) {
            throw new IOException("Chunk is missing Level data: " + region + " [" + chunkX + ", " + chunkZ + "]");
        }
        return root;
    }
}
