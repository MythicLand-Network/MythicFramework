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
        return registerCompiled(plugin, compiler.compileRoots(commands).values()).registration();
    }

    public static RegisteredCommands registerCompiled(JavaPlugin plugin, Collection<CommandSpec> specs) {
        BukkitRegistration registration = new BukkitRegistration();
        Map<PluginCommand, CommandSpec> bindings = new LinkedHashMap<>();
        try {
            for (CommandSpec spec : specs) {
                PluginCommand pluginCommand = plugin.getCommand(spec.rootName());
                if (pluginCommand == null) {
                    throw new IllegalStateException("Command is missing from plugin.yml: " + spec.rootName());
                }
                BukkitCommandBridge bridge = new BukkitCommandBridge(plugin, spec);
                registration.registerCommand(pluginCommand, bridge, bridge);
                bindings.put(pluginCommand, spec);
            }
            return new RegisteredCommands(registration, bindings);
        } catch (RuntimeException | Error exception) {
            try {
                registration.close();
            } catch (RuntimeException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    public record RegisteredCommands(
            BukkitRegistration registration,
            Map<PluginCommand, CommandSpec> bindings
    ) implements AutoCloseable {
        public RegisteredCommands {
            bindings = Map.copyOf(bindings);
        }

        @Override
        public void close() {
            this.registration.close();
        }
    }
}
