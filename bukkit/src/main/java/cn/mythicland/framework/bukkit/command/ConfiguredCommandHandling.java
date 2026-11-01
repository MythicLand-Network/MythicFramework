package cn.mythicland.framework.bukkit.command;

import cn.mythicland.framework.command.CommandPath;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.command.CommandSender;

/**
 * Presentation and completion rules for a root with configured help.
 */
final class ConfiguredCommandHandling {
    private final CommandSpec spec;
    private final Logger logger;

    ConfiguredCommandHandling(CommandSpec spec, Logger logger) {
        this.spec = spec;
        this.logger = logger;
    }

    boolean execute(CommandSender sender, String label, String[] arguments) {
        CommandNode root = spec.root();
        if (arguments.length == 0) {
            if (root.canExecute()) executeNode(sender, root, List.of(), label, arguments);
            else if (spec.treeHelp()) treeHelp(sender, label, List.of());
            else shortHelp(sender);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("help")) {
            if (spec.treeHelp()) treeHelp(sender, label, Arrays.asList(arguments).subList(1, arguments.length));
            else if (spec.helpGroups().isEmpty()) {
                for (CommandNode node : commands())
                    if (allowed(sender, node))
                        sender.sendMessage(VanillaCommandMessages.usage(usageForLabel(usage(node), label)));
            } else groupedHelp(sender, label, arguments);
            return true;
        }
        CommandPath.Match<CommandNode> match = walk(arguments);
        CommandNode selected = null;
        for (CommandNode node : match.visited()) if (node.canExecute()) selected = node;
        if (selected == null) {
            if (spec.treeHelp()) treeHelp(sender, label, Arrays.stream(arguments, 0, match.consumed())
                    .map(value -> value.toLowerCase(Locale.ROOT)).toList());
            else if (root.canExecute() && root.child(arguments[0]) == null)
                executeNode(sender, root, List.copyOf(Arrays.asList(arguments)), label, arguments);
            else {
                sender.sendMessage(VanillaCommandMessages.red("未知子命令: " + arguments[0]));
                shortHelp(sender);
            }
            return true;
        }
        executeNode(sender, selected, List.copyOf(Arrays.asList(arguments).subList(match.consumed(), arguments.length)),
                label, arguments);
        return true;
    }

    List<String> complete(CommandSender sender, String label, String[] arguments) {
        if (arguments.length == 0) return List.of();
        if (arguments[0].equalsIgnoreCase("help") && spec.treeHelp()) return completeTreeHelp(sender, arguments);
        if (arguments[0].equalsIgnoreCase("help") && !spec.helpGroups().isEmpty()) {
            if (arguments.length != 2) return List.of();
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return spec.helpGroups().keySet().stream().filter(key -> key.startsWith(prefix))
                    .filter(key -> !commandsForGroup(sender, key).isEmpty()).toList();
        }
        CommandNode root = spec.root();
        String first = arguments[0].toLowerCase(Locale.ROOT);
        boolean rootPrefix = (!root.children().isEmpty() && "help".startsWith(first))
                || root.children().stream().anyMatch(child -> child.name().toLowerCase(Locale.ROOT).startsWith(first));
        if (!arguments[0].equalsIgnoreCase("help") && root.canExecute() && root.child(arguments[0]) == null
                && !rootPrefix) {
            if (!allowed(sender, root)) return List.of();
            return completeNode(sender, root, List.copyOf(Arrays.asList(arguments)), label);
        }
        CommandPath.Match<CommandNode> match = walk(arguments);
        CommandNode node = match.node();
        int consumed = match.consumed();
        if (consumed < arguments.length) {
            String token = arguments[consumed].toLowerCase(Locale.ROOT);
            Set<String> names = new LinkedHashSet<>();
            for (CommandNode child : node.children()) {
                if (child.name().toLowerCase(Locale.ROOT).startsWith(token) && accessible(sender, child))
                    names.add(child.name().toLowerCase(Locale.ROOT));
            }
            if (spec.treeHelp() && names.isEmpty() && node.canExecute() && allowed(sender, node)
                    && consumed == arguments.length - 1 && !token.isEmpty())
                return completeNode(sender, node,
                        List.copyOf(Arrays.asList(arguments).subList(consumed, arguments.length)), label);
            if (!node.children().isEmpty() && (!names.isEmpty() || consumed == arguments.length - 1)) {
                if (consumed == 0 && "help".startsWith(token)) names.add("help");
                return List.copyOf(names);
            }
        }
        if (consumed < arguments.length && node.canExecute() && allowed(sender, node))
            return completeNode(sender, node, List.copyOf(Arrays.asList(arguments).subList(consumed, arguments.length)),
                    label);
        if (consumed == arguments.length && !node.children().isEmpty())
            return node.children().stream().filter(child -> accessible(sender, child))
                    .map(child -> child.name().toLowerCase(Locale.ROOT)).toList();
        if (node.canExecute() && allowed(sender, node)) return completeNode(sender, node, List.of(), label);
        return List.of();
    }

