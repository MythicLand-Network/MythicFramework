package cn.mythicland.framework.component.capabilities;

import cn.mythicland.framework.component.ComponentArguments;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.framework.component.ComponentRole;
import cn.mythicland.framework.component.ComponentScope;
import cn.mythicland.framework.component.ComponentSet;
import cn.mythicland.framework.component.DefaultImplementation;
import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.component.RuntimeArgument;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ComponentCapabilitiesTest {
    @Test
    void resolvesRolesSetsDefaultsAndTypedPrototypeArguments() {
        ComponentContainer container = new ComponentContainer();
        container.scan("cn.mythicland.framework.component.capabilities");

        assertInstanceOf(PrimaryService.class, container.resolve(Service.class));
        assertEquals(2, container.resolve(ServiceConsumer.class).services.values().size());
        assertEquals("runtime", container.create(
                RuntimeComponent.class,
                ComponentArguments.of("runtime")
        ).value);
    }

    interface Service {
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ComponentRole(contract = Service.class)
    @interface ServiceComponent {
    }

    @ServiceComponent
    @DefaultImplementation
    static final class PrimaryService implements Service {
    }

    @ServiceComponent
    static final class SecondaryService implements Service {
    }

    @InjectComponent
    static final class ServiceConsumer {
        private final ComponentSet<Service> services;

        ServiceConsumer(ComponentSet<Service> services) {
            this.services = services;
        }
    }

    @InjectComponent(scope = ComponentScope.PROTOTYPE)
    static final class RuntimeComponent {
        private final String value;

        RuntimeComponent(@RuntimeArgument String value) {
            this.value = value;
        }
    }
}
