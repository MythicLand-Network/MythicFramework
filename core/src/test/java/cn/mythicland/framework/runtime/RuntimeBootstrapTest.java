package cn.mythicland.framework.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeBootstrapTest {
    private static final String FIXTURE_CLASS =
            "cn.mythicland.framework.runtime.fixture.RuntimeBootstrapFixture";

    @TempDir
    private Path dataDirectory;

    @Test
    void lifecycle_shouldRunInIsolatedRuntimeClassLoader() {
        RuntimeBootstrap bootstrap = this.newBootstrap();

        bootstrap.load();
        assertEquals("loaded", bootstrap.invoke("state", String.class));
        ClassLoader contextClassLoader = bootstrap.invoke("contextClassLoader", ClassLoader.class);
        assertNotSame(this.getClass().getClassLoader(), contextClassLoader);

        bootstrap.enable();
        assertEquals("enabled", bootstrap.invoke("state", String.class));
        bootstrap.reload();
        assertEquals("reloaded", bootstrap.invoke("state", String.class));
        bootstrap.disable();

        assertThrows(IllegalStateException.class, () -> bootstrap.invoke("state", String.class));
    }

    @Test
    void load_shouldCloseClassLoaderWhenRuntimeInitializationThrowsError() throws Exception {
        RuntimeBootstrap bootstrap = RuntimeBootstrap.builder(
                        new RuntimeBootstrapFixturePlugin(),
                        "cn.mythicland.framework.runtime.fixture.RuntimeBootstrapErrorFixture"
                )
                .runtimeName("error fixture")
                .libraries(
                        this.dataDirectory,
                        "runtime-bootstrap-test-libraries.properties",
                        Set.of("test")
                )
                .loggers(ignored -> {
                }, ignored -> {
                })
                .childFirstPackages(List.of("cn.mythicland.framework.runtime.fixture."))
                .parentFirstClasses(List.of(RuntimeBootstrapFixturePlugin.class.getName()))
                .build();

        assertThrows(AssertionError.class, bootstrap::load);
        Field classLoaderField = RuntimeBootstrap.class.getDeclaredField("classLoader");
        classLoaderField.setAccessible(true);
        assertNull(classLoaderField.get(bootstrap));
    }

    private RuntimeBootstrap newBootstrap() {
        return RuntimeBootstrap.builder(new RuntimeBootstrapFixturePlugin(), FIXTURE_CLASS)
                .runtimeName("fixture")
                .libraries(
                        this.dataDirectory,
                        "runtime-bootstrap-test-libraries.properties",
                        Set.of("test")
                )
                .loggers(ignored -> {
                }, ignored -> {
                })
                .childFirstPackages(List.of("cn.mythicland.framework.runtime.fixture."))
                .parentFirstClasses(List.of(RuntimeBootstrapFixturePlugin.class.getName()))
                .lifecycleMethods("prepare", "start", "refresh", "stop")
                .build();
    }
}
