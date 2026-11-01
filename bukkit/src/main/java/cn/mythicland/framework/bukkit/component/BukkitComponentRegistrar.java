package cn.mythicland.framework.bukkit.component;

import cn.mythicland.framework.bukkit.bootstrap.BukkitRegistration;
import cn.mythicland.framework.bukkit.command.BukkitCommandRegistrar;
import cn.mythicland.framework.component.ComponentResolver;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitComponentRegistrar {
    private BukkitComponentRegistrar() {
    }

    public static BukkitRegistration register(JavaPlugin plugin, ComponentResolver components) {
        BukkitRegistration registration = registerListeners(plugin, components);
        try {
            registration.combine(BukkitCommandRegistrar.register(plugin, components));
            return registration;
        } catch (RuntimeException | Error exception) {
            try {
                registration.close();
            } catch (RuntimeException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    public static BukkitRegistration registerListeners(JavaPlugin plugin, ComponentResolver components) {
        BukkitRegistration registration = new BukkitRegistration();
        try {
            for (Listener listener : components.resolveAll(ListenerComponent.class, Listener.class)) {
                registration.registerListener(plugin, listener);
            }
            return registration;
        } catch (RuntimeException | Error exception) {
            try {
                registration.close();
            } catch (RuntimeException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }
}
