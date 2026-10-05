package cn.mythicland.framework.bukkit.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class YamlConfigSynchronizerTest {
    @TempDir
    private Path folder;

    @Test
    public void synchronizePreservesValuesAndListsAndRemovesObsoleteNestedKeys() throws Exception {
        YamlConfiguration defaults = yaml("combat:\n  speed: 1.0\n  interval: 10\nitems: [iron]\nempty: {}\n");
        YamlConfiguration config =
                yaml("combat:\n  speed: 1.7\n  obsolete: true\nitems: [diamond, gold]\nold:\n  enabled: true\n");
        assertTrue(YamlConfigSynchronizer.synchronize(config, defaults));
        assertEquals(1.7, config.getDouble("combat.speed"), 0);
        assertEquals(10, config.getInt("combat.interval"));
        assertEquals(List.of("diamond", "gold"), config.getStringList("items"));
        assertFalse(config.contains("combat.obsolete"));
        assertFalse(config.contains("old"));
        assertTrue(config.isConfigurationSection("empty"));
        assertFalse(YamlConfigSynchronizer.synchronize(config, defaults));
    }

    @Test
    public void dynamicNamesPreserveOnlySupportedFieldsAndRuntimeData() throws Exception {
        YamlConfiguration config =
                yaml("mappings:\n  custom:\n    equipped: custom_profile\n    obsolete: true\nmining:\n  ladders: [{location: saved, facing: NORTH}]\n  old: true\n");
        assertTrue(YamlConfigSynchronizer.removeUnknown(config, Set.of("mappings.*.equipped", "mining.ladders")));
        assertEquals("custom_profile", config.getString("mappings.custom.equipped"));
        assertFalse(config.contains("mappings.custom.obsolete"));
        assertFalse(config.contains("mining.old"));
        assertEquals("saved", config.getMapList("mining.ladders").getFirst().get("location"));
    }

    @Test
    public void loadAndReloadWriteNewKeysAndDeleteObsoleteKeysWithoutReplacingExistingValues() throws Exception {
        var file = this.folder.resolve("test.yml");
        Files.writeString(file, "speed: 1.7\nold: 10\ncustom:\n  admin: chosen\n");
        TestConfig config = new TestConfig(plugin());
        config.load();
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(1.7, saved.getDouble("speed"), 0);
        assertEquals(10, saved.getInt("interval"));
        assertFalse(saved.contains("old"));
        assertEquals("chosen", saved.getString("custom.admin"));
        saved.set("interval", null);
        saved.set("old", 12);
        saved.save(file.toFile());
        config.load();
        saved = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(10, saved.getInt("interval"));
        assertFalse(saved.contains("old"));
        assertEquals(1.7, saved.getDouble("speed"), 0);
    }

    @Test
    public void malformedYamlAndInvalidValuesDoNotOverwriteOriginalFile() throws Exception {
        var file = this.folder.resolve("test.yml");
        TestConfig config = new TestConfig(plugin());
        for (String content : List.of("speed: [unterminated\n", "speed: invalid\nold: kept\n")) {
            Files.writeString(file, content);
            assertThrows(ConfigException.class, config::load);
            assertEquals(content, Files.readString(file));
        }
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(this.folder.toFile());
        when(plugin.getResource("test.yml")).thenAnswer(call -> new ByteArrayInputStream(
                "speed: 1.0\ninterval: 10\ncustom: {}\n".getBytes(StandardCharsets.UTF_8)
        ));
        return plugin;
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(content);
        return config;
    }

    private static final class TestConfig extends AbstractYamlConfig {
        private TestConfig(JavaPlugin plugin) {
            super(plugin, "test.yml");
        }

        @Override
        protected void checkConfig() {
            doubleOrDefault("speed", 1);
        }

        @Override
        protected Set<String> additionalConfigPaths() {
            return Set.of("custom.*");
        }
    }
}
