package cn.mythicland.framework.runtime;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectiveChildFirstClassLoaderTest {
    @Test
    void shouldApplyConfiguredClassAndPackageBoundaries() throws Exception {
        try (SelectiveChildFirstClassLoader classLoader = new SelectiveChildFirstClassLoader(
                new URL[0],
                this.getClass().getClassLoader(),
                List.of("cn.mythicland.plugin.", "org.example."),
                List.of("cn.mythicland.plugin.Plugin"),
                List.of("cn.mythicland.plugin.bootstrap.")
        )) {
            assertTrue(classLoader.shouldUseChildFirst("cn.mythicland.plugin.Service"));
            assertTrue(classLoader.shouldUseChildFirst("org.example.Library"));
            assertFalse(classLoader.shouldUseChildFirst("cn.mythicland.plugin.Plugin"));
            assertFalse(classLoader.shouldUseChildFirst("cn.mythicland.plugin.bootstrap.Loader"));
            assertFalse(classLoader.shouldUseChildFirst("java.lang.String"));
        }
    }

    @Test
    void shouldAllowPluginSpecificParentFirstPolicy() throws Exception {
        try (SelectiveChildFirstClassLoader classLoader = new SelectiveChildFirstClassLoader(
                new URL[0],
                this.getClass().getClassLoader(),
                List.of("example."),
                List.of(),
                List.of(),
                className -> className.endsWith("Contract")
        )) {
            assertFalse(classLoader.shouldUseChildFirst("example.PublicContract"));
            assertTrue(classLoader.shouldUseChildFirst("example.InternalService"));
        }
    }
}
