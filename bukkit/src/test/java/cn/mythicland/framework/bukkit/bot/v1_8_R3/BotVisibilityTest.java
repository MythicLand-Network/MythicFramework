package cn.mythicland.framework.bukkit.bot.v1_8_R3;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotVisibilityTest {
    @Test
    void visibleViewersShareMovementPacketsAndCombatVisibilityStillRespectsDistance() {
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.plugin.PluginManager permissions = mock(org.bukkit.plugin.PluginManager.class);
        when(server.getPluginManager()).thenReturn(permissions);
        when(permissions.getDefaultPermissions(anyBoolean())).thenReturn(Set.of());
        try (var bukkit = mockStatic(Bukkit.class);
             var info = mockConstruction(PacketPlayOutPlayerInfo.class);
             var spawn = mockConstruction(PacketPlayOutNamedEntitySpawn.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            World world = mock(World.class);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            EntityPlayer bot = mock(EntityPlayer.class);
            bot.locY = 64;
            bot.locZ = 10;
            when(bot.getHeadHeight()).thenReturn(1.62F);
            UUID botId = UUID.randomUUID();
            when(bot.getUniqueID()).thenReturn(botId);
            when(bot.getProfile()).thenReturn(new GameProfile(botId, "bot"));
            when(bot.getDataWatcher()).thenReturn(mock(DataWatcher.class));
            CraftPlayer body = mock(CraftPlayer.class);
            when(bot.getBukkitEntity()).thenReturn(body);
            when(body.getWorld()).thenReturn(world);
            when(body.getLocation()).thenAnswer(
                    call -> new Location(world, bot.locX, bot.locY, bot.locZ, bot.yaw, bot.pitch));
            List<Player> viewers = new ArrayList<>();
            List<Location> eyes = new ArrayList<>();
            List<List<Packet<?>>> sent = new ArrayList<>();
            Location eye = new Location(world, 0, 65.62, 0);
            for (int index = 0; index < 2; index++) {
                CraftPlayer viewer = mock(CraftPlayer.class);
                when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
                when(viewer.getWorld()).thenReturn(world);
                when(viewer.canSee(body)).thenReturn(true);
                when(viewer.hasLineOfSight(body)).thenReturn(true);
                Location viewerEye = eye.clone();
                eyes.add(viewerEye);
                when(viewer.getLocation()).thenAnswer(call -> viewerEye.clone());
                when(viewer.getEyeLocation()).thenAnswer(call -> viewerEye.clone());
                EntityPlayer handle = mock(EntityPlayer.class);
                handle.playerConnection = mock(PlayerConnection.class);
                when(viewer.getHandle()).thenReturn(handle);
                List<Packet<?>> packets = new ArrayList<>();
                doAnswer(call -> {
                    packets.add(call.getArgument(0));
                    return null;
                })
                        .when(handle.playerConnection).sendPacket(any());
                viewers.add(viewer);
                sent.add(packets);
                bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
            }
            BotVisibility visibility = spy(new BotVisibility());
            BotVisibilitySettings settings = new Settings(32, 30, 128, 0, 70, 10, 2, 0, 5);
            visibility.activity(viewers, 0);
            visibility.update(bot, viewers, 0, null, Set.of(), settings, null, false);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            for (Class<?> packet : new Class<?>[]{PacketPlayOutEntityTeleport.class,
                    PacketPlayOutEntityHeadRotation.class}) {
                assertSame(sent.getFirst().stream().filter(packet::isInstance).findFirst().orElseThrow(),
                        sent.getLast().stream().filter(packet::isInstance).findFirst().orElseThrow());
            }
            for (int index = 0; index < viewers.size(); index++) {
                eyes.get(index).setYaw(180);
                when(viewers.get(index).hasLineOfSight(body)).thenReturn(false);
            }
            visibility.activity(viewers, 10);
            visibility.update(bot, viewers, 10, null, Set.of(), settings, null, false);
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            verify(visibility, never()).onIdleHidden(any());
            visibility.update(bot, viewers, 11, viewers.getFirst().getUniqueId(), Set.of(), settings, null, false);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            for (int index = 0; index < viewers.size(); index++) {
                eyes.get(index).setYaw(0);
                when(viewers.get(index).hasLineOfSight(body)).thenReturn(true);
            }
            visibility.activity(viewers, 20);
            visibility.update(bot, viewers, 20, null, Set.of(), settings, null, false);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertTrue(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            visibility.activity(viewers, 620);
            visibility.update(bot, viewers, 620, null, Set.of(), settings, null, false);
            verify(visibility).onIdleHidden(viewers.getFirst());
            verify(visibility).onIdleHidden(viewers.getLast());
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            eyes.getFirst().setYaw(1);
            visibility.activity(viewers, 621);
            visibility.update(bot, viewers, 621, null, Set.of(), settings, null, false);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            visibility.update(bot, viewers, 622, viewers.getLast().getUniqueId(), Set.of(), settings, null, false);
            assertTrue(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            bot.locZ = 40;
            clearInvocations(visibility);
            visibility.update(bot, viewers, 640, viewers.getFirst().getUniqueId(), Set.of(), settings, null, false);
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            assertFalse(visibility.isVisible(viewers.getLast().getUniqueId(), botId));
            verify(visibility, never()).onIdleHidden(any());
            bot.locZ = 10;
            visibility.restoreIdle(viewers.getFirst(), 660, 600);
            visibility.update(bot, viewers, 660, null, Set.of(), settings, null, false);
            assertFalse(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            verify(visibility).onIdleHidden(viewers.getFirst());
            eyes.getFirst().setYaw(2);
            visibility.activity(viewers, 661);
            visibility.update(bot, viewers, 661, null, Set.of(), settings, null, false);
            assertTrue(visibility.isVisible(viewers.getFirst().getUniqueId(), botId));
            visibility.destroy(bot);
            visibility.clear();
        }
    }

    private record Settings(
            double viewDistance,
            double idleSeconds,
            double maxViewerY,
            double nearbyDistance,
            double viewAngle,
            int visibilityCheckTicks,
            int maxRevealsPerTick,
            int spawnDelayTicks,
            int equipmentCheckTicks
    ) implements BotVisibilitySettings {
    }
}
