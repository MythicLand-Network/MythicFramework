package cn.mythicland.framework.bukkit.config;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractYamlConfigurationTest {
    @TempDir
    private Path dataDirectory;

    @Test
    void reloadYamlConfigurationDoesNotDispatchBackToOverriddenReload() throws Exception {
        Path file = this.dataDirectory.resolve("config.yml");
        Files.writeString(file, "value: before\n");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(this.dataDirectory.toFile());
        TestConfiguration configuration = new TestConfiguration(plugin);
        Files.writeString(file, "value: after\n");

        configuration.reload();

        assertEquals(1, configuration.reloadCount);
        assertEquals("after", configuration.getYamlConfiguration().getString("value"));
    }

    private static final class TestConfiguration extends AbstractYamlConfiguration {
        private int reloadCount;

        private TestConfiguration(Plugin plugin) {
            super(plugin, "config.yml");
        }

        @Override
        public void reload() {
            this.reloadYamlConfiguration();
            this.reloadCount++;
        }
    }
}
