package cn.mythicland.framework.bukkit.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YamlConfigTest {
    @TempDir
    Path directory;

    @Test
    void reloading_shouldKeepDynamicEditsAndDeletionsWithoutRestoringBundledExamples() throws Exception {
        Files.writeString(this.directory.resolve("menus.yml"), "menus:\n  custom:\n    title: saved\n");
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(this.directory.toFile());
        when(plugin.getResource("menus.yml")).thenAnswer(ignored -> new ByteArrayInputStream(
                "menus:\n  custom:\n    title: default\n    items:\n      example: bundled\n  game:\n    title: bundled\n".getBytes(
                        StandardCharsets.UTF_8)
        ));
        YamlConfig config = new YamlConfig(plugin, "menus.yml");
        config.load();
        assertEquals("saved", config.data().getString("menus.custom.title"));
        config.data().set("menus.custom.title", "edited");
        config.save();
        config.load();
        assertEquals("edited", config.data().getString("menus.custom.title"));
        assertFalse(config.data().contains("menus.game"));
        assertFalse(config.data().contains("menus.custom.items.example"));
        config.data().set("menus.custom", null);
        config.save();
        config.load();
        assertFalse(config.data().contains("menus.custom"));
        assertFalse(config.data().contains("menus.game"));
    }
}
