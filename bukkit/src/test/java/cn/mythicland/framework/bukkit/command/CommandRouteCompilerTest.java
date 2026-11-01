package cn.mythicland.framework.bukkit.command;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CommandRouteCompilerTest {
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

        @CommandRoute(path = {"world", "teleport"}, usages = "/world teleport", aliases = "tp",
                permissionMethod = "permission")
        private void teleport(CommandContext context) {
        }

        private String permission() {
            return this.permission;
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
