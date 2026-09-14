package cn.mythicland.framework.command;

import cn.mythicland.framework.bukkit.CommandComponent;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.framework.component.ComponentResolver;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;

public final class BukkitCommandRegistrar {
    private BukkitCommandRegistrar() {
    }

    public static void register(JavaPlugin plugin, ComponentContainer components) {
        register(plugin, components.componentsAnnotatedWith(CommandComponent.class, AbstractCommand.class), components);
    }

    public static void register(
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
        for (CommandSpec spec : specs.values()) {
            PluginCommand pluginCommand = plugin.getCommand(spec.rootName());
            if (pluginCommand == null) {
                throw new IllegalStateException("Command is missing from plugin.yml: " + spec.rootName());
            }
            BukkitCommandBridge bridge = new BukkitCommandBridge(plugin, spec);
            pluginCommand.setExecutor(bridge);
            pluginCommand.setTabCompleter(bridge);
        }
    }
}
