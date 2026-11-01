package cn.mythicland.framework.bukkit.command;

@FunctionalInterface
public interface CommandExecution {
    void execute(CommandContext context);
}
