package cn.mythicland.framework.world;

import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

public final class VoidChunkGenerator extends ChunkGenerator {
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0, 64, 0);
    }

    @Override
    public byte[][] generateBlockSections(World world, Random random, int x, int z, BiomeGrid biomes) {
        return new byte[world.getMaxHeight() >> 4][];
    }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        return false;
    }
}
