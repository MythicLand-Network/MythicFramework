package cn.mythicland.framework.component;

import org.reflections.Reflections;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ComponentContainer implements ComponentResolver {
    private final Map<Class<?>, Object> components = new LinkedHashMap<>();
    private final List<ManagedLifecycle> loadedLifecycles = new ArrayList<>();
    private final Set<Class<?>> managedTypes = new LinkedHashSet<>();
    private final ThreadLocal<List<Class<?>>> resolvingTypes = ThreadLocal.withInitial(ArrayList::new);

    public ComponentContainer bind(Object dependency) {
        if (dependency == null) throw new IllegalArgumentException("Component binding cannot be null");
        this.components.put(dependency.getClass(), dependency);
        return this;
    }

    public ComponentContainer bind(Class<?> contract, Object dependency) {
        if (contract == null || dependency == null) {
            throw new IllegalArgumentException("Component contract and binding cannot be null");
        }
        if (!contract.isInstance(dependency)) {
            throw new IllegalArgumentException(dependency.getClass().getName() + " does not implement " + contract.getName());
        }
        this.components.put(contract, dependency);
        return this;
    }

    public void scan(String basePackage) {
        Reflections reflections = new Reflections(basePackage);
        this.managedTypes.clear();
        this.managedTypes.addAll(reflections.getTypesAnnotatedWith(InjectComponent.class));
        for (Class<? extends Annotation> role : componentRoles(reflections)) {
            Class<?> contract = role.getAnnotation(ComponentRole.class).contract();
            Set<Class<?>> roleTypes = reflections.getTypesAnnotatedWith(role);
            for (Class<?> roleType : roleTypes) {
                if (!contract.isAssignableFrom(roleType)) {
                    throw new IllegalStateException(roleType.getName() + " does not implement " + contract.getName());
                }
            }
            this.managedTypes.addAll(roleTypes);
        }
        this.managedTypes.removeIf(type -> type.isInterface() || Modifier.isAbstract(type.getModifiers()));

        Set<Class<?>> pending = new LinkedHashSet<>(this.managedTypes);
        pending.removeIf(type -> componentScope(type) == ComponentScope.PROTOTYPE);
        pending.removeIf(type -> this.find(type) != null);
        boolean progressed;
        do {
            progressed = false;
            for (Class<?> type : ordered(pending)) {
                if (this.find(type) != null) {
                    pending.remove(type);
                    progressed = true;
                    continue;
                }
                Object instance = this.tryCreate(type, ComponentArguments.empty());
                if (instance == null) continue;
                this.components.put(type, instance);
                pending.remove(type);
                progressed = true;
            }
        } while (progressed && !pending.isEmpty());

        if (!pending.isEmpty()) {
            throw new IllegalStateException("Unresolved component dependencies: " + pending.stream()
                    .map(Class::getName)
                    .sorted()
                    .toList());
        }
        this.loadLifecycles();
    }

    @Override
    public <T> T resolve(Class<T> requestedType) {
        Object component = this.find(requestedType);
        if (component != null) return requestedType.cast(component);
        Class<? extends T> implementation = this.resolveImplementation(requestedType);
        T created = this.instantiateWithCycleGuard(implementation, ComponentArguments.empty());
        if (componentScope(implementation) == ComponentScope.SINGLETON) {
            this.components.put(implementation, created);
        }
        return created;
    }

    public <T> T create(Class<T> requestedType, ComponentArguments<?> arguments) {
        Class<? extends T> implementation = this.resolveImplementation(requestedType);
        return this.instantiateWithCycleGuard(implementation, arguments);
    }

    public <A extends Annotation, T> List<T> resolveAll(Class<A> mark, Class<T> contract) {
        return ordered(this.managedTypes.stream()
                .filter(type -> type.isAnnotationPresent(mark))
                .filter(contract::isAssignableFrom)
                .toList()).stream()
                .map(type -> contract.cast(this.resolve(type)))
                .toList();
    }

    public <T> List<T> componentsAnnotatedWith(Class<? extends Annotation> annotation, Class<T> contract) {
        return this.components.values().stream()
                .filter(component -> component.getClass().isAnnotationPresent(annotation))
                .filter(contract::isInstance)
                .map(contract::cast)
                .distinct()
                .sorted(Comparator.comparing(component -> component.getClass().getName()))
                .toList();
    }

    public List<Class<?>> managedTypes() {
        return ordered(this.managedTypes);
    }

    public void reload() {
        for (ManagedLifecycle lifecycle : this.loadedLifecycles) lifecycle.reload();
    }

    public void unload() {
        RuntimeException failure = null;
        try {
            for (int index = this.loadedLifecycles.size() - 1; index >= 0; index--) {
                try {
                    this.loadedLifecycles.get(index).unload();
                } catch (RuntimeException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
        } finally {
            this.loadedLifecycles.clear();
            this.components.clear();
            this.managedTypes.clear();
        }
        if (failure != null) throw failure;
    }

    private void loadLifecycles() {
        List<ManagedLifecycle> lifecycles = this.components.values().stream()
                .filter(ManagedLifecycle.class::isInstance)
                .map(ManagedLifecycle.class::cast)
                .distinct()
                .sorted(Comparator.comparingInt(this::phaseOrdinal)
                        .thenComparing(lifecycle -> lifecycle.getClass().getName()))
                .toList();
        try {
            for (ManagedLifecycle lifecycle : lifecycles) {
                lifecycle.load();
                this.loadedLifecycles.add(lifecycle);
            }
        } catch (RuntimeException | Error exception) {
            this.rollbackLoadedLifecycles(exception);
            this.components.clear();
            this.managedTypes.clear();
            throw exception;
        }
    }

    private void rollbackLoadedLifecycles(Throwable loadFailure) {
        for (int index = this.loadedLifecycles.size() - 1; index >= 0; index--) {
            try {
                this.loadedLifecycles.get(index).unload();
            } catch (RuntimeException | Error exception) {
                loadFailure.addSuppressed(exception);
            }
        }
        this.loadedLifecycles.clear();
    }

    private int phaseOrdinal(ManagedLifecycle lifecycle) {
        LoadOrder order = lifecycle.getClass().getAnnotation(LoadOrder.class);
        return order == null ? LoadPhase.SERVICES.ordinal() : order.phase().ordinal();
    }

    private Object tryCreate(Class<?> type, ComponentArguments<?> arguments) {
        try {
            return this.instantiateWithCycleGuard(type, arguments);
        } catch (UnresolvedDependencyException ignored) {
            return null;
        }
    }

    private <T> T instantiateWithCycleGuard(Class<T> type, ComponentArguments<?> arguments) {
        List<Class<?>> resolving = this.resolvingTypes.get();
        if (resolving.contains(type)) {
            List<String> cycle = new ArrayList<>();
            for (Class<?> resolvingType : resolving) cycle.add(resolvingType.getName());
            cycle.add(type.getName());
            throw new IllegalStateException("Circular component dependency: " + String.join(" -> ", cycle));
        }
        resolving.add(type);
        try {
            return this.instantiate(type, arguments);
        } finally {
            resolving.remove(resolving.size() - 1);
            if (resolving.isEmpty()) this.resolvingTypes.remove();
        }
    }

    private <T> T instantiate(Class<T> type, ComponentArguments<?> arguments) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length != 1) {
            throw new IllegalStateException("Component must declare exactly one constructor: " + type.getName());
        }
        Constructor<?> constructor = constructors[0];
        Parameter[] parameters = constructor.getParameters();
        Object[] values = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            values[index] = this.resolveConstructorArgument(type, parameters[index], arguments);
        }
        try {
            constructor.setAccessible(true);
            return type.cast(constructor.newInstance(values));
        } catch (InstantiationException | IllegalAccessException exception) {
            throw new IllegalStateException("Failed to create component: " + type.getName(), exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Component constructor failed: " + type.getName(), exception.getCause());
        }
    }

    private Object resolveConstructorArgument(
            Class<?> owner,
            Parameter parameter,
            ComponentArguments<?> arguments
    ) {
        if (parameter.isAnnotationPresent(RuntimeArgument.class)) {
            if (!arguments.isPresent()) {
                throw new IllegalStateException("Missing runtime argument for component: " + owner.getName());
            }
            Object value = arguments.value();
            if (!wrapPrimitive(parameter.getType()).isInstance(value)) {
                throw new IllegalArgumentException("Runtime argument for " + owner.getName()
                        + " must be " + parameter.getType().getName());
            }
            return value;
        }
        if (parameter.getType() == ComponentSet.class) {
            Class<?> elementType = componentSetElementType(parameter.getParameterizedType(), owner);
            return ComponentSet.of(this.resolveAllImplementations(elementType));
        }
        Object dependency = this.find(parameter.getType());
        if (dependency != null) return dependency;
        if (this.managedTypes.contains(parameter.getType())
                && componentScope(parameter.getType()) == ComponentScope.SINGLETON) {
            throw new UnresolvedDependencyException();
        }
        try {
            return this.resolve(parameter.getType());
        } catch (IllegalStateException exception) {
            if (parameter.getType().isInterface() || Modifier.isAbstract(parameter.getType().getModifiers())) {
                throw new UnresolvedDependencyException();
            }
            throw exception;
        }
    }

    private List<?> resolveAllImplementations(Class<?> contract) {
        List<Object> values = new ArrayList<>();
        for (Class<?> type : ordered(this.managedTypes)) {
            if (!contract.isAssignableFrom(type)) continue;
            values.add(this.resolve(type));
        }
        return List.copyOf(values);
    }

    private <T> Class<? extends T> resolveImplementation(Class<T> requestedType) {
        if (!requestedType.isInterface() && !Modifier.isAbstract(requestedType.getModifiers())) {
            if (this.managedTypes.contains(requestedType)
                    || componentScope(requestedType) == ComponentScope.PROTOTYPE
                    && requestedType.isAnnotationPresent(InjectComponent.class)) {
                return requestedType;
            }
            throw new IllegalStateException("Missing component: " + requestedType.getName());
        }
        List<Class<? extends T>> candidates = new ArrayList<>();
        for (Class<?> type : this.managedTypes) {
            if (requestedType.isAssignableFrom(type)) candidates.add(type.asSubclass(requestedType));
        }
        candidates.sort(Comparator.comparing(Class::getName));
        if (candidates.isEmpty()) throw new IllegalStateException("Missing component: " + requestedType.getName());
        if (candidates.size() == 1) return candidates.getFirst();
        List<Class<? extends T>> defaults = candidates.stream()
                .filter(type -> type.isAnnotationPresent(DefaultImplementation.class))
                .toList();
        if (defaults.size() == 1) return defaults.getFirst();
        throw new IllegalStateException("Multiple components implement " + requestedType.getName() + ": " + candidates);
    }

    private Object find(Class<?> type) {
        Object direct = this.components.get(type);
        if (direct != null) return direct;
        Object match = null;
        for (Object component : this.components.values()) {
            if (!type.isInstance(component)) continue;
            if (match != null && match != component) {
                if (match.getClass().isAnnotationPresent(DefaultImplementation.class)) continue;
                if (component.getClass().isAnnotationPresent(DefaultImplementation.class)) {
                    match = component;
                    continue;
                }
                throw new IllegalStateException("Multiple components implement " + type.getName());
            }
            match = component;
        }
        return match;
    }

    private static Set<Class<? extends Annotation>> componentRoles(Reflections reflections) {
        Set<Class<? extends Annotation>> roles = new LinkedHashSet<>();
        for (Class<?> type : reflections.getTypesAnnotatedWith(ComponentRole.class)) {
            if (!type.isAnnotation()) continue;
            roles.add(type.asSubclass(Annotation.class));
        }
        return roles;
    }

    private static ComponentScope componentScope(Class<?> type) {
        InjectComponent component = type.getAnnotation(InjectComponent.class);
        return component == null ? ComponentScope.SINGLETON : component.scope();
    }

    private static Class<?> componentSetElementType(Type genericType, Class<?> owner) {
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            throw new IllegalStateException("ComponentSet must declare an element type in " + owner.getName());
        }
        Type elementType = parameterizedType.getActualTypeArguments()[0];
        if (!(elementType instanceof Class<?> type)) {
            throw new IllegalStateException("Unsupported ComponentSet element type in " + owner.getName());
        }
        return type;
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static List<Class<?>> ordered(Iterable<Class<?>> types) {
        List<Class<?>> ordered = new ArrayList<>();
        types.forEach(ordered::add);
        ordered.sort(Comparator.comparing(Class::getName));
        return List.copyOf(ordered);
    }

    private static final class UnresolvedDependencyException extends RuntimeException {
    }
}
