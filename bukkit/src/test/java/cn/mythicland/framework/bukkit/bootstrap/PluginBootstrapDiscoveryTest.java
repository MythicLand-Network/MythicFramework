package cn.mythicland.framework.bukkit.bootstrap;

import cn.mythicland.framework.bukkit.bootstrap.PluginBootstrap;
import cn.mythicland.framework.component.ComponentScanner;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the JDK-only scanner can discover compiled Lib component infrastructure.
 */
class PluginBootstrapDiscoveryTest {

    @Test
    void scannerFindsClassesFromAnExplodedGradleClasspath() {
        List<Class<?>> classes = ComponentScanner.scan(
                getClass().getClassLoader(),
                "cn.mythicland.framework.bukkit.bootstrap"
        );

        assertTrue(classes.contains(PluginBootstrap.class));
    }

    @Test
    void scannerSkipsClassesWhoseOptionalDependencyIsAbsent() {
        ClassLoader classLoader = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new NoClassDefFoundError("optional/dependency/Type");
            }
        };

        assertNull(ComponentScanner.loadScannedClass(classLoader, "optional.Component"));
    }
}
