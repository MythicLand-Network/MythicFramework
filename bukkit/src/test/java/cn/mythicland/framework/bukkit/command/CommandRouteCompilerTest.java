package cn.mythicland.framework.bukkit.command;

import java.util.List;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

final class CommandRouteCompilerTest {
    @Test
    void onlinePlayerProviderIsScopedToItsCompilationAndFiltersTheVisiblePrefix() {
        Player player = mock(Player.class);
        var compiler = new CommandRouteCompiler(null);
        var command = new PlayerCommand();
        var first = compiler.compileRoots(List.of(command), (sender, context) -> List.of("FirstAlias"));
        var second = compiler.compileRoots(List.of(command), (sender, context) -> List.of("SecondAlias"));
        var firstBridge = new BukkitCommandBridge(null, first.get("view"));
        var secondBridge = new BukkitCommandBridge(null, second.get("view"));
        assertEquals(List.of("FirstAlias"), firstBridge.onTabComplete(player, null, "view", new String[]{"First"}));
        assertEquals(List.of(), firstBridge.onTabComplete(player, null, "view", new String[]{"Real"}));
        assertEquals(List.of("SecondAlias"), secondBridge.onTabComplete(player, null, "view", new String[]{"Second"}));
    }

    @Test
    void compilesDifferentRootsFromOneCommandComponent() {
        List<CommandSpec> specs = new CommandRouteCompiler(null).compileAll(new MultipleRootCommand());

        assertEquals(List.of("login", "register"), specs.stream().map(CommandSpec::rootName).toList());
    }

    @Test
    void compilesAliasesAndResolvesDynamicPermissionAtUseTime() {
        DynamicCommand command = new DynamicCommand();
        CommandNode root = new CommandRouteCompiler(null).compile(command).root();
        assertEquals("first", root.child("tp").permission());
        command.permission = "second";
        assertEquals("second", root.child("teleport").permission());
    }

    private static final class DynamicCommand extends AbstractCommand {
        private String permission = "first";

        @CommandRoute(
                path = {"world", "teleport"}, usages = "/world teleport", aliases = "tp",
                permissionMethod = "permission"
        )
        private void teleport(CommandContext context) {
        }

        private String permission() {
            return this.permission;
        }
    }

    private static final class PlayerCommand extends AbstractCommand {
        @CommandRoute(
                path = "view",
                usages = "/view <player>",
                maxArguments = 1,
                arguments = @CommandArgument(index = 0, source = SuggestionSource.ONLINE_PLAYERS)
        )
        private void view(CommandContext context) {
        }
    }

    private static final class MultipleRootCommand extends AbstractCommand {
        @CommandRoute(path = "login", usages = "/login")
        private void login(CommandContext context) {
        }

        @CommandRoute(path = "register", usages = "/register")
        private void register(CommandContext context) {
        }
    }
}
