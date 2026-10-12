package cn.mythicland.framework.world.v1_8_R3;

import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class AlphaSchematic {
    private final int width;
    private final int height;
    private final int length;
    private final byte[] blocks;
    private final byte[] data;
    private final byte[] high;
    private final Map<Integer, NBTTagCompound> tiles;

    private AlphaSchematic(NBTTagCompound root) {
        if (!root.getString("Materials").equals("Alpha")) throw new IllegalArgumentException("Expected Alpha schematic");
        if (!root.hasKeyOfType("Width", 2) || !root.hasKeyOfType("Height", 2) || !root.hasKeyOfType("Length", 2)
                || !root.hasKeyOfType("Blocks", 7) || !root.hasKeyOfType("Data", 7)
                || root.hasKey("AddBlocks") && !root.hasKeyOfType("AddBlocks", 7))
            throw new IllegalArgumentException("Invalid schematic NBT fields");
        this.width = root.getShort("Width");
        this.height = root.getShort("Height");
        this.length = root.getShort("Length");
        if (this.width <= 0 || this.width > 256 || this.length <= 0 || this.length > 256
                || this.height <= 0 || this.height > 256) throw new IllegalArgumentException("Invalid schematic dimensions");
        int volume = Math.multiplyExact(Math.multiplyExact(this.width, this.height), this.length);
        this.blocks = root.getByteArray("Blocks");
        this.data = root.getByteArray("Data");
        this.high = root.getByteArray("AddBlocks");
        if (this.blocks.length != volume || this.data.length != volume
                || (this.high.length != 0 && this.high.length != (volume + 1) / 2))
            throw new IllegalArgumentException("Invalid schematic block arrays");
        for (int i = 0; i < volume; i++) {
            if (Block.getById(id(i)) == null || (this.data[i] & 255) > 15)
                throw new IllegalArgumentException("Unsupported schematic block at index " + i);
        }
        Map<Integer, NBTTagCompound> tiles = new HashMap<>();
        if (root.hasKey("TileEntities") && !(root.get("TileEntities") instanceof NBTTagList))
            throw new IllegalArgumentException("Invalid schematic tile entity list");
        NBTTagList list = root.getList("TileEntities", 10);
        if (root.get("TileEntities") instanceof NBTTagList raw && !raw.isEmpty() && raw.f() != 10)
            throw new IllegalArgumentException("Invalid schematic tile entity entries");
        for (int i = 0; i < list.size(); i++) {
            NBTTagCompound tile = (NBTTagCompound) list.get(i).clone();
            if (!tile.hasKeyOfType("x", 3) || !tile.hasKeyOfType("y", 3) || !tile.hasKeyOfType("z", 3)
                    || !tile.hasKeyOfType("id", 8)) throw new IllegalArgumentException("Invalid schematic tile entity fields");
            int x = tile.getInt("x");
            int y = tile.getInt("y");
            int z = tile.getInt("z");
            if (x < 0 || x >= this.width || y < 0 || y >= this.height || z < 0 || z >= this.length)
                throw new IllegalArgumentException("Tile entity outside schematic bounds");
            String original = tile.getString("id");
            String id = switch (original) {
                case "minecraft:chest" -> "Chest";
                case "minecraft:sign" -> "Sign";
                case "minecraft:beacon" -> "Beacon";
                case "minecraft:ender_chest" -> "EnderChest";
                case "minecraft:enchanting_table" -> "EnchantTable";
                case "minecraft:furnace" -> "Furnace";
                case "minecraft:hopper" -> "Hopper";
                case "minecraft:dispenser" -> "Trap";
                case "minecraft:dropper" -> "Dropper";
                case "minecraft:brewing_stand" -> "Cauldron";
                case "minecraft:mob_spawner" -> "MobSpawner";
                case "minecraft:skull" -> "Skull";
                case "minecraft:flower_pot" -> "FlowerPot";
                case "minecraft:command_block" -> "Control";
                case "minecraft:noteblock" -> "Music";
                case "minecraft:piston" -> "Piston";
                case "minecraft:end_portal" -> "Airportal";
                case "minecraft:daylight_detector" -> "DLDetector";
                case "minecraft:comparator" -> "Comparator";
                case "minecraft:banner" -> "Banner";
                case "minecraft:jukebox" -> "RecordPlayer";
                default -> original;
            };
            String expected = tileId(id(index(x, y, z)));
            if (!id.equals(expected)) throw new IllegalArgumentException("Schematic tile entity does not match its block: " + original);
            tile.setString("id", id);
            int index = index(x, y, z);
            if (tiles.put(index, tile) != null) throw new IllegalArgumentException("Duplicate schematic tile entity at " + index);
        }
        this.tiles = Map.copyOf(tiles);
    }

    private static String tileId(int block) {
        return switch (block) {
            case 54, 146 -> "Chest";
            case 63, 68 -> "Sign";
            case 138 -> "Beacon";
            case 130 -> "EnderChest";
            case 116 -> "EnchantTable";
            case 61, 62 -> "Furnace";
            case 154 -> "Hopper";
            case 23 -> "Trap";
            case 158 -> "Dropper";
            case 117 -> "Cauldron";
            case 52 -> "MobSpawner";
            case 144 -> "Skull";
            case 140 -> "FlowerPot";
            case 137 -> "Control";
            case 25 -> "Music";
            case 36 -> "Piston";
            case 119 -> "Airportal";
            case 151, 178 -> "DLDetector";
            case 149, 150 -> "Comparator";
            case 176, 177 -> "Banner";
            case 84 -> "RecordPlayer";
            default -> "";
        };
    }

    public static AlphaSchematic read(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return new AlphaSchematic(NBTCompressedStreamTools.a(input));
        }
    }

    public int width() { return this.width; }
    public int height() { return this.height; }
    public int length() { return this.length; }

    public int index(int x, int y, int z) {
        return (y * this.length + z) * this.width + x;
    }

    public int id(int index) {
        int extra = this.high.length == 0 ? 0 : (this.high[index >> 1] >> ((index & 1) * 4)) & 15;
        return (this.blocks[index] & 255) | (extra << 8);
    }

    public int data(int index) {
        return this.data[index] & 15;
    }

    public NBTTagCompound tile(int x, int y, int z, int originX, int originY, int originZ) {
        NBTTagCompound source = this.tiles.get(index(x, y, z));
        if (source == null) return null;
        NBTTagCompound tile = (NBTTagCompound) source.clone();
        tile.setInt("x", originX + x);
        tile.setInt("y", originY + y);
        tile.setInt("z", originZ + z);
        return tile;
    }
}
