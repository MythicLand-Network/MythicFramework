package cn.mythicland.framework.bukkit.message.v1_8_R3;

import net.minecraft.server.v1_8_R3.ChatComponentText;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerListHeaderFooter;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;

public final class TabHeaderFooter {
    private static final Field FOOTER = footerField();

    private TabHeaderFooter() {
    }

    public static void send(Player player, String header, String footer) {
        PacketPlayOutPlayerListHeaderFooter packet = new PacketPlayOutPlayerListHeaderFooter(
                new ChatComponentText(header == null ? "" : header));
        try {
            FOOTER.set(packet, new ChatComponentText(footer == null ? "" : footer));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Failed to set tab footer", exception);
        }
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
    }

    private static Field footerField() {
        try {
            Field field = PacketPlayOutPlayerListHeaderFooter.class.getDeclaredField("b");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Failed to resolve tab footer field", exception);
        }
    }
}
