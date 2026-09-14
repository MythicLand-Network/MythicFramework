package cn.mythicland.framework.bukkit;

import cn.mythicland.framework.component.ComponentContainer;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitComponentRegistrar {
    private BukkitComponentRegistrar() {
    }

    public static void registerListeners(JavaPlugin plugin, ComponentContainer components) {
        for (Listener listener : components.componentsAnnotatedWith(ListenerComponent.class, Listener.class)) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
        }
    }
}
