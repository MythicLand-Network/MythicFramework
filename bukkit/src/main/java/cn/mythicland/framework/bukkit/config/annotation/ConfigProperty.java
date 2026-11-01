package cn.mythicland.framework.bukkit.config.annotation;

import java.lang.annotation.*;

/**
 * Binds a configuration field or record component to a stable YAML path.
 * The default and validation options apply to record binding; mutable-field binding is strict.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
public @interface ConfigProperty {
    /**
     * Absolute path relative to the configuration file root.
     */
    String path();

    /** Record binding default and validation options; field binding remains strict. */
    String defaultValue() default "";
    boolean nonBlank() default false;
    boolean trim() default true;
    boolean positive() default false;
    boolean nonNegative() default false;
    boolean finite() default true;
}
