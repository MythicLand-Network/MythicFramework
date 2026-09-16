package cn.mythicland.framework.bukkit.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public abstract class AbstractYamlConfiguration {
    private final Plugin plugin;
    private final File file;
    private final String fileName;
    private final String[] legacyFileNames;
    private YamlConfiguration yamlConfiguration;

    protected AbstractYamlConfiguration(
            Plugin plugin,
            String fileName,
            String... legacyFileNames
    ) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.file = new File(plugin.getDataFolder(), fileName);
        this.legacyFileNames = legacyFileNames.clone();
        this.saveDefaultConfig();
        this.loadYamlConfiguration();
    }

    public final void saveDefaultConfig() {
        File dataFolder = this.plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create plugin data folder for " + this.fileName);
        }
        if (this.file.exists()) return;
        if (this.tryMigrateLegacyFile()) return;
        try {
            this.plugin.saveResource(this.fileName, false);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Failed to copy default config resource " + this.fileName,
                    exception
            );
        }
    }

    public final void loadYamlConfiguration() {
        this.yamlConfiguration = YamlConfiguration.loadConfiguration(this.file);
    }

    public void reload() {
        this.reloadYamlConfiguration();
    }

    public final void reloadYamlConfiguration() {
        this.loadYamlConfiguration();
    }

    public final void saveYamlConfiguration() {
        try {
            this.yamlConfiguration.save(this.file);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to save " + this.fileName,
                    exception
            );
        }
    }

    public final YamlConfiguration getYamlConfiguration() {
        return this.yamlConfiguration;
    }

    public final String getFileName() {
        return this.fileName;
    }

    private boolean tryMigrateLegacyFile() {
        for (String legacyFileName : this.legacyFileNames) {
            if (legacyFileName == null || legacyFileName.trim().isEmpty()) continue;
            File legacyFile = new File(this.plugin.getDataFolder(), legacyFileName);
            if (!legacyFile.exists()) continue;
            try {
                Files.copy(
                        legacyFile.toPath(),
                        this.file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
                return true;
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Failed to migrate " + legacyFileName + " to " + this.fileName,
                        exception
                );
            }
        }
        return false;
    }
}
