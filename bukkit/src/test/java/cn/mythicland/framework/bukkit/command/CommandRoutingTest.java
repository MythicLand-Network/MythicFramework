package cn.mythicland.framework.bukkit.command;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandRoutingTest {

    @Test
    void annotatedRootHandlerHandlesBareRootCommand() {
        AtomicBoolean executed = new AtomicBoolean();
        BukkitCommandBridge router = router(new RootCommand(executed));

        assertTrue(router.onCommand(sender(), null, "edit", new String[0]));
        assertTrue(executed.get());
    }

    @Test
    void annotatedRootHandlerReceivesPositionalArguments() {
        AtomicReference<List<String>> received = new AtomicReference<>();
        BukkitCommandBridge router = router(new RootArgumentCommand(received));

        assertTrue(router.onCommand(sender(), null, "gm", new String[]{"0"}));
        assertEquals(List.of("0"), received.get());
        assertEquals(
                List.of("0", "1", "2", "3"),
                router.onTabComplete(sender(), null, "gm", new String[]{""})
        );
    }

    @Test
    void annotatedPermissionAndCompleterAreApplied() {
        BukkitCommandBridge router = router(new AnnotatedCommand());

        assertEquals(
                List.of("one"),
                router.onTabComplete(sender(), null, "example", new String[]{"reload", ""})
        );
    }

    @Test
    void nestedAnnotatedPathsRouteArgumentsAndCompletion() {
        List<String> received = new ArrayList<>();
        BukkitCommandBridge router = router(new NestedCommand(received));

        assertTrue(router.onCommand(
                sender(),
                null,
                "nested",
                new String[]{"cmd", "add", "player", "say", "hello"}
        ));
        assertEquals(List.of("player", "say", "hello"), received);
        assertEquals(
                List.of("player", "console"),
                router.onTabComplete(sender(), null, "nested", new String[]{"cmd", "add", ""})
        );
    }

    @Test
    void dynamicPermissionIsResolvedAtInvocationTime() {
        DynamicPermissionCommand command = new DynamicPermissionCommand();
        BukkitCommandBridge router = router(command);

        assertTrue(router.onCommand(
                sender(new ArrayList<>(), permission -> permission.equals("first")),
                null,
                "dynamic",
                new String[0]
        ));
        command.permission = "second";
        List<String> messages = new ArrayList<>();
        assertTrue(router.onCommand(
                sender(messages, permission -> permission.equals("first")),
                null,
                "dynamic",
                new String[0]
        ));
        assertTrue(messages.stream().anyMatch(message -> message.contains("没有执行此命令的权限")));
    }

    @Test
    void rootPrefixesAndHelpCategoriesCompleteForAliasesAndRespectPermissions() {
        BukkitCommandBridge router = router(new GroupedCommand());
        var admin = sender(new ArrayList<>(), permission -> true);
        var player = sender(new ArrayList<>(), permission -> false);
        assertTrue(router.onTabComplete(admin, null, "ex", new String[]{""}).containsAll(List.of("items", "help")));
        assertEquals(List.of("items"), router.onTabComplete(admin, null, "ex", new String[]{"it"}));
        assertEquals(List.of("help"), router.onTabComplete(admin, null, "ex", new String[]{"he"}));
        assertEquals(List.of("edit"), router.onTabComplete(admin, null, "ex", new String[]{"items", "e"}));
        assertEquals(List.of("alpha"), router.onTabComplete(admin, null, "ex", new String[]{"items", "edit", "al"}));
        assertEquals(List.of("items"), router.onTabComplete(admin, null, "ex", new String[]{"help", "i"}));
        assertEquals(List.of(), router.onTabComplete(player, null, "ex", new String[]{"help", "i"}));
        assertEquals(List.of(), router.onTabComplete(player, null, "ex", new String[]{"it"}));
    }

    @Test
    void groupedHelpUsesTheInvokedAliasAndSortedAuthorizedCommands() {
        BukkitCommandBridge router = router(new GroupedCommand());
        List<String> messages = new ArrayList<>();
        var admin = sender(messages, permission -> true);
        router.onCommand(admin, null, "ex", new String[]{"help"});
        assertEquals(3, messages.size());
        assertTrue(messages.get(1).contains("/ex help player"));
        assertTrue(messages.get(2).contains("/ex help items"));
        messages.clear();
        router.onCommand(admin, null, "ex", new String[]{"help", "items"});
        assertEquals(3, messages.size());
        assertTrue(messages.get(1).contains("/ex items edit"));
        assertTrue(messages.get(2).contains("/ex items reload"));
        messages.clear();
        router.onCommand(sender(messages, permission -> false), null, "ex", new String[]{"help"});
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("/ex help player"));
    }

    @Test
    void treeHelpShowsSortedRedCommandsAndNestedHelpForInvalidInput() {
        BukkitCommandBridge router = router(new TreeCommand());
        List<String> messages = new ArrayList<>();
        var admin = sender(messages, permission -> true);
        router.onCommand(admin, null, "ex", new String[]{"help"});
        assertEquals(List.of("§4> §c/ex combat", "§4> §c/ex items", "§4> §c/ex npc"), messages);
        messages.clear();
        router.onCommand(admin, null, "ex", new String[]{"items", "wrong"});
        assertEquals(List.of("§4> §c/ex items", "§4> §c/ex items edit <id>"), messages);
        messages.clear();
        router.onCommand(admin, null, "ex", new String[]{"combat", "wrong"});
        assertEquals(List.of("§4> §c/ex combat reload"), messages);
        messages.clear();
        router.onCommand(admin, null, "ex", new String[]{"unrecognized"});
        assertEquals(List.of("§4> §c/ex combat", "§4> §c/ex items", "§4> §c/ex npc"), messages);
        messages.clear();
        router.onCommand(sender(messages, permission -> false), null, "ex", new String[]{"help"});
        assertEquals(List.of("§4> §c/ex npc"), messages);
        assertEquals(List.of("combat", "items", "npc"),
                router.onTabComplete(admin, null, "ex", new String[]{"help", ""}));
        assertEquals(List.of("edit"), router.onTabComplete(admin, null, "ex", new String[]{"help", "items", ""}));
        assertEquals(List.of("legacy-id"), router.onTabComplete(admin, null, "ex", new String[]{"npc", "lega"}));
    }

    @Test
    void bareTreeCommandWithoutRootHandlerShowsHelp() {
        BukkitCommandBridge router = router(new TreeWithoutRoot());
        List<String> messages = new ArrayList<>();
        router.onCommand(sender(messages, permission -> true), null, "ex", new String[0]);
        assertEquals(List.of("§4> §c/ex items", "§4> §c/ex reload"), messages);
    }

    @Test
    void standardHelpAndInvalidUsageUseTheSameArrowAndAlias() {
        BukkitCommandBridge router = router(new AnnotatedCommand());
        List<String> messages = new ArrayList<>();
        var admin = sender(messages, permission -> true);
        router.onCommand(admin, null, "ex", new String[]{"help"});
        assertEquals(List.of("§4> §c/ex reload"), messages);
        messages.clear();
        router.onCommand(admin, null, "ex", new String[]{"reload"});
        assertEquals(List.of("§4> §c/ex reload"), messages);
    }

    @Test
    void commonMessageColorsAreAvailable() {
        assertTrue(VanillaCommandMessages.red("message").startsWith(ChatColor.RED.toString()));
        assertTrue(VanillaCommandMessages.yellow("message").startsWith(ChatColor.YELLOW.toString()));
        assertTrue(VanillaCommandMessages.green("message").startsWith(ChatColor.GREEN.toString()));
    }

    @Test
    void multilineUsagePrefixesEveryCommandLine() {
        String usage = VanillaCommandMessages.usage("/worldregion landmark set <id> <显示名>\n"
                + "/worldregion landmark delete <id>\n"
                + "/worldregion landmark list");

        assertEquals(
                ChatColor.DARK_RED + "> " + ChatColor.RED + "/worldregion landmark set <id> <显示名>\n"
                        + ChatColor.DARK_RED + "> " + ChatColor.RED + "/worldregion landmark delete <id>\n"
                        + ChatColor.DARK_RED + "> " + ChatColor.RED + "/worldregion landmark list",
                usage
        );
    }

    @Test
    void publicAliasDoesNotInheritRestrictedParentPermission() {
        AtomicBoolean executed = new AtomicBoolean();
        BukkitCommandBridge bridge = router(new PermissionCommand(executed));
        CommandSender player = sender(new ArrayList<>(), permission -> false);
        assertEquals(List.of("teleport", "tp"), bridge.onTabComplete(player, null, "gate", new String[]{"t"}));
        bridge.onCommand(player, null, "gate", new String[]{"tp"});
        assertTrue(executed.get());
    }

    @Test
    void boundRouteHonorsDeclaredArgumentLimitsAndSuggestions() {
        BukkitCommandBridge bridge = router(new SuggestedCommand());
        List<String> messages = new ArrayList<>();
        CommandSender player = sender(messages, permission -> true);
        assertEquals(List.of("alpha"), bridge.onTabComplete(player, null, "choice", new String[]{"a"}));
        bridge.onCommand(player, null, "choice", new String[]{"one", "two"});
        assertTrue(messages.stream().anyMatch(message -> message.contains("/choice <value>")));
    }

    @CommandBinding(value = "gate", permission = "admin")
    private record PermissionCommand(AtomicBoolean executed) {
        @CommandRoute(path = {"gate", "teleport"}, aliases = "tp", inheritPermission = false)
        private void teleport(CommandContext context) {
            executed.set(true);
        }
    }

    @CommandBinding("choice")
    private static final class SuggestedCommand {
        @CommandRoute(path = {"choice"}, usages = "/choice <value>", minArguments = 1, maxArguments = 1,
                arguments = @CommandArgument(index = 0, source = SuggestionSource.STATIC_VALUES, values = {"alpha",
                        "beta"}))
        private void choose(CommandContext context) {
        }
    }

    private BukkitCommandBridge router(Object component) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CommandRouterTest"));
        return new BukkitCommandBridge(plugin, new CommandRouteCompiler(null).compileBound(component));
    }

    private CommandSender sender() {
        return sender(new ArrayList<>(), permission -> true);
    }

    private CommandSender sender(List<String> messages, Predicate<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")) {
                        messages.add((String) arguments[0]);
                        return null;
                    }
                    if (method.getName().equals("hasPermission")) {
                        return permissions.test((String) arguments[0]);
                    }
                    if (method.getReturnType() == boolean.class) return true;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == float.class) return 0.0F;
                    if (method.getReturnType() == double.class) return 0.0D;
                    if (method.getReturnType() == short.class) return (short) 0;
                    if (method.getReturnType() == byte.class) return (byte) 0;
                    if (method.getReturnType() == char.class) return (char) 0;
                    return null;
                }
        );
    }

    @CommandBinding("edit")
    private record RootCommand(AtomicBoolean executed) {

        @CommandRoute(path = {"edit"}, maxArguments = Integer.MAX_VALUE)
        private void execute(CommandContext context) {
            context.requireArguments(0);
            executed.set(true);
        }
    }

    @CommandBinding("gm")
    private record RootArgumentCommand(AtomicReference<List<String>> received) {

        @CommandRoute(path = {"gm"}, maxArguments = Integer.MAX_VALUE)
        private void execute(CommandContext context) {
            received.set(context.arguments());
        }

        @CommandCompleter
        private List<String> complete(CommandContext context) {
            return List.of("0", "1", "2", "3");
        }
    }

    @CommandBinding("example")
    private static final class AnnotatedCommand {

        @CommandRoute(path = {"example", "reload"}, maxArguments = Integer.MAX_VALUE, permission = "example.reload")
        private void reload(CommandContext context) {
            context.requireArguments(1);
        }

        @CommandCompleter("reload")
        private List<String> complete(CommandContext context) {
            return List.of("one");
        }
    }

    @CommandBinding("nested")
    private record NestedCommand(List<String> received) {

        @CommandRoute(path = {"nested", "cmd", "add"}, maxArguments = Integer.MAX_VALUE, permission = "nested.add")
        private void add(CommandContext context) {
            context.requireAtLeast(3);
            received.addAll(context.arguments());
        }

        @CommandCompleter("cmd add")
        private List<String> complete(CommandContext context) {
            return List.of("player", "console");
        }
    }

    @CommandBinding(value = "example", helpGroups = {"player:玩家", "items:物品"})
    private static final class GroupedCommand {
        @CommandRoute(path = {"example"}, maxArguments = Integer.MAX_VALUE, helpGroup = "player")
        private void home(CommandContext context) {
        }

        @CommandRoute(path = {"example", "items",
                "reload"}, maxArguments = Integer.MAX_VALUE, helpGroup = "items", permission = "example.admin")
        private void reload(CommandContext context) {
        }

        @CommandRoute(path = {"example", "items",
                "edit"}, maxArguments = Integer.MAX_VALUE, helpGroup = "items", permission = "example.admin")
        private void edit(CommandContext context) {
        }

        @CommandCompleter("items edit")
        private List<String> completeEdit(CommandContext context) {
            return List.of("alpha", "beta");
        }
    }

    @CommandBinding(value = "example", treeHelp = true)
    private static final class TreeCommand {
        @CommandRoute(path = {"example"}, maxArguments = Integer.MAX_VALUE)
        private void home(CommandContext context) {
            context.requireArguments(0);
        }

        @CommandRoute(path = {"example", "items"}, maxArguments = Integer.MAX_VALUE, permission = "admin")
        private void items(CommandContext context) {
            context.requireArguments(0);
        }

        @CommandRoute(path = {"example", "items",
                "edit"}, maxArguments = Integer.MAX_VALUE, permission = "admin", usages = "/example items edit <id>")
        private void edit(CommandContext context) {
            context.requireArguments(1);
        }

        @CommandRoute(path = {"example", "combat", "reload"}, maxArguments = Integer.MAX_VALUE, permission = "admin")
        private void reload(CommandContext context) {
            context.requireArguments(0);
        }

        @CommandRoute(path = {"example", "npc"}, maxArguments = Integer.MAX_VALUE, showInHelp = false)
        private void legacyNpc(CommandContext context) {
            context.requireArguments(1);
        }

        @CommandCompleter("npc")
        private List<String> npcComplete(CommandContext context) {
            return List.of("legacy-id");
        }

        @CommandRoute(path = {"example", "npc", "list"}, maxArguments = Integer.MAX_VALUE)
        private void npcList(CommandContext context) {
            context.requireArguments(0);
        }
    }

    @CommandBinding(value = "example", treeHelp = true)
    private static final class TreeWithoutRoot {
        @CommandRoute(path = {"example", "items"}, maxArguments = Integer.MAX_VALUE)
        private void items(CommandContext context) {
        }

        @CommandRoute(path = {"example", "reload"}, maxArguments = Integer.MAX_VALUE)
        private void reload(CommandContext context) {
        }
    }

    @CommandBinding("dynamic")
    private static final class DynamicPermissionCommand {

        private String permission = "first";

        @CommandRoute(path = {"dynamic"}, maxArguments = Integer.MAX_VALUE, permissionMethod = "permission")
        private void execute(CommandContext context) {
            context.requireArguments(0);
        }

        private String permission() {
            return permission;
        }
    }
}
