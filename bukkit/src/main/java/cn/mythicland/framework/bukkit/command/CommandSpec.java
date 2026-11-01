package cn.mythicland.framework.bukkit.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CommandSpec {
    private final CommandNode root;
    private final boolean configuredHelp;
    private final boolean treeHelp;
    private final Map<String, String> helpGroups;

    private CommandSpec(CommandNode root) {
        this(root, false, false, Map.of());
    }

    private CommandSpec(
            CommandNode root,
            boolean configuredHelp,
            boolean treeHelp,
            Map<String, String> helpGroups
    ) {
        this.root = root;
        this.configuredHelp = configuredHelp;
        this.treeHelp = treeHelp;
        this.helpGroups = Collections.unmodifiableMap(new LinkedHashMap<>(helpGroups));
    }

    public static CommandSpec configured(
            CommandNode root,
            boolean treeHelp,
            Map<String, String> helpGroups
    ) {
        return new CommandSpec(root, true, treeHelp, helpGroups);
    }

    public boolean configuredHelp() {
        return this.configuredHelp;
    }

    public boolean treeHelp() {
        return this.treeHelp;
    }

    public Map<String, String> helpGroups() {
        return this.helpGroups;
    }

    public static CommandSpec of(CommandNode root) {
        return new CommandSpec(root);
    }

    public String rootName() {
        return this.root.name();
    }

    public CommandNode root() {
        return this.root;
    }

    public CommandSpec merge(CommandSpec other) {
        if (other == null) return this;
        if (this.configuredHelp != other.configuredHelp || this.treeHelp != other.treeHelp
                || !this.helpGroups.equals(other.helpGroups)) {
            throw new IllegalArgumentException("Cannot merge command roots with different help settings");
        }
        return new CommandSpec(this.root.merge(other.root), this.configuredHelp, this.treeHelp, this.helpGroups);
    }
}
