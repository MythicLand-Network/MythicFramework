package cn.mythicland.framework.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class BukkitCommandBridgeTest {
    @Test
    void rootShouldReportNoPermissionWhenEverySubcommandIsUnavailable() {
        BukkitCommandBridge bridge = newBridge(CommandSpec.of(
                CommandNode.root("membership")
                        .child(command("set", "mythicmembership.admin", "/membership set"))
                        .build()
        ));
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("mythicmembership.admin")).thenReturn(false);

        bridge.onCommand(sender, mock(Command.class), "membership", new String[0]);

        verify(sender).sendMessage("§c你没有权限使用这个命令。");
        verify(sender, never()).sendMessage("§c用法:/membership set");
    }

    @Test
    void rootShouldOnlyShowUsagesAvailableToSender() {
        BukkitCommandBridge bridge = newBridge(CommandSpec.of(
                CommandNode.root("membership")
                        .child(command("info", null, "/membership info"))
                        .child(command("set", "mythicmembership.admin", "/membership set"))
                        .build()
        ));
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("mythicmembership.admin")).thenReturn(false);

        bridge.onCommand(sender, mock(Command.class), "membership", new String[0]);

        verify(sender).sendMessage("§c用法:/membership info");
        verify(sender, never()).sendMessage("§c用法:/membership set");
        verify(sender, never()).sendMessage("§c你没有权限使用这个命令。");
    }

    private static CommandNode command(String name, String permission, String usage) {
        return CommandNode.literal(name)
                .permission(permission)
                .usage(usage)
                .execute(context -> {
                })
                .build();
    }

    private static BukkitCommandBridge newBridge(CommandSpec spec) {
        return new BukkitCommandBridge(mock(JavaPlugin.class), spec);
    }
}
