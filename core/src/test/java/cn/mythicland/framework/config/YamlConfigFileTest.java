package cn.mythicland.framework.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlConfigFileTest {
    @TempDir
    Path directory;

    @Test
    void copiesMissingResourceWithoutOverwritingExistingFile() throws IOException {
        Path resources = Files.createDirectory(this.directory.resolve("resources"));
        Files.writeString(resources.resolve("defaults.yml"), "names: [Lobby, Login]\n");
        Path file = this.directory.resolve("config/config.yml");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()})) {
            assertEquals(List.of("Lobby", "Login"),
                    YamlConfigFile.load(file, loader, "defaults.yml").stringList("names"));
            Files.writeString(file, "names: []\n");
            assertEquals(List.of(), YamlConfigFile.load(file, loader, "defaults.yml").stringList("names"));
        }
    }

    @Test
    void rejectsWrongTypesAndBlankEntries() throws IOException {
        Path file = this.directory.resolve("config.yml");
        for (String invalid : List.of("names: value", "names: [5]", "names: [' ']", "other: []")) {
            Files.writeString(file, invalid);
            YamlConfigFile config = YamlConfigFile.load(file, this.getClass().getClassLoader(), "unused.yml");
            assertThrows(IllegalArgumentException.class, () -> config.stringList("names"));
        }
    }

    @Test
    void rejectsDuplicateKeys() throws IOException {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, "names: [Lobby]\nnames: [Login]\n");
        assertThrows(YAMLException.class,
                () -> YamlConfigFile.load(file, this.getClass().getClassLoader(), "unused.yml"));
    }

    @Test
    void readsNestedTypedSettingsAndOnlyDefaultsMissingValues() throws IOException {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, "reconnect:\n  enabled: false\n  seconds: 15\n");
        YamlConfigFile config = YamlConfigFile.load(file, this.getClass().getClassLoader(), "unused.yml");
        assertFalse(config.booleanValue("reconnect.enabled", true));
        assertEquals(15, config.intValue("reconnect.seconds", 5));
        assertEquals(10, config.intValue("missing.seconds", 10));
        assertThrows(IllegalArgumentException.class, () -> config.booleanValue("reconnect.seconds", true));
        assertThrows(IllegalArgumentException.class, () -> config.intValue("reconnect.enabled", 5));
        for (String invalid : List.of("reconnect: []", "reconnect: null", "reconnect: {seconds: null}",
                "reconnect: {seconds: 1.5}", "reconnect: {seconds: 2147483648}")) {
            Files.writeString(file, invalid);
            YamlConfigFile malformed = YamlConfigFile.load(file, this.getClass().getClassLoader(), "unused.yml");
            assertThrows(IllegalArgumentException.class, () -> malformed.intValue("reconnect.seconds", 5));
        }
    }
}
