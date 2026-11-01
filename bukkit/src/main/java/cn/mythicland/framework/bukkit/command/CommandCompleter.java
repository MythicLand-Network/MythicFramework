package cn.mythicland.framework.bukkit.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that supplies tab completions for one {@link CommandRoute}.
 *
 * <p>The method must have the shape {@code List<String> method(CommandContext)}. Its value uses
 * the same command path as {@link CommandRoute#path()}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CommandCompleter {

    /**
     * Returns the command path, or an empty value for the root action.
     *
     * @return command name
     */
    String value() default "";
}
