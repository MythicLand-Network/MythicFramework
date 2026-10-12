package cn.mythicland.framework.world.v1_8_R3;

import cn.mythicland.framework.bukkit.task.TaskScheduler;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.PacketPlayOutBlockAction;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ChestAnimations {
    private final TaskScheduler scheduler;
    private final Map<BlockKey, Integer> viewers = new HashMap<>();

    public ChestAnimations(TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public int open(Location location) {
        int count = this.viewers.merge(BlockKey.at(location), 1, Integer::sum);
        animate(location, count);
        return count;
    }

    public int close(Location location) {
        int count = decrement(location);
        BlockKey key = BlockKey.at(location);
        this.scheduler.executeSyncLater(() -> animate(location, this.viewers.getOrDefault(key, 0)), 1L);
        return count;
    }

    public void closeNow(Location location) {
        animate(location, decrement(location));
    }

    private int decrement(Location location) {
        Integer count = this.viewers.compute(BlockKey.at(location), (key, current) ->
                current == null || current <= 1 ? null : current - 1);
        return count == null ? 0 : count;
    }

    private static void animate(Location location, int count) {
        World world = location.getWorld();
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Material material = location.getBlock().getType();
        if (material != Material.CHEST && material != Material.TRAPPED_CHEST && material != Material.ENDER_CHEST) return;
        WorldServer handle = ((CraftWorld) world).getHandle();
        BlockPosition position = new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        PacketPlayOutBlockAction packet = new PacketPlayOutBlockAction(position, handle.getType(position).getBlock(), 1, count);
        Location center = location.clone().add(.5, .5, .5);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) > 64 * 64) continue;
            ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
        }
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        private static BlockKey at(Location location) {
            return new BlockKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
