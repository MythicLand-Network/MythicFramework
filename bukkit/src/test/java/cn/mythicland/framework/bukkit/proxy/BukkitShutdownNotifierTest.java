package cn.mythicland.framework.bukkit.proxy;

import cn.mythicland.framework.proxy.ServerShutdownNotice;
import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.DedicatedServer;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketPlayOutCustomPayload;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BukkitShutdownNotifierTest {
    @Test
    void onlyRealShutdownSendsNoticeToRegisteredProxyConnections() throws Exception {
        CraftServer server = mock(CraftServer.class);
        DedicatedServer minecraft = mock(DedicatedServer.class);
        when(server.getServer()).thenReturn(minecraft);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            CraftPlayer player = mock(CraftPlayer.class);
            CraftPlayer unregistered = mock(CraftPlayer.class);
            when(player.getListeningPluginChannels()).thenReturn(Set.of(ServerShutdownNotice.CHANNEL));
            when(unregistered.getListeningPluginChannels()).thenReturn(Set.of());
            when(server.getOnlinePlayers()).thenAnswer(call -> List.of(player, unregistered));
            EntityPlayer entity = mock(EntityPlayer.class);
            entity.playerConnection = mock(PlayerConnection.class);
            when(player.getHandle()).thenReturn(entity);

            BukkitShutdownNotifier.notifyProxy(server);
            verifyNoInteractions(entity.playerConnection);
            verify(server, never()).getOnlinePlayers();

            var stopped = MinecraftServer.class.getDeclaredField("hasStopped");
            stopped.setAccessible(true);
            stopped.setBoolean(minecraft, true);
            BukkitShutdownNotifier.notifyProxy(server);
            ArgumentCaptor<PacketPlayOutCustomPayload> packet =
                    ArgumentCaptor.forClass(PacketPlayOutCustomPayload.class);
            verify(entity.playerConnection).sendPacket(packet.capture());
            verify(unregistered, never()).getHandle();
            PacketDataSerializer encoded = new PacketDataSerializer(Unpooled.buffer());
            try {
                packet.getValue().b(encoded);
                assertEquals(ServerShutdownNotice.CHANNEL, encoded.c(20));
                byte[] payload = new byte[encoded.readableBytes()];
                encoded.readBytes(payload);
                assertTrue(ServerShutdownNotice.matches(payload));
            } finally {
                encoded.release();
            }
        }
    }
}
