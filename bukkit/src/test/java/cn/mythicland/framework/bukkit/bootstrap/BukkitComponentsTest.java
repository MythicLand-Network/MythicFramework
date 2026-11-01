package cn.mythicland.framework.bukkit.bootstrap;

import cn.mythicland.framework.bukkit.command.CommandRouteCompiler;
import cn.mythicland.framework.bukkit.menu.MenuManager;
import cn.mythicland.framework.bukkit.task.ScheduledTaskLifecycle;
import cn.mythicland.framework.bukkit.task.TaskScheduler;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.framework.component.ComponentResolver;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static cn.mythicland.framework.bukkit.bootstrap.BukkitComponents.Capability.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BukkitComponentsTest {
    @Test
    void providesRequestedCapabilitiesWithOneContainer() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        ComponentContainer components = BukkitComponents.create(plugin, COMMANDS, MENUS, SCHEDULED_TASKS);

        assertSame(plugin, components.resolve(JavaPlugin.class));
        assertSame(components, components.resolve(ComponentResolver.class));
        assertSame(components, components.resolve(ComponentContainer.class));
        assertNotNull(components.resolve(CommandRouteCompiler.class));
        assertNotNull(components.resolve(TaskScheduler.class));
        assertNotNull(components.resolve(MenuManager.class));
        assertNotNull(components.resolve(ScheduledTaskLifecycle.class));
    }

    @Test
    void doesNotInstallUnrequestedCapabilities() {
        ComponentContainer components = BukkitComponents.create(mock(JavaPlugin.class), COMMANDS);

        assertNotNull(components.resolve(CommandRouteCompiler.class));
        assertThrows(IllegalStateException.class, () -> components.resolve(MenuManager.class));
        assertThrows(IllegalStateException.class, () -> components.resolve(ScheduledTaskLifecycle.class));
    }

    @Test
    void menusDoNotImplicitlyEnableScheduledTasks() {
        ComponentContainer components = BukkitComponents.create(mock(JavaPlugin.class), MENUS);

        assertNotNull(components.resolve(MenuManager.class));
        assertThrows(IllegalStateException.class, () -> components.resolve(ScheduledTaskLifecycle.class));
    }
}