    private void executeNode(CommandSender sender, CommandNode node, List<String> arguments, String label,
            String[] raw) {
        if (!allowed(sender, node)) {
            sender.sendMessage(VanillaCommandMessages.red("你没有执行此命令的权限。"));
            return;
        }
        try {
            if (arguments.size() < node.minArguments() || arguments.size() > node.maxArguments())
                throw new CommandUsageException(usage(node));
            node.execution().execute(new CommandContext(sender, spec.rootName(), label, usage(node), arguments));
        } catch (CommandUsageException exception) {
            if (spec.treeHelp()) treeHelp(sender, label, parentTopic(node));
            else sender.sendMessage(VanillaCommandMessages.usage(usageForLabel(exception.usage(), label)));
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Command execution failed for /" + label + " " + String.join(" ", raw), exception);
            sender.sendMessage(VanillaCommandMessages.red("命令执行失败, 请查看服务端日志。"));
        }
    }

    private List<String> completeNode(CommandSender sender, CommandNode node, List<String> arguments, String label) {
        try {
            CommandContext context = new CommandContext(sender, spec.rootName(), label, usage(node), arguments);
            List<String> values;
            if (node.completion() != null) values = node.completion().apply(context);
            else {
                int index = Math.max(0, arguments.size() - 1);
                if (index >= node.argumentSuggestions().size()) return List.of();
                values = node.argumentSuggestions().get(index).suggestions(sender, context);
            }
            if (values == null) return List.of();
            String prefix = arguments.isEmpty() ? "" : arguments.getLast().toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Command tab completion failed for /" + spec.rootName(), exception);
            return List.of();
        }
    }

    private List<CommandNode> commands() {
        Map<String, CommandNode> values = new LinkedHashMap<>();
        collect(spec.root(), values);
        return values.values().stream().sorted(Comparator.comparingInt(CommandNode::commandOrder)).toList();
    }

    private void collect(CommandNode node, Map<String, CommandNode> result) {
        if (node.canExecute()) result.putIfAbsent(node.commandPath(), node);
        for (CommandNode child : node.children()) collect(child, result);
    }

    private boolean allowed(CommandSender sender, CommandNode node) {
        String permission = node.permission();
        return permission == null || permission.isBlank() || sender.hasPermission(permission);
    }

    private boolean accessible(CommandSender sender, CommandNode node) {
        if (node.canExecute() && allowed(sender, node)) return true;
        return node.children().stream().anyMatch(child -> accessible(sender, child));
    }

    private CommandPath.Match<CommandNode> walk(String[] arguments) {
        return CommandPath.walk(spec.root(), arguments, arguments.length, CommandNode::child, node -> true);
    }

    private String usage(CommandNode node) {
        return node.usages().isEmpty() ? "/" + spec.rootName() : node.usages().getFirst();
    }

    private String usageForLabel(String usage, String label) {
        String root = "/" + spec.rootName();
        if (usage.equals(root) || usage.startsWith(root + " ")) return "/" + label + usage.substring(root.length());
        return usage;
    }

