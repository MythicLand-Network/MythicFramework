package cn.mythicland.framework.bukkit.bootstrap;

import cn.mythicland.framework.bukkit.command.BukkitCommandBridge;
import cn.mythicland.framework.bukkit.command.CommandBinding;
import cn.mythicland.framework.bukkit.command.CommandRouteCompiler;
import cn.mythicland.framework.bukkit.component.ListenerComponent;
import cn.mythicland.framework.bukkit.component.PluginComponents;
import cn.mythicland.framework.bukkit.component.ServiceComponent;
import cn.mythicland.framework.bukkit.config.ConfigSupport;
import cn.mythicland.framework.bukkit.config.ConfigView;
import cn.mythicland.framework.bukkit.config.ConfigurableComponent;
import cn.mythicland.framework.bukkit.config.annotation.ConfigComponent;
import cn.mythicland.framework.bukkit.task.PluginTaskScope;
import cn.mythicland.framework.component.ManagedLifecycle;

import java.util.*;
import java.util.logging.Level;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Discovers, constructs, registers, reloads, and closes one plugin's Lib components.
 */
public final class PluginBootstrap implements AutoCloseable {

    private final JavaPlugin plugin;
    private final PluginComponents components;
    private final List<ManagedLifecycle> lifecycles = new ArrayList<>();
    private final List<ConfigurableComponent> configurations = new ArrayList<>();
    private BukkitRegistration listeners = new BukkitRegistration();
    private BukkitRegistration services = new BukkitRegistration();
    private BukkitRegistration commands = new BukkitRegistration();
    private final List<GlobalCommandTakeover> commandTakeovers = new ArrayList<>();
    private PluginTaskScope taskScope;
    private boolean enabled;

