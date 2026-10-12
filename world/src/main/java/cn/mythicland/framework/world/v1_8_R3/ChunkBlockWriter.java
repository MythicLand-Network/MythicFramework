package cn.mythicland.framework.world.v1_8_R3;

import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.Chunk;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.PacketPlayOutMapChunk;
import net.minecraft.server.v1_8_R3.Packet;
import org.bukkit.World;
import net.minecraft.server.v1_8_R3.TileEntity;
import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import cn.mythicland.framework.world.v1_8_R3.SchematicWorkQueue.BlockChange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import org.bukkit.craftbukkit.v1_8_R3.CraftChunk;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.BitSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkBlockWriter {
    private final Chunk chunk;
    private final ChunkSection[] sections;
    private final BitSet positions = new BitSet(65536);
    private int sectionMask;
    private AtomicReference<ChunkSaveVerifier.Snapshot> saveSnapshot;

    public ChunkBlockWriter(org.bukkit.Chunk chunk) {
        this.chunk = ((CraftChunk) chunk).getHandle();
        this.sections = this.chunk.getSections().clone();
    }

    public static BlockChange capture(World world, int x, int y, int z) {
        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
        TileEntity entity = ((CraftWorld) world).getHandle().getTileEntity(new BlockPosition(x, y, z));
        NBTTagCompound tile = null;
        if (entity != null) {
            tile = new NBTTagCompound();
            entity.b(tile);
        }
        return new BlockChange(x, y, z, block.getTypeId(), block.getData(), tile);
    }

    public static String encodeTile(BlockChange change) {
        if (change.tile() == null) return "";
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NBTCompressedStreamTools.a(change.tile(), output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static BlockChange decodeBlock(int x, int y, int z, int id, int data, String tile) {
        try {
            return new BlockChange(x, y, z, id, data, tile.isEmpty() ? null
                    : NBTCompressedStreamTools.a(new ByteArrayInputStream(Base64.getDecoder().decode(tile))));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public int chunkX() {
        return this.chunk.locX;
    }

    public int chunkZ() {
        return this.chunk.locZ;
    }

    public void set(int x, int y, int z, int id, int data) {
        int index = y >> 4;
        if ((this.sectionMask & (1 << index)) == 0) {
            ChunkSection old = this.sections[index];
            char[] blocks = old == null ? new char[4096] : old.getIdArray().clone();
            this.sections[index] = new ChunkSection(index << 4, !this.chunk.world.worldProvider.o(), blocks);
            if (old != null) {
                this.sections[index].a(old.getEmittedLightArray());
                if (old.getSkyLightArray() != null) this.sections[index].b(old.getSkyLightArray());
            }
            this.sectionMask |= 1 << index;
        }
        this.sections[index].setType(x & 15, y & 15, z & 15, Block.getByCombinedId(id | (data << 12)));
        this.positions.set((y << 8) | ((z & 15) << 4) | (x & 15));
    }

    public void commit() {
        List<BlockPosition> removed = this.chunk.getTileEntities().keySet().stream()
                .filter(position -> this.positions.get((position.getY() << 8)
                        | ((position.getZ() & 15) << 4) | (position.getX() & 15)))
                .toList();
        for (BlockPosition position : removed) this.chunk.world.t(position);
        for (int index = 0; index < this.sections.length; index++) {
            ChunkSection current = this.chunk.getSections()[index];
            if ((this.sectionMask & (1 << index)) == 0) {
                this.sections[index] = current;
                continue;
            }
            char[] edited = this.sections[index].getIdArray();
            char[] merged = current == null ? new char[4096] : current.getIdArray().clone();
            int start = index << 12;
            for (int position = this.positions.nextSetBit(start); position >= start && position < start + 4096;
                 position = this.positions.nextSetBit(position + 1)) merged[position - start] = edited[position - start];
            this.sections[index] = new ChunkSection(index << 4, !this.chunk.world.worldProvider.o(), merged);
            if (current != null) {
                this.sections[index].a(current.getEmittedLightArray());
                if (current.getSkyLightArray() != null) this.sections[index].b(current.getSkyLightArray());
            }
            this.sections[index].recalcBlockCounts();
        }
        this.chunk.a(this.sections);
        this.chunk.initLighting();
        this.chunk.mustSave = true;
        for (int position = this.positions.nextSetBit(0); position >= 0; position = this.positions.nextSetBit(position + 1)) {
            BlockPosition block = new BlockPosition((this.chunk.locX << 4) + (position & 15),
                    position >> 8, (this.chunk.locZ << 4) + ((position >> 4) & 15));
            if (this.chunk.getType(block).isTileEntity()) this.chunk.world.getTileEntity(block);
        }
    }

    public void restoreTile(NBTTagCompound compound) {
        TileEntity tile = TileEntity.c(compound);
        if (tile == null) throw new IllegalArgumentException("Unsupported tile entity: " + compound.getString("id"));
        this.chunk.world.setTileEntity(new BlockPosition(compound.getInt("x"), compound.getInt("y"), compound.getInt("z")), tile);
    }

    public void refresh(Player player) {
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(
                new PacketPlayOutMapChunk(this.chunk, false, this.sectionMask));
        for (TileEntity tile : this.chunk.getTileEntities().values()) {
            Packet<?> packet = tile.getUpdatePacket();
            if (packet != null) ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
        }
    }

    public void requestSave() {
        if (this.saveSnapshot != null) this.saveSnapshot.set(captureSave());
        ((CraftWorld) this.chunk.world.getWorld()).getHandle().chunkProviderServer.saveChunk(this.chunk);
    }

    public CompletableFuture<Void> saveVerified(Executor executor) {
        this.saveSnapshot = new AtomicReference<>();
        Path folder = this.chunk.world.getWorld().getWorldFolder().toPath();
        requestSave();
        return ChunkSaveVerifier.verify(folder, this.chunk.locX, this.chunk.locZ,
                this.saveSnapshot::get, (BitSet) this.positions.clone(), executor);
    }

    private ChunkSaveVerifier.Snapshot captureSave() {
        Map<Integer, char[]> expected = new HashMap<>();
        for (int index = 0; index < this.sections.length; index++) {
            if ((this.sectionMask & (1 << index)) == 0) continue;
            ChunkSection section = this.chunk.getSections()[index];
            expected.put(index, section == null ? new char[4096] : section.getIdArray().clone());
        }
        Map<BlockPosition, NBTTagCompound> tiles = new HashMap<>();
        for (Map.Entry<BlockPosition, TileEntity> entry : this.chunk.getTileEntities().entrySet()) {
            BlockPosition position = entry.getKey();
            if (!this.positions.get((position.getY() << 8) | ((position.getZ() & 15) << 4) | (position.getX() & 15))) continue;
            NBTTagCompound tile = new NBTTagCompound();
            entry.getValue().b(tile);
            tiles.put(entry.getKey(), tile);
        }
        return new ChunkSaveVerifier.Snapshot(expected, tiles);
    }
}
