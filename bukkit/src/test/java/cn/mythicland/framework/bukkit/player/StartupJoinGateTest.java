package cn.mythicland.framework.bukkit.player;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupJoinGateTest {
    @Test
    void rejectsBothLoginStagesUntilReadinessSucceeds() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        try (StartupJoinGate gate = gate(ready)) {
            assertRejected(gate);
            ready.complete(null);
            assertTrue(gate.isAcceptingJoins());
            var preLogin = preLogin();
            var login = login();
            gate.onAsyncPreLogin(preLogin);
            gate.onLogin(login);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());
            assertEquals(PlayerLoginEvent.Result.ALLOWED, login.getResult());
            login.disallow(PlayerLoginEvent.Result.KICK_BANNED, "banned");
            gate.onLogin(login);
            assertEquals(PlayerLoginEvent.Result.KICK_BANNED, login.getResult());
        }
    }

    @Test
    void failedOrCancelledStartupNeverAdmitsPlayers() {
        CompletableFuture<Void> failed = CompletableFuture.failedFuture(new IllegalStateException("World restore failed"));
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        for (CompletableFuture<Void> ready : List.of(failed, cancelled)) {
            try (StartupJoinGate gate = gate(ready)) {
                assertRejected(gate);
            }
        }
    }

    @Test
    void lateCompletionCannotReopenAClosedGate() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        StartupJoinGate gate = gate(ready);
        gate.close();
        ready.complete(null);
        gate.close();
        assertFalse(gate.isAcceptingJoins());
    }

    private static StartupJoinGate gate(CompletableFuture<Void> ready) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        return new StartupJoinGate(plugin, "Server is starting", ready);
    }

    private static AsyncPlayerPreLoginEvent preLogin() {
        return new AsyncPlayerPreLoginEvent("StartupProbe", InetAddress.getLoopbackAddress(), UUID.randomUUID());
    }

    private static PlayerLoginEvent login() {
        return new PlayerLoginEvent(mock(Player.class), "localhost", InetAddress.getLoopbackAddress());
    }

    private static void assertRejected(StartupJoinGate gate) {
        var preLogin = preLogin();
        var login = login();
        gate.onAsyncPreLogin(preLogin);
        gate.onLogin(login);
        assertFalse(gate.isAcceptingJoins());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, preLogin.getLoginResult());
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login.getResult());
        assertEquals("Server is starting", preLogin.getKickMessage());
        assertEquals("Server is starting", login.getKickMessage());
    }
}
