package cn.mythicland.framework.component.external;

import cn.mythicland.framework.component.ComponentContainer;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.*;

class ExternalComponentsTest {
    @Test
    void discoversExternalRolesAndInjectsTheSelectedSingleton() {
        ComponentContainer components = new ComponentContainer();
        components.discover(getClass().getClassLoader(), getClass().getPackageName(),
                type -> type.isAnnotationPresent(Component.class), type -> type.isAnnotationPresent(Preferred.class));

        components.resolve(OtherService.class);
        Consumer consumer = components.resolve(Consumer.class);
        assertInstanceOf(PreferredService.class, consumer.service);
        assertSame(consumer.service, components.resolve(Service.class));
        assertSame(consumer, components.resolve(Consumer.class));
    }

    @Test
    void reportsCyclesInExternallyDiscoveredComponents() {
        ComponentContainer components = new ComponentContainer();
        components.discover(getClass().getClassLoader(), getClass().getPackageName(),
                type -> type.isAnnotationPresent(Component.class), type -> type.isAnnotationPresent(Preferred.class));

        assertThrows(IllegalStateException.class, () -> components.resolve(CycleA.class));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Component {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface Preferred {}

    interface Service {}

    @Component
    abstract static class AbstractService implements Service {}

    @Component
    static class Consumer {
        final Service service;
        Consumer(Service service) { this.service = service; }
    }

    @Component
    static class OtherService implements Service {}

    @Component
    @Preferred
    static class PreferredService implements Service {}

    @Component
    static class CycleA { CycleA(CycleB dependency) {} }

    @Component
    static class CycleB { CycleB(CycleA dependency) {} }
}
