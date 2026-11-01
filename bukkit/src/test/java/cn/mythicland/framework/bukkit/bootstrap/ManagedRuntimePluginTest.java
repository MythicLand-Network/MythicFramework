package cn.mythicland.framework.bukkit.bootstrap;

import cn.mythicland.framework.runtime.RuntimeBootstrap;

import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.*;

class ManagedRuntimePluginTest {
    @Test
    void loadsEnablesReloadsAndClosesTheSameRuntime() {
        ManagedRuntimePlugin plugin = mock(ManagedRuntimePlugin.class, CALLS_REAL_METHODS);
        RuntimeBootstrap runtime = mock(RuntimeBootstrap.class);
        doReturn(runtime).when(plugin).createRuntime();
        doReturn(true).when(plugin).prepareRuntimeOnLoad();

        plugin.onLoad();
        plugin.onEnable();
        plugin.reloadRuntime();
        plugin.onDisable();
        plugin.onDisable();

        InOrder order = inOrder(runtime);
        order.verify(runtime).load();
        order.verify(runtime).enable();
        order.verify(runtime).reload();
        order.verify(runtime).disable();
        verifyNoMoreInteractions(runtime);
        verify(plugin, times(1)).createRuntime();
    }

    @Test
    void disablesThePluginWhenRuntimeEnableFails() {
        ManagedRuntimePlugin plugin = mock(ManagedRuntimePlugin.class, CALLS_REAL_METHODS);
        RuntimeBootstrap runtime = mock(RuntimeBootstrap.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        doReturn(runtime).when(plugin).createRuntime();
        doReturn(server).when(plugin).getServer();
        doReturn(manager).when(server).getPluginManager();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        doReturn("Test").when(plugin).getName();
        doThrow(new IllegalStateException("startup failed")).when(runtime).enable();
        doAnswer(invocation -> {
            plugin.onDisable();
            return null;
        }).when(manager).disablePlugin(plugin);

        plugin.onEnable();

        verify(manager).disablePlugin(plugin);
        verify(runtime).disable();
    }

    @Test
    void prerequisiteFailureDoesNotCreateARuntime() {
        ManagedRuntimePlugin plugin = mock(ManagedRuntimePlugin.class, CALLS_REAL_METHODS);
        doReturn(false).when(plugin).canEnableRuntime();

        plugin.onEnable();

        verify(plugin, never()).createRuntime();
    }
}
