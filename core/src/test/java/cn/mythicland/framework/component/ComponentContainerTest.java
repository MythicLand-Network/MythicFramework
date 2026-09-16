package cn.mythicland.framework.component;

import cn.mythicland.framework.component.fixture.ConfigurationFixture;
import cn.mythicland.framework.component.fixture.ServiceFixture;
import cn.mythicland.framework.component.lifecyclefailure.RollbackFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentContainerTest {
    private ComponentContainer container;

    @BeforeEach
    void setUp() {
        ConfigurationFixture.EVENTS.clear();
        this.container = new ComponentContainer();
    }

    @Test
    void scanInjectsDependenciesAndRunsLifecycleInPhaseOrder() {
        this.container.scan("cn.mythicland.framework.component.fixture");

        ServiceFixture service = this.container.resolve(ServiceFixture.class);
        assertSame(this.container.resolve(ConfigurationFixture.class), service.configuration());
        assertEquals(
                java.util.List.of("configuration.load", "service.load"),
                ConfigurationFixture.EVENTS
        );

        this.container.unload();

        assertEquals(
                java.util.List.of(
                        "configuration.load",
                        "service.load",
                        "service.unload",
                        "configuration.unload"
                ),
                ConfigurationFixture.EVENTS
        );
    }

    @Test
    void scanExcludesRejectedComponentTypes() {
        this.container.scan(
                "cn.mythicland.framework.component.fixture",
                type -> type != ServiceFixture.class
        );

        assertThrows(IllegalStateException.class, () -> this.container.resolve(ServiceFixture.class));
    }

    @Test
    void discoverIndexesComponentsWithoutValidatingUnresolvedDependencies() {
        this.container.discover(
                "cn.mythicland.framework.component.fixture",
                type -> type == ServiceFixture.class
        );

        assertEquals(java.util.List.of(ServiceFixture.class), this.container.managedTypes());
        assertThrows(IllegalStateException.class, () -> this.container.resolve(ServiceFixture.class));
    }

    @Test
    void scanKeepsExplicitlyBoundManagedComponentsOutsideTheSourceFilter() {
        ConfigurationFixture configuration = new ConfigurationFixture();
        this.container.bind(ConfigurationFixture.class, configuration);

        this.container.scan(
                "cn.mythicland.framework.component.fixture",
                type -> type != ConfigurationFixture.class
        );

        assertSame(configuration, this.container.resolve(ConfigurationFixture.class));
        assertSame(configuration, this.container.resolve(ServiceFixture.class).configuration());
        assertEquals(java.util.List.of("configuration.load", "service.load"), ConfigurationFixture.EVENTS);
    }

    @Test
    void liveContainerCannotBeScannedAgain() {
        this.container.scan("cn.mythicland.framework.component.fixture");

        assertThrows(
                IllegalStateException.class,
                () -> this.container.scan("cn.mythicland.framework.component.fixture")
        );
    }

    @Test
    void failedLifecycleLoadRollsBackPreviouslyLoadedComponents() {
        RollbackFixture.EVENTS.clear();

        assertThrows(
                IllegalStateException.class,
                () -> this.container.scan("cn.mythicland.framework.component.lifecyclefailure")
        );

        assertEquals(java.util.List.of("rollback.load", "rollback.unload"), RollbackFixture.EVENTS);
        assertThrows(IllegalStateException.class, () -> this.container.resolve(RollbackFixture.class));
    }
}
