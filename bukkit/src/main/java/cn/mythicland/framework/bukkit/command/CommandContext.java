package cn.mythicland.framework.bukkit.command;

import java.util.Arrays;
import java.util.List;

import org.bukkit.command.CommandSender;

/**
 * Immutable command input shared by route handlers and completion providers.
 */
public record CommandContext(
        CommandSender sender,
        String rootCommand,
        String label,
        String usage,
        List<String> arguments
) {
    public CommandContext {
        arguments = List.copyOf(arguments);
    }

    public CommandContext(CommandSender sender, String label, List<String> arguments) {
        this(sender, label, label, null, arguments);
    }

    public static CommandContext of(CommandSender sender, String label, String[] arguments) {
        return new CommandContext(sender, label, arguments == null ? List.of() : Arrays.asList(arguments));
    }

    public int argumentCount() {
        return arguments.size();
    }

    public String argument(int index) {
        if (usage != null && (index < 0 || index >= arguments.size())) throw invalidUsage();
        return arguments.get(index);
    }

    public String optionalArgument(int index) {
        if (index < 0 || index >= arguments.size()) return null;
        return arguments.get(index);
    }

    public void requireArguments(int expected) {
        if (expected < 0 || arguments.size() != expected) throw invalidUsage();
    }

    public void requireAtLeast(int minimum) {
        if (minimum < 0 || arguments.size() < minimum) throw invalidUsage();
    }

    public CommandUsageException invalidUsage() {
        return new CommandUsageException(usage == null ? "/" + label : usage);
    }
}
