package cn.mythicland.framework.bukkit.command;

import cn.mythicland.framework.command.CommandPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitCommandBridge implements CommandExecutor, TabCompleter {
    private static final String NO_PERMISSION = "§c你没有权限使用这个命令。";

    private final JavaPlugin plugin;
    private final CommandSpec spec;
    private final ConfiguredCommandHandling configuredHandling;

    public BukkitCommandBridge(JavaPlugin plugin, CommandSpec spec) {
        this.plugin = plugin;
        this.spec = spec;
        this.configuredHandling =
                spec.configuredHelp() ? new ConfiguredCommandHandling(spec, plugin.getLogger()) : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        try {
            String[] input = arguments == null ? new String[0] : arguments;
            if (this.spec.configuredHelp()) return this.configuredHandling.execute(sender, label, input);
            this.execute(sender, label, input);
        } catch (Exception exception) {
            this.plugin.getLogger().log(
                    Level.SEVERE,
                    "Unhandled command exception: /" + label + " " + String.join(" ", arguments),
                    exception
            );
            sender.sendMessage("§c命令执行失败, 异常已写入后台日志。");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        String[] input = arguments == null ? new String[0] : arguments;
        if (this.spec.configuredHelp()) return this.configuredHandling.complete(sender, alias, input);
        return this.suggest(sender, alias, input);
    }

    private void execute(CommandSender sender, String label, String[] arguments) {
        CommandPath.Match<CommandNode> match = CommandPath.walk(
                this.spec.root(), arguments, arguments.length, CommandNode::child,
                child -> this.hasPermission(sender, child)
        );
        if (match.denied()) {
            sender.sendMessage(NO_PERMISSION);
            return;
        }
        CommandNode current = match.node();
        int index = match.consumed();
        if (!this.hasPermission(sender, current)) {
            sender.sendMessage(NO_PERMISSION);
            return;
        }
        List<String> remaining = Arrays.asList(arguments).subList(index, arguments.length);
        if (!current.canExecute()
                || remaining.size() < current.minArguments()
                || remaining.size() > current.maxArguments()) {
            this.sendUsage(sender, current);
            return;
        }
        try {
            current.execution().execute(new CommandContext(sender, label, remaining));
        } catch (CommandUsageException exception) {
            this.sendUsage(sender, current);
        }
    }

    private List<String> suggest(CommandSender sender, String label, String[] arguments) {
        int pathTokens = Math.max(0, arguments.length - 1);
        CommandPath.Match<CommandNode> match = CommandPath.walk(
                this.spec.root(), arguments, pathTokens, CommandNode::child,
                child -> this.hasPermission(sender, child)
        );
        if (match.denied()) return List.of();
        CommandNode current = match.node();
        int consumed = match.consumed();
        if (consumed < pathTokens && !current.canExecute()) return List.of();
        if (!this.hasPermission(sender, current)) return List.of();
        String prefix = arguments.length == 0 ? "" : arguments[arguments.length - 1];
        int argumentIndex = Math.max(0, arguments.length - consumed - 1);
        CommandContext context = new CommandContext(sender, label,
                arguments.length == 0 ? List.of() : Arrays.asList(arguments).subList(consumed, arguments.length));
        Set<String> values = new LinkedHashSet<>();
        for (CommandNode child : current.children()) {
            if (this.hasPermission(sender, child)) values.add(child.name());
        }
        if (current.canExecute() && argumentIndex < current.maxArguments()) {
            List<SuggestionProvider> suggestions = current.argumentSuggestions();
            if (argumentIndex < suggestions.size()) {
                values.addAll(suggestions.get(argumentIndex).suggestions(sender, context));
            }
        }
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (value.toLowerCase(Locale.ROOT).startsWith(normalized)) result.add(value);
        }
        return result;
    }

    private void sendUsage(CommandSender sender, CommandNode node) {
        Set<String> usages = new LinkedHashSet<>();
        this.collectUsages(sender, node, usages);
        if (usages.isEmpty() && this.hasDeclaredUsage(node)) {
            sender.sendMessage(NO_PERMISSION);
            return;
        }
        if (usages.isEmpty()) sender.sendMessage("§c用法: /" + node.name());
        for (String usage : usages) sender.sendMessage("§c用法: " + usage);
    }

    private void collectUsages(CommandSender sender, CommandNode node, Set<String> usages) {
        if (!this.hasPermission(sender, node)) return;
        usages.addAll(node.usages());
        for (CommandNode child : node.children()) this.collectUsages(sender, child, usages);
    }

    private boolean hasDeclaredUsage(CommandNode node) {
        if (!node.usages().isEmpty()) return true;
        for (CommandNode child : node.children()) {
            if (this.hasDeclaredUsage(child)) return true;
        }
        return false;
    }

    private boolean hasPermission(CommandSender sender, CommandNode node) {
        return node.permission() == null || node.permission().isBlank() || sender.hasPermission(node.permission());
    }
}
