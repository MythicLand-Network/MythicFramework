package cn.mythicland.framework.bukkit.bootstrap;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

final class BukkitRegistrationTest {
    @Test
    void closesUndoActionsInReverseOrderOnlyOnce() {
        List<String> actions = new ArrayList<>();
        BukkitRegistration registration = new BukkitRegistration();
        registration.addUndoAction(() -> actions.add("listener"));
        registration.addUndoAction(() -> actions.add("command"));

        registration.close();
        registration.close();

        assertEquals(List.of("command", "listener"), actions);
    }

    @Test
    void restoresThePreviousCommandHandlersOnce() {
        PluginCommand command = mock(PluginCommand.class);
        CommandExecutor previousExecutor = mock(CommandExecutor.class);
        TabCompleter previousCompleter = mock(TabCompleter.class);
        CommandExecutor executor = mock(CommandExecutor.class);
        TabCompleter completer = mock(TabCompleter.class);
        when(command.getExecutor()).thenReturn(previousExecutor);
        when(command.getTabCompleter()).thenReturn(previousCompleter);
        BukkitRegistration registration = new BukkitRegistration();

        registration.registerCommand(command, executor, completer);
        registration.close();
        registration.close();

        var order = inOrder(command);
        order.verify(command).setExecutor(executor);
        order.verify(command).setTabCompleter(completer);
        order.verify(command).setExecutor(previousExecutor);
        order.verify(command).setTabCompleter(previousCompleter);
    }
}
