package cn.mythicland.framework.bukkit.world.v1_12_R1;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Offline Anvil snapshot operations for Minecraft 1.12.2. */
public final class LegacyRegionSnapshots {

    private LegacyRegionSnapshots() {
    }

    /**
     * Copies a closed region file, excluding living creatures and living passengers.
     * Armor stands, other nonliving entities, terrain and block entities are preserved.
     * The caller must keep the source stable while copying and must not load the destination.
     * No Bukkit state is accessed; this method may run on an I/O worker.
     *
     * @param source stable source region file
     * @param target distinct destination file
     * @throws IOException if the file cannot be copied or a chunk cannot be decoded or written
     */
    public static void copyWithoutCreatures(Path source, Path target) throws IOException {
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Snapshot source and target must be distinct: " + source);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        try {
            NbtAccess nbt = new NbtAccess();
            Object region = nbt.regionConstructor.newInstance(target.toFile());
            try {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        Object chunk;
                        try (DataInputStream input = (DataInputStream) nbt.readStream.invoke(region, x, z)) {
                            if (input == null) continue;
                            chunk = nbt.readNbt.invoke(null, input);
                        }
                        Object level = nbt.getCompound.invoke(chunk, "Level");
                        Object entities = nbt.getList.invoke(level, "Entities", 10);
                        if (!nbt.removeCreatures(entities)) continue;
                        try (DataOutputStream output = (DataOutputStream) nbt.writeStream.invoke(region, x, z)) {
                            nbt.writeNbt.invoke(null, chunk, output);
                        }
                    }
                }
            } finally {
                nbt.closeRegion.invoke(region);
            }
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IOException ioException) throw ioException;
            throw new IOException("Cannot filter region snapshot: " + source, exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Cannot access Minecraft 1.12.2 region format: " + source, exception);
        }
    }

    private static final class NbtAccess {
        private final Constructor<?> regionConstructor;
        private final Method readStream;
        private final Method writeStream;
        private final Method closeRegion;
        private final Method readNbt;
        private final Method writeNbt;
        private final Method getCompound;
        private final Method getList;
        private final Method getString;
        private final Method hasKeyOfType;
        private final Method listSize;
        private final Method listGet;
        private final Method listRemove;

        private NbtAccess() throws ReflectiveOperationException {
            String prefix = "net.minecraft.server.v1_12_R1.";
            Class<?> region = Class.forName(prefix + "RegionFile");
            Class<?> compound = Class.forName(prefix + "NBTTagCompound");
            Class<?> list = Class.forName(prefix + "NBTTagList");
            Class<?> streams = Class.forName(prefix + "NBTCompressedStreamTools");
            regionConstructor = region.getConstructor(File.class);
            readStream = region.getMethod("a", int.class, int.class);
            writeStream = region.getMethod("b", int.class, int.class);
            closeRegion = region.getMethod("c");
            readNbt = streams.getMethod("a", DataInputStream.class);
            writeNbt = streams.getMethod("a", compound, DataOutput.class);
            getCompound = compound.getMethod("getCompound", String.class);
            getList = compound.getMethod("getList", String.class, int.class);
            getString = compound.getMethod("getString", String.class);
            hasKeyOfType = compound.getMethod("hasKeyOfType", String.class, int.class);
            listSize = list.getMethod("size");
            listGet = list.getMethod("get", int.class);
            listRemove = list.getMethod("remove", int.class);
        }

        private boolean removeCreatures(Object entities) throws ReflectiveOperationException {
            boolean changed = false;
            for (int index = (int) listSize.invoke(entities) - 1; index >= 0; index--) {
                Object entity = listGet.invoke(entities, index);
                String type = (String) getString.invoke(entity, "id");
                boolean armorStand = type.equals("minecraft:armor_stand") || type.equals("ArmorStand");
                if ((boolean) hasKeyOfType.invoke(entity, "Health", 99) && !armorStand) {
                    listRemove.invoke(entities, index);
                    changed = true;
                } else if (removeCreatures(getList.invoke(entity, "Passengers", 10))) {
                    changed = true;
                }
            }
            return changed;
        }
    }
}