    /**
     * Creates a package-scoped plugin bootstrap.
     *
     * @param plugin     plugin being bootstrapped
     * @param components configured plugin component graph
     */
    public PluginBootstrap(JavaPlugin plugin, PluginComponents components) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.components = Objects.requireNonNull(components, "components");
        this.taskScope = components.resolve(PluginTaskScope.class);
    }

    /**
     * Enables all discovered components.
     *
     * <p>Service providers are constructed first, lifecycle components are enabled in dependency
     * order, and only then are services, listeners, and commands published. The method must run
     * on the Bukkit primary thread during plugin startup.</p>
     *
     * @throws IllegalStateException if a component cannot be constructed or registered
     */
    public void enable() {
        if (enabled) throw new IllegalStateException("Plugin bootstrap is already enabled");
        taskScope = components.resolve(PluginTaskScope.class);
        listeners = new BukkitRegistration();
        services = new BukkitRegistration();
        commands = new BukkitRegistration();
        try {
            configurations.addAll(prepareConfigurations());
            ConfigView configuration = ConfigSupport.loadDefaultView(plugin);
            for (ConfigurableComponent component : configurations) component.reload(configuration);
            List<PendingService> pendingServices = prepareServices();
            lifecycles.addAll(components.resolveAllOrdered(ManagedLifecycle.class));
            for (ManagedLifecycle lifecycle : lifecycles) lifecycle.load();
            registerServices(pendingServices);
            registerListeners();
            registerCommands();
            enabled = true;
        } catch (RuntimeException exception) {
            disableInternal();
            throw exception;
        }
    }

    /**
     * Reloads every lifecycle component in dependency order.
     *
     * <p>Reload is a Bukkit lifecycle operation and must run on the primary thread. Components
     * decide which mutable resources are reloadable.</p>
     *
     * @throws IllegalStateException if this bootstrap is not enabled
     */
    public void reload() {
        if (!enabled) throw new IllegalStateException("Plugin bootstrap is not enabled");
        ConfigView configuration = ConfigSupport.reloadView(plugin);
        for (ConfigurableComponent component : configurations) component.reload(configuration);
        for (ManagedLifecycle lifecycle : lifecycles) lifecycle.reload();
    }

    /**
     * Disables listeners, command overrides, services, and lifecycle components.
     */
    @Override
    public void close() {
        disable();
    }

    /**
     * Disables the bootstrap. The operation is idempotent for Bukkit shutdown handling.
     *
     * <p>This method must run on the Bukkit primary thread because component cleanup may touch
     * Bukkit listeners, commands, services, and scoreboards.</p>
     */
    public void disable() {
        if (!enabled && lifecycles.isEmpty() && services.isClosed() && commandTakeovers.isEmpty()) {
            taskScope.close();
            components.clear();
            return;
        }
        disableInternal();
    }

    /**
     * Resolves a component for a plugin-owned facade or test.
     *
     * @param type component type
     * @param <T>  component type
     * @return cached component
     */
    public <T> T resolve(Class<T> type) {
        return components.resolve(type);
    }

    private List<PendingService> prepareServices() {
        Set<Class<?>> contracts = new HashSet<>();
        List<PendingService> pending = new ArrayList<>();
        for (Class<?> componentType : components.annotatedTypes(ServiceComponent.class)) {
            ServiceComponent annotation = componentType.getAnnotation(ServiceComponent.class);
            Object provider = components.resolve(componentType);
            Class<?> contract = annotation.value();
            if (!contract.isInstance(provider)) {
                throw new IllegalStateException(
                        "Service component does not implement " + contract.getName() + ": " + componentType.getName()
                );
            }
            if (!contracts.add(contract)) {
                throw new IllegalStateException("Multiple Lib service components implement: " + contract.getName());
            }
            pending.add(new PendingService(contract, provider, annotation.priority()));
        }
        return List.copyOf(pending);
    }

    private List<ConfigurableComponent> prepareConfigurations() {
        List<ConfigurableComponent> result = new ArrayList<>();
        for (Class<?> componentType : components.annotatedTypes(ConfigComponent.class)) {
            Object component = components.resolve(componentType);
            if (!(component instanceof ConfigurableComponent configurable)) {
                throw new IllegalStateException(
                        "Config component must implement ConfigurableComponent: " + componentType.getName()
                );
            }
            result.add(configurable);
        }
        return List.copyOf(result);
    }

    private void registerServices(List<PendingService> pendingServices) {
        for (PendingService pending : pendingServices) {
            registerService(pending.contract(), pending.provider(), pending.priority());
        }
    }

    private void registerListeners() {
        for (Class<?> componentType : components.annotatedTypes(ListenerComponent.class)) {
            Object component = components.resolve(componentType);
            if (!(component instanceof Listener listener)) {
                throw new IllegalStateException(
                        "Listener component is not a Bukkit Listener: " + componentType.getName());
            }
            listeners.registerListener(plugin, listener);
        }
    }

    private void registerCommands() {
        Set<String> commandNames = new HashSet<>();
        for (Class<?> componentType : components.annotatedTypes(CommandBinding.class)) {
            Object value = components.resolve(componentType);
            CommandBinding annotation = componentType.getAnnotation(CommandBinding.class);
            String commandName = annotation == null ? null : annotation.value().trim();
            String commandKey = commandName == null ? null : commandName.toLowerCase(Locale.ROOT);
            if (commandName == null || commandName.isBlank() || !commandNames.add(commandKey)) {
                throw new IllegalStateException("Invalid or duplicate Lib command component: " + commandName);
            }
            PluginCommand command = plugin.getCommand(commandName);
            if (command == null) throw new IllegalStateException("Command is missing from plugin.yml: " + commandName);
            BukkitCommandBridge router = new BukkitCommandBridge(plugin,
                    new CommandRouteCompiler(components.resolver()).compileBound(value));
            commands.registerCommand(command, router, router);
            if (annotation.takeOverGlobalMapping()) {
                GlobalCommandTakeover globalTakeover = new GlobalCommandTakeover(plugin, commandName);
                globalTakeover.install(command);
                commandTakeovers.add(globalTakeover);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerService(Class<?> contract, Object provider, ServicePriority priority) {
        services.registerService(plugin, (Class) contract, provider, priority);
    }

    private void disableInternal() {
        taskScope.close();
        listeners.close();
        for (int index = commandTakeovers.size() - 1; index >= 0; index--) {
            commandTakeovers.get(index).restore();
        }
        commandTakeovers.clear();
        commands.close();
        services.close();
        for (int index = lifecycles.size() - 1; index >= 0; index--) {
            ManagedLifecycle lifecycle = lifecycles.get(index);
            try {
                lifecycle.unload();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Lib component shutdown failed: " + lifecycle.getClass().getName(),
                        exception
                );
            }
        }
        lifecycles.clear();
        configurations.clear();
        enabled = false;
        components.clear();
    }

    private record PendingService(Class<?> contract, Object provider, ServicePriority priority) {
    }
}
