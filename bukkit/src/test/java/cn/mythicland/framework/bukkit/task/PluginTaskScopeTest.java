package cn.mythicland.framework.bukkit.task;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginTaskScopeTest {
    @Test
    void closesOutstandingTasksAndKeepsCompletedTasksOutOfTheScope() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask completed = mock(BukkitTask.class);
        BukkitTask repeating = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L))).thenReturn(completed);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(2L))).thenReturn(repeating);

        PluginTaskScope scope = new PluginTaskScope(plugin);
        Runnable action = mock(Runnable.class);
        scope.runLater(1L, action);
        scope.runTimer(1L, 2L, () -> {});
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(eq(plugin), callback.capture(), eq(1L));
        callback.getValue().run();
        scope.close();
        scope.close();

        verify(action).run();
        verify(completed, never()).cancel();
        verify(repeating).cancel();
        assertThrows(IllegalStateException.class, () -> scope.runLater(1L, action));
    }
}
