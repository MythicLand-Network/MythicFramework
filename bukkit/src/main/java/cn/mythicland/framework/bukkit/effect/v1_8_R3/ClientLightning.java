package cn.mythicland.framework.bukkit.effect.v1_8_R3;

import net.minecraft.server.v1_8_R3.EntityLightning;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;

public final class ClientLightning {
    private ClientLightning() {
    }

    public static EntityLightning create(Location location) {
        return new EntityLightning(
                ((CraftWorld) location.getWorld()).getHandle(),
                location.getX(), location.getY(), location.getZ(), true, true
        );
    }
}
