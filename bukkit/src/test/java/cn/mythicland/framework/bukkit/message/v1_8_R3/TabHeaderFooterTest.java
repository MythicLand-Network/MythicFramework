package cn.mythicland.framework.bukkit.message.v1_8_R3;

import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerListHeaderFooter;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TabHeaderFooterTest {
    @BeforeAll
    static void initializeServerTypes() throws Exception {
        Server server = mock(Server.class);
        PluginManager permissions = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(permissions);
        when(permissions.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            Class.forName("org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity");
        }
    }

    @Test
    void sendsBothLinesAndClearsNullValues() throws Exception {
        CraftPlayer player = mock(CraftPlayer.class);
        EntityPlayer handle = mock(EntityPlayer.class);
        PlayerConnection connection = mock(PlayerConnection.class);
        handle.playerConnection = connection;
        when(player.getHandle()).thenReturn(handle);

        TabHeaderFooter.send(player, "§aHeader\nsecond", "§bFooter");
        TabHeaderFooter.send(player, null, null);

        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(connection, times(2)).sendPacket(packets.capture());
        assertEquals("§aHeader\nsecond", text(packets.getAllValues().getFirst(), "a"));
        assertEquals("§bFooter", text(packets.getAllValues().getFirst(), "b"));
        assertEquals("", text(packets.getAllValues().getLast(), "a"));
        assertEquals("", text(packets.getAllValues().getLast(), "b"));
    }

    private String text(Packet packet, String name) throws Exception {
        Field field = PacketPlayOutPlayerListHeaderFooter.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((IChatBaseComponent) field.get(packet)).getText();
    }
}
