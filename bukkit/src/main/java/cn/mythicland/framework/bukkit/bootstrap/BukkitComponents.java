package cn.mythicland.framework.bukkit.bootstrap;

import cn.mythicland.framework.bukkit.command.CommandRouteCompiler;
import cn.mythicland.framework.bukkit.menu.MenuManager;
import cn.mythicland.framework.bukkit.task.BukkitTaskScheduler;
import cn.mythicland.framework.bukkit.task.ScheduledTaskLifecycle;
import cn.mythicland.framework.bukkit.task.TaskScheduler;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.framework.component.ComponentResolver;

import java.util.Set;

import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitComponents {
    public enum Capability {COMMANDS, MENUS, SCHEDULED_TASKS}

    private BukkitComponents() {
    }

    public static ComponentContainer create(JavaPlugin plugin, Capability... capabilities) {
        Set<Capability> requested = Set.of(capabilities);
        ComponentContainer components = new ComponentContainer();
        components.bind(plugin);
        components.bind(JavaPlugin.class, plugin);
        components.bind(ComponentContainer.class, components);
        components.bind(ComponentResolver.class, components);
        if (requested.contains(Capability.COMMANDS)) components.bind(new CommandRouteCompiler(components));
        if (requested.contains(Capability.MENUS) || requested.contains(Capability.SCHEDULED_TASKS)) {
            TaskScheduler scheduler = new BukkitTaskScheduler(plugin);
            components.bind(TaskScheduler.class, scheduler);
            if (requested.contains(Capability.MENUS)) components.bind(new MenuManager(plugin, components, scheduler));
            if (requested.contains(Capability.SCHEDULED_TASKS)) {
                components.bind(new ScheduledTaskLifecycle(components, scheduler));
            }
        }
        return components;
    }
}
