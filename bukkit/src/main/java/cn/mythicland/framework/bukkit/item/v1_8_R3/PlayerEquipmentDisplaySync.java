package cn.mythicland.framework.bukkit.item.v1_8_R3;

import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEquipment;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class PlayerEquipmentDisplaySync {
    private PlayerEquipmentDisplaySync() {
    }

    public static void syncToViewers(Player player) {
        if (!player.isOnline()) return;
        EntityPlayer handle = ((CraftPlayer) player).getHandle();
        for (int slot = 0; slot < 5; slot++) {
            handle.u().getTracker().a(
                    handle,
                    new PacketPlayOutEntityEquipment(handle.getId(), slot, handle.getEquipment(slot))
            );
        }
    }
}
