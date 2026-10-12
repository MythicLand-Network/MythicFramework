package cn.mythicland.framework.world.v1_8_R3;

import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagList;
import net.minecraft.server.v1_8_R3.RegionFile;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class ChunkSaveVerifier {
    private ChunkSaveVerifier() {
    }

    static CompletableFuture<Void> verify(
            Path world,
            int x,
            int z,
            Map<Integer, char[]> sections,
            Map<BlockPosition, NBTTagCompound> tiles,
            Executor executor
    ) {
        BitSet positions = new BitSet(65536);
        positions.set(0, 65536);
        return verify(world, x, z, sections, tiles, positions, executor);
    }

    static CompletableFuture<Void> verify(
            Path world,
            int x,
            int z,
            Map<Integer, char[]> sections,
            Map<BlockPosition, NBTTagCompound> tiles,
            BitSet positions,
            Executor executor
    ) {
        return verify(world, x, z, () -> new Snapshot(sections, tiles), positions, executor);
    }

    static CompletableFuture<Void> verify(
            Path world,
            int x,
            int z,
            Supplier<Snapshot> snapshot,
            BitSet positions,
            Executor executor
    ) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Path region = world.resolve("region/r." + (x >> 5) + "." + (z >> 5) + ".mca");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        executor.execute(() -> poll(region, x, z, snapshot, positions, executor, deadline, result));
        return result;
    }

    private static void poll(
            Path file,
            int x,
            int z,
            Supplier<Snapshot> snapshot,
            BitSet positions,
            Executor executor,
            long deadline,
            CompletableFuture<Void> result
    ) {
        if (result.isDone()) return;
        IOException failure = null;
        try {
            Snapshot expected = snapshot.get();
            if (matches(file, x, z, expected.sections(), expected.tiles(), positions)) {
                result.complete(null);
                return;
            }
        } catch (IOException exception) {
            failure = exception;
        }
        if (System.nanoTime() >= deadline) {
            result.completeExceptionally(new IOException("Chunk save was not acknowledged: " + file + " [" + x + ", " + z + "]", failure));
            return;
        }
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS, executor).execute(
                () -> poll(file, x, z, snapshot, positions, executor, deadline, result));
    }

    record Snapshot(Map<Integer, char[]> sections, Map<BlockPosition, NBTTagCompound> tiles) {}

    private static boolean matches(
            Path file,
            int x,
            int z,
            Map<Integer, char[]> expected,
            Map<BlockPosition, NBTTagCompound> expectedTiles,
            BitSet positions
    ) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        RegionFile region = new RegionFile(file.toFile());
        try (DataInputStream input = region.a(x & 31, z & 31)) {
            if (input == null) return false;
            NBTTagCompound level = NBTCompressedStreamTools.a(input).getCompound("Level");
            Map<Integer, NBTTagCompound> sections = new HashMap<>();
            NBTTagList list = level.getList("Sections", 10);
            for (int i = 0; i < list.size(); i++) sections.put((int) list.get(i).getByte("Y"), list.get(i));
            for (Map.Entry<Integer, char[]> entry : expected.entrySet()) {
                NBTTagCompound section = sections.get(entry.getKey());
                byte[] blocks = section == null ? new byte[4096] : section.getByteArray("Blocks");
                byte[] data = section == null ? new byte[2048] : section.getByteArray("Data");
                byte[] high = section == null ? new byte[0] : section.getByteArray("Add");
                if (blocks.length != 4096 || data.length != 2048 || (high.length != 0 && high.length != 2048)) return false;
                for (int i = 0; i < 4096; i++) {
                    if (!positions.get((entry.getKey() << 12) + i)) continue;
                    int shift = (i & 1) * 4;
                    int id = (blocks[i] & 255) | (high.length == 0 ? 0 : ((high[i >> 1] >> shift) & 15) << 8);
                    int value = (id << 4) | ((data[i >> 1] >> shift) & 15);
                    if (value != entry.getValue()[i]) return false;
                }
            }
            Map<BlockPosition, NBTTagCompound> tiles = new HashMap<>();
            NBTTagList tileList = level.getList("TileEntities", 10);
            for (int i = 0; i < tileList.size(); i++) {
                NBTTagCompound tile = tileList.get(i);
                BlockPosition position = new BlockPosition(tile.getInt("x"), tile.getInt("y"), tile.getInt("z"));
                if (positions.get((position.getY() << 8) | ((position.getZ() & 15) << 4) | (position.getX() & 15))) tiles.put(position, tile);
            }
            return tiles.equals(expectedTiles);
        } finally {
            region.c();
        }
    }
}
