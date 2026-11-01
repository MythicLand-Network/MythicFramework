package cn.mythicland.framework.bukkit.component;

import cn.mythicland.framework.bukkit.command.CommandBinding;
import cn.mythicland.framework.bukkit.config.annotation.ConfigComponent;
import cn.mythicland.framework.bukkit.task.PluginTaskScope;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.framework.component.ComponentResolver;
import cn.mythicland.framework.component.DefaultImplementation;
import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.component.LifecycleComponent;
import cn.mythicland.framework.component.LifecycleSorter;
import cn.mythicland.framework.component.ManagedLifecycle;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * Adapts Bukkit component roles to the shared framework container.
 */
public final class PluginComponents {
    private final ComponentContainer components = new ComponentContainer();

    /**
     * Creates a plugin-scoped container and discovers components in the supplied package.
     */
    public PluginComponents(JavaPlugin plugin, String basePackage, Consumer<ComponentContainer> bindings) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(bindings, "bindings");
        String packageName = Objects.requireNonNull(basePackage, "basePackage").trim();
        if (packageName.isBlank()) throw new IllegalArgumentException("basePackage cannot be blank");
        components.bind(plugin);
        components.bind(JavaPlugin.class, plugin);
        components.bind(Server.class, plugin.getServer());
        components.bind(BukkitScheduler.class, plugin.getServer().getScheduler());
        components.bind(Logger.class, plugin.getLogger());
        bindings.accept(components);
        components.bind(PluginTaskScope.class, new PluginTaskScope(plugin));
        components.bind(PluginComponents.class, this);
        components.discover(plugin.getClass().getClassLoader(), packageName,
                PluginComponents::isManaged, type -> type.isAnnotationPresent(DefaultImplementation.class));
    }

    private static boolean isManaged(Class<?> type) {
        return type.isAnnotationPresent(InjectComponent.class)
                || type.isAnnotationPresent(ListenerComponent.class)
                || type.isAnnotationPresent(CommandBinding.class)
                || type.isAnnotationPresent(ServiceComponent.class)
                || type.isAnnotationPresent(ConfigComponent.class)
                || type.isAnnotationPresent(LifecycleComponent.class)
                || ManagedLifecycle.class.isAssignableFrom(type);
    }

    /**
     * Returns the shared framework resolver for component-aware integrations.
     */
    public ComponentResolver resolver() {
        return components;
    }

    /**
     * Resolves a component or registered dependency as a singleton.
     */
    public <T> T resolve(Class<T> requestedType) {
        return components.resolve(requestedType);
    }

    /**
     * Resolves matching components in class-name order.
     */
    public <T> List<T> resolveAll(Class<T> contract) {
        List<T> result = new ArrayList<>();
        for (Class<?> type : candidateTypes(contract)) result.add(contract.cast(components.resolve(type)));
        return List.copyOf(result);
    }

    /**
     * Resolves matching lifecycle components in their declared dependency order.
     */
    public <T> List<T> resolveAllOrdered(Class<T> contract) {
        List<T> result = new ArrayList<>();
        for (Class<?> type : LifecycleSorter.sort(candidateTypes(contract))) {
            result.add(contract.cast(components.resolve(type)));
        }
        return List.copyOf(result);
    }

    /**
     * Returns discovered component types carrying the given annotation.
     */
    public List<Class<?>> annotatedTypes(Class<? extends Annotation> annotation) {
        Objects.requireNonNull(annotation, "annotation");
        return components.managedTypes().stream().filter(type -> type.isAnnotationPresent(annotation)).toList();
    }

    /**
     * Clears the component instances and scan state.
     */
    public void clear() {
        components.clear();
    }

    private <T> List<Class<?>> candidateTypes(Class<T> contract) {
        Objects.requireNonNull(contract, "contract");
        return components.managedTypes().stream().filter(contract::isAssignableFrom).toList();
    }
}
