package cn.mythicland.framework.bukkit.command;

import cn.mythicland.framework.bukkit.bootstrap.BukkitRegistration;
import cn.mythicland.framework.bukkit.component.CommandComponent;
import cn.mythicland.framework.component.ComponentResolver;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitCommandRegistrar {
    private BukkitCommandRegistrar() {
    }

    public static BukkitRegistration register(JavaPlugin plugin, ComponentResolver components) {
        return register(
                plugin,
                components.resolveAll(CommandComponent.class, AbstractCommand.class),
                components
        );
    }

    public static BukkitRegistration register(
            JavaPlugin plugin,
            Collection<? extends AbstractCommand> commands,
            ComponentResolver componentResolver
    ) {
        CommandRouteCompiler compiler = new CommandRouteCompiler(componentResolver);
        Map<String, CommandSpec> specs = new LinkedHashMap<>();
        for (AbstractCommand command : commands) {
            for (CommandSpec compiled : compiler.compileAll(command)) {
                specs.merge(compiled.rootName().toLowerCase(), compiled, CommandSpec::merge);
            }
        }
        BukkitRegistration registration = new BukkitRegistration();
        try {
            for (CommandSpec spec : specs.values()) {
                PluginCommand pluginCommand = plugin.getCommand(spec.rootName());
                if (pluginCommand == null) {
                    throw new IllegalStateException("Command is missing from plugin.yml: " + spec.rootName());
                }
                BukkitCommandBridge bridge = new BukkitCommandBridge(plugin, spec);
                registration.registerCommand(pluginCommand, bridge, bridge);
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
