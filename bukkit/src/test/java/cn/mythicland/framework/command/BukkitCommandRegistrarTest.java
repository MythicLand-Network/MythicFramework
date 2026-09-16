package cn.mythicland.framework.command;

import cn.mythicland.framework.component.ComponentResolver;
import cn.mythicland.framework.bukkit.BukkitRegistration;
import cn.mythicland.framework.bukkit.CommandComponent;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class BukkitCommandRegistrarTest {
    @Test
    void restoresExistingCommandHandlersWhenRegistrationCloses() {
        ComponentResolver resolver = mock(ComponentResolver.class);
        CommandComponentFixture command = new CommandComponentFixture();
        when(resolver.resolveAll(CommandComponent.class, AbstractCommand.class)).thenReturn(List.of(command));

        JavaPlugin plugin = mock(JavaPlugin.class);
        PluginCommand pluginCommand = mock(PluginCommand.class);
        CommandExecutor previousExecutor = mock(CommandExecutor.class);
        TabCompleter previousCompleter = mock(TabCompleter.class);
        when(plugin.getCommand("fixture")).thenReturn(pluginCommand);
        when(pluginCommand.getExecutor()).thenReturn(previousExecutor);
        when(pluginCommand.getTabCompleter()).thenReturn(previousCompleter);

        BukkitRegistration registration = BukkitCommandRegistrar.register(plugin, resolver);
        registration.close();

        verify(pluginCommand).setExecutor(previousExecutor);
        verify(pluginCommand).setTabCompleter(previousCompleter);
    }

    @cn.mythicland.framework.bukkit.CommandComponent
    private static final class CommandComponentFixture extends AbstractCommand {
        @CommandRoute(path = "fixture", usages = "/fixture")
        private void fixture(CommandContext context) {
        }
    }
}
