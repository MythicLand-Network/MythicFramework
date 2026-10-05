package cn.mythicland.framework.bukkit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import cn.mythicland.framework.bukkit.config.annotation.ConfigProperty;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ConfigBinderTest {
    @Test
    public void bind_shouldAssignSupportedTypesWithoutInferringDefaults() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("poolSize", 24);
        configuration.set("enabled", true);
        configuration.set("mode", "sqlite");
        configuration.set("names", List.of("first", "second"));
        configuration.set("ratio", 0.75D);
        BindableConfig target = new BindableConfig();

        new ConfigBinder("test.yml", configuration).bind(target);

        assertEquals(24, target.poolSize);
        assertEquals(true, target.enabled);
        assertEquals(DatabaseMode.SQLITE, target.mode);
        assertEquals(List.of("first", "second"), target.names);
        assertEquals(0.75D, target.ratio, 0.0D);
    }

    @Test
    public void bind_shouldFailWhenValueIsMissing() {
        try {
            new ConfigBinder("test.yml", new YamlConfiguration()).bind(new BindableConfig());
            fail("Expected missing configuration value to fail");
        } catch (ConfigException exception) {
            assertEquals(true, exception.getMessage().contains("poolSize"));
        }
    }

    @Test
    public void bind_shouldRejectDuplicatePathsAcrossConfigClassHierarchy() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("shared", 1);

        try {
            new ConfigBinder("test.yml", configuration).bind(new DuplicatePathConfig());
            fail("Expected duplicate configuration paths to fail");
        } catch (ConfigException exception) {
            assertEquals(true, exception.getMessage().contains("duplicate configuration path"));
        }
    }

    private enum DatabaseMode {
        POSTGRES,
        SQLITE
    }

    private static final class BindableConfig {
        @ConfigProperty(path = "poolSize")
        private int poolSize;
        @ConfigProperty(path = "enabled")
        private boolean enabled;
        @ConfigProperty(path = "mode")
        private DatabaseMode mode;
        @ConfigProperty(path = "names")
        private List<String> names;
        @ConfigProperty(path = "ratio")
        private double ratio;
    }

    private static class DuplicatePathBaseConfig {
        @ConfigProperty(path = "shared")
        private int baseValue;
    }

    private static final class DuplicatePathConfig extends DuplicatePathBaseConfig {
        @ConfigProperty(path = "shared")
        private int childValue;
    }
}
