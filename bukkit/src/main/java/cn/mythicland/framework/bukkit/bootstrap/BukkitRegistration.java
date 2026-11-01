package cn.mythicland.framework.bukkit.bootstrap;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitRegistration implements AutoCloseable {
    private final List<Runnable> undoActions;
    private boolean closed;

    public BukkitRegistration() {
        this.undoActions = new ArrayList<>();
    }

    public void addUndoAction(Runnable undoAction) {
        if (this.closed) throw new IllegalStateException("Bukkit registration is already closed");
        this.undoActions.add(undoAction);
    }

    public void registerListener(JavaPlugin plugin, Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        this.addUndoAction(() -> HandlerList.unregisterAll(listener));
    }

    public <T> void registerService(JavaPlugin plugin, Class<T> contract, T provider, ServicePriority priority) {
        plugin.getServer().getServicesManager().register(contract, provider, plugin, priority);
        this.addUndoAction(() -> plugin.getServer().getServicesManager().unregister(contract, provider));
    }

    public void registerCommand(PluginCommand command, CommandExecutor executor, TabCompleter completer) {
        CommandExecutor previousExecutor = command.getExecutor();
        TabCompleter previousCompleter = command.getTabCompleter();
        command.setExecutor(executor);
        command.setTabCompleter(completer);
        this.addUndoAction(() -> {
            command.setExecutor(previousExecutor);
            command.setTabCompleter(previousCompleter);
        });
    }

    public void combine(BukkitRegistration registration) {
        if (registration == null) return;
        this.addUndoAction(registration::close);
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        Throwable failure = null;
        for (int index = this.undoActions.size() - 1; index >= 0; index--) {
            try {
                this.undoActions.get(index).run();
            } catch (RuntimeException | Error exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        this.undoActions.clear();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }

    public boolean isClosed() {
        return this.closed;
    }
}
