package cn.mythicland.framework.bukkit.component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.bukkit.plugin.ServicePriority;

/**
 * Marks an injected component that should be registered through Bukkit's ServicesManager.
 * Lib constructs the provider before lifecycle startup and publishes it after lifecycle
 * components have enabled successfully.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ServiceComponent {

    /**
     * Returns the service contract implemented by the component.
     *
     * @return service contract type
     */
    Class<?> value();

    /**
     * Returns the Bukkit service priority used during registration.
     *
     * @return service priority
     */
    ServicePriority priority() default ServicePriority.Normal;
}
