package cn.mythicland.framework.bukkit.bot.v1_8_R3;

import net.minecraft.server.v1_8_R3.PlayerList;
import net.minecraft.server.v1_8_R3.ServerStatisticManager;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BotEntityTest {
    @Test
    void repeatedBotSpawnsDoNotRetainStatisticsOrRemovePlayerStatistics() throws Exception {
        PlayerList players = mock(PlayerList.class);
        Map<UUID, ServerStatisticManager> cached = new HashMap<>();
        var field = PlayerList.class.getDeclaredField("o");
        field.setAccessible(true);
        field.set(players, cached);
        UUID playerId = UUID.randomUUID();
        ServerStatisticManager player = mock(ServerStatisticManager.class);
        ServerStatisticManager bot = mock(ServerStatisticManager.class);
        cached.put(playerId, player);
        for (int spawn = 0; spawn < 1000; spawn++) {
            UUID botId = UUID.randomUUID();
            cached.put(botId, bot);
            BotEntity.releaseStatistics(players, botId, bot);
        }
        assertEquals(Map.of(playerId, player), cached);
        BotEntity.releaseStatistics(players, playerId, bot);
        assertSame(player, cached.get(playerId));
    }
}
