package cn.mythicland.framework.bukkit.bootstrap;

import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Provides shared Bukkit lifecycle entry points and startup failure handling.
 */
public abstract class ManagedPlugin extends JavaPlugin {
    @Override
    public final void onEnable() {
        try {
            this.enablePlugin();
        } catch (RuntimeException exception) {
            this.onEnableFailure(exception);
            this.getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public final void onDisable() {
        this.disablePlugin();
    }

    protected abstract void enablePlugin();

    protected abstract void disablePlugin();

    protected void onEnableFailure(RuntimeException exception) {
        this.getLogger().log(Level.SEVERE, "Failed to enable " + this.getName(), exception);
    }
}
