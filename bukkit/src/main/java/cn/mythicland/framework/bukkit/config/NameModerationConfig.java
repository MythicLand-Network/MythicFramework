package cn.mythicland.framework.bukkit.config;

import cn.mythicland.framework.moderation.NameModeration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class NameModerationConfig {
    private NameModerationConfig() {}

    public static NameModeration load(ConfigurationSection configuration, JavaPlugin plugin) {
        String key = "name-moderation.enabled";
        if (configuration.contains(key) && !configuration.isBoolean(key)) {
            plugin.getLogger().warning(key + " must be a boolean");
            return NameModeration.UNAVAILABLE;
        }
        if (!configuration.getBoolean(key, false)) return NameModeration.DISABLED;
        return NameModeration.aliyun(
                configuration.getString("name-moderation.access-key-id", "").trim(),
                configuration.getString("name-moderation.access-key-secret", "").trim(),
                plugin.getLogger(),
                task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task)
        );
    }
}
