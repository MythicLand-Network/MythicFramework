package cn.mythicland.framework.bukkit.config;

import cn.mythicland.framework.bukkit.config.annotation.ConfigProperty;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordConfigBinderTest {
    @Test
    void invalidRecordValuesUseDeclaredDefaultsAndReportWarnings() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("size", -1);
        configuration.set("label", "  Test  ");
        List<String> warnings = new ArrayList<>();

        Settings settings = RecordConfigBinder.bind(configuration, warnings::add, Settings.class);

        assertEquals(3, settings.size());
        assertEquals("Test", settings.label());
        assertEquals(1, warnings.size());
    }

    private record Settings(
            @ConfigProperty(path = "size", defaultValue = "3", positive = true) int size,
            @ConfigProperty(path = "label", defaultValue = "Default", nonBlank = true) String label
    ) {
    }
}