    private void shortHelp(CommandSender sender) {
        sender.sendMessage(VanillaCommandMessages.usage("/" + spec.rootName() + " <子命令>"));
        sender.sendMessage(VanillaCommandMessages.red("使用 /" + spec.rootName() + " help 查看所有命令。"));
    }

    private List<CommandNode> visible(CommandSender sender) {
        return commands().stream().filter(node -> !node.commandPath().isBlank()).filter(CommandNode::showInHelp)
                .filter(node -> allowed(sender, node)).toList();
    }

    private List<String> parentTopic(CommandNode node) {
        if (node.commandPath().isBlank()) return List.of();
        List<String> parts = new ArrayList<>(List.of(node.commandPath().split(" ")));
        if (parts.size() > 1) parts.removeLast();
        return parts;
    }

    private List<String> completeTreeHelp(CommandSender sender, String[] arguments) {
        if (arguments.length < 2) return List.of();
        String prefix =
                String.join(" ", Arrays.asList(arguments).subList(1, arguments.length - 1)).toLowerCase(Locale.ROOT);
        String current = arguments[arguments.length - 1].toLowerCase(Locale.ROOT);
        String start = prefix.isEmpty() ? "" : prefix + " ";
        return visible(sender).stream().map(CommandNode::commandPath).filter(name -> name.startsWith(start))
                .map(name -> name.substring(start.length()).split(" ")[0])
                .filter(name -> name.startsWith(current)).distinct().sorted().toList();
    }

    private void treeHelp(CommandSender sender, String label, List<String> topic) {
        String path = String.join(" ", topic).toLowerCase(Locale.ROOT);
        List<CommandNode> visible = visible(sender);
        boolean found = visible.stream()
                .anyMatch(node -> node.commandPath().equals(path) || node.commandPath().startsWith(path + " "));
        String selected = found ? path : "";
        List<String> lines;
        if (selected.isEmpty()) {
            lines = visible.stream().map(node -> "/" + label + " " + node.commandPath().split(" ")[0])
                    .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } else {
            lines = visible.stream().filter(node -> node.commandPath().equals(selected) ||
                            node.commandPath().startsWith(selected + " "))
                    .map(node -> usageForLabel(usage(node), label)).distinct().sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        for (String line : lines) sender.sendMessage(VanillaCommandMessages.usage(line));
    }

    private List<CommandNode> commandsForGroup(CommandSender sender, String group) {
        return commands().stream().filter(node -> node.helpGroup().equals(group)).filter(node -> allowed(sender, node))
                .sorted(Comparator.comparing(CommandNode::commandPath)).toList();
    }

    private void groupedHelp(CommandSender sender, String label, String[] arguments) {
        if (arguments.length == 1) {
            sender.sendMessage(VanillaCommandMessages.green("/" + label + " 帮助分类:"));
            spec.helpGroups().forEach((key, description) -> {
                if (!commandsForGroup(sender, key).isEmpty())
                    sender.sendMessage(
                            VanillaCommandMessages.usage("/" + label + " help " + key + " - " + description));
            });
            return;
        }
        if (arguments.length != 2) {
            sender.sendMessage(VanillaCommandMessages.usage("/" + label + " help [分类]"));
            return;
        }
        String key = arguments[1].toLowerCase(Locale.ROOT);
        List<CommandNode> commands = commandsForGroup(sender, key);
        if (!spec.helpGroups().containsKey(key) || commands.isEmpty()) {
            sender.sendMessage(VanillaCommandMessages.red("未知或不可用的帮助分类: " + arguments[1]));
            return;
        }
        sender.sendMessage(VanillaCommandMessages.green(spec.helpGroups().get(key) + " - 命令帮助:"));
        for (CommandNode node : commands) {
            String text = usageForLabel(usage(node), label);
            if (!node.description().isBlank()) text += " - " + node.description();
            sender.sendMessage(VanillaCommandMessages.usage(text));
        }
    }
}
