package cn.mythicland.framework.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CommandRouteCompilerTest {
    @Test
    void compilesDifferentRootsFromOneCommandComponent() {
        List<CommandSpec> specs = new CommandRouteCompiler(null).compileAll(new MultipleRootCommand());

        assertEquals(List.of("login", "register"), specs.stream().map(CommandSpec::rootName).toList());
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
