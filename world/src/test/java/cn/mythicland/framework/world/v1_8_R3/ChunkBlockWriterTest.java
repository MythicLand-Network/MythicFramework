package cn.mythicland.framework.world.v1_8_R3;

import net.minecraft.server.v1_8_R3.Blocks;
import net.minecraft.server.v1_8_R3.Chunk;
import net.minecraft.server.v1_8_R3.ChunkRegionLoader;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.ChunkProviderServer;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.DispenserRegistry;
import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.RegionFile;
import net.minecraft.server.v1_8_R3.TileEntityBeacon;
import net.minecraft.server.v1_8_R3.World;
import net.minecraft.server.v1_8_R3.WorldProvider;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftChunk;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.DataOutputStream;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkBlockWriterTest {
    @TempDir
    Path directory;

    @Test
    void saveRetryShouldVerifyUpdatedBeaconDataAfterAnEarlierSubmissionWasLost() throws Exception {
        DispenserRegistry.c();
        WorldServer world = mock(WorldServer.class);
        world.worldProvider = mock(WorldProvider.class);
        world.chunkProviderServer = mock(ChunkProviderServer.class);
        CraftWorld bukkitWorld = mock(CraftWorld.class);
        when(world.getWorld()).thenReturn(bukkitWorld);
        when(bukkitWorld.getHandle()).thenReturn(world);
        when(bukkitWorld.getWorldFolder()).thenReturn(this.directory.toFile());
        Chunk chunk = spy(new Chunk(world, 8, 1));
        doNothing().when(chunk).initLighting();
        CraftChunk bukkitChunk = mock(CraftChunk.class);
        when(bukkitChunk.getHandle()).thenReturn(chunk);
        ChunkBlockWriter writer = new ChunkBlockWriter(bukkitChunk);
        writer.set(132, 98, 24, 138, 0);
        writer.commit();
        TileEntityBeacon beacon = new TileEntityBeacon();
        NBTTagCompound tile = new NBTTagCompound();
        tile.setInt("Levels", 0);
        tile.setInt("x", 132);
        tile.setInt("y", 98);
        tile.setInt("z", 24);
        beacon.a(tile);
        chunk.getTileEntities().put(new BlockPosition(132, 98, 24), beacon);
        var serialize = ChunkRegionLoader.class.getDeclaredMethod("a", Chunk.class, World.class, NBTTagCompound.class);
        serialize.setAccessible(true);
        ChunkRegionLoader loader = new ChunkRegionLoader(this.directory.toFile());
        Path regionPath = Files.createDirectories(this.directory.resolve("region")).resolve("r.0.0.mca");
        doNothing().doAnswer(call -> {
            NBTTagCompound level = new NBTTagCompound();
            serialize.invoke(loader, chunk, world, level);
            NBTTagCompound root = new NBTTagCompound();
            root.set("Level", level);
            RegionFile region = new RegionFile(regionPath.toFile());
            try (DataOutputStream output = region.b(8, 1)) {
                NBTCompressedStreamTools.a(root, (java.io.DataOutput) output);
            } finally {
                region.c();
            }
            return null;
        }).when(world.chunkProviderServer).saveChunk(chunk);
        LinkedBlockingQueue<Runnable> polls = new LinkedBlockingQueue<>();
        var saved = writer.saveVerified(polls::add);
        polls.remove().run();
        assertFalse(saved.isDone());

        tile.setInt("Levels", 4);
        beacon.a(tile);
        writer.requestSave();
        Runnable retry = polls.poll(1, TimeUnit.SECONDS);
        assertNotNull(retry);
        retry.run();
        assertTimeoutPreemptively(Duration.ofSeconds(1), saved::join);
        verify(world.chunkProviderServer, times(2)).saveChunk(chunk);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void editedSectionsShouldSerializeWithDimensionLighting(boolean noSkyLight) throws Exception {
        DispenserRegistry.c();
        World world = mock(WorldServer.class);
        world.worldProvider = mock(WorldProvider.class);
        when(world.worldProvider.o()).thenReturn(noSkyLight);
        var serialize = ChunkRegionLoader.class.getDeclaredMethod("a", Chunk.class, World.class, NBTTagCompound.class);
        serialize.setAccessible(true);
        ChunkRegionLoader loader = new ChunkRegionLoader(this.directory.toFile());
        for (boolean existing : List.of(false, true)) {
            Chunk chunk = spy(new Chunk(world, 0, 0));
            doNothing().when(chunk).initLighting();
            if (existing) {
                ChunkSection previous = new ChunkSection(64, false);
                previous.setType(2, 1, 2, Blocks.STONE.getBlockData());
                previous.getEmittedLightArray().a(2, 1, 2, 7);
                chunk.getSections()[4] = previous;
            }
            CraftChunk bukkitChunk = mock(CraftChunk.class);
            when(bukkitChunk.getHandle()).thenReturn(chunk);
            ChunkBlockWriter writer = new ChunkBlockWriter(bukkitChunk);
            writer.set(1, 65, 1, 2, 0);
            writer.commit();

            NBTTagCompound level = new NBTTagCompound();
            assertDoesNotThrow(() -> serialize.invoke(loader, chunk, world, level));
            ChunkSection edited = chunk.getSections()[4];
            if (noSkyLight) assertNull(edited.getSkyLightArray());
            else assertNotNull(edited.getSkyLightArray());
            if (existing) {
                assertEquals(Blocks.STONE.getBlockData(), edited.getType(2, 1, 2));
                assertEquals(7, edited.getEmittedLightArray().a(2, 1, 2));
            }
            NBTTagCompound saved = level.getList("Sections", 10).get(0);
            assertEquals(2048, saved.getByteArray("SkyLight").length);
            assertEquals(2, saved.getByteArray("Blocks")[(1 << 8) | (1 << 4) | 1]);
        }
    }
}
