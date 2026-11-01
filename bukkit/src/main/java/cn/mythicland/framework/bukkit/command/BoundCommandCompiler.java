package cn.mythicland.framework.bukkit.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Compiles a command component with a declared root into the common command tree.
 */
final class BoundCommandCompiler {
    private final Function<CommandArgument, SuggestionProvider> suggestions;

    BoundCommandCompiler(Function<CommandArgument, SuggestionProvider> suggestions) {
        this.suggestions = suggestions;
    }

    CommandSpec compile(Object component) {
        CommandBinding binding = component.getClass().getAnnotation(CommandBinding.class);
        if (binding == null) throw new IllegalStateException("Missing @CommandBinding: " + component.getClass());
        String root = binding.value().trim();
        if (root.isBlank()) throw new IllegalStateException("Command root cannot be blank");
        Map<String, String> groups = new LinkedHashMap<>();
        if (binding.treeHelp() && binding.helpGroups().length > 0)
            throw new IllegalStateException("Tree help and grouped help cannot both be enabled");
        for (String spec : binding.helpGroups()) {
            String[] group = spec.split(":", 2);
            if (group.length != 2 || !group[0].matches("[a-z][a-z0-9_-]*") || group[1].isBlank()
                    || groups.putIfAbsent(group[0], group[1].trim()) != null)
                throw new IllegalStateException("Invalid or duplicate help group: " + spec);
        }
        Map<String, Method> completers = new LinkedHashMap<>();
        List<Method> handlers = new ArrayList<>();
        for (Class<?> type = component.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(CommandRoute.class)) handlers.add(method);
                CommandCompleter completer = method.getAnnotation(CommandCompleter.class);
                if (completer != null) {
                    validate(method, true);
                    String path = normalize(completer.value());
                    if (completers.putIfAbsent(path, method) != null)
                        throw new IllegalStateException("Duplicate command completer: " + path);
                }
            }
        }
        handlers.sort(Comparator.comparingInt((Method method) -> method.getAnnotation(CommandRoute.class).order())
                .thenComparing(Method::getName).thenComparing(method -> Arrays.toString(method.getParameterTypes())));
        if (handlers.isEmpty()) throw new IllegalStateException("Command component has no @CommandRoute methods");
        CommandNode tree = null;
        Set<String> names = new HashSet<>();
        int sequence = 0;
        for (Method method : handlers) {
            CommandRoute route = method.getAnnotation(CommandRoute.class);
            validate(method, false);
            if (route.path().length == 0 || !route.path()[0].equalsIgnoreCase(root))
                throw new IllegalStateException("Command path must start with root " + root + ": " + method);
            String path = normalize(String.join(" ", Arrays.copyOfRange(route.path(), 1, route.path().length)));
            if (!names.add(path)) throw new IllegalStateException("Duplicate command route: " + path);
            if (!groups.isEmpty() && !groups.containsKey(route.helpGroup()))
                throw new IllegalStateException("Unknown help group for command: " + path);
            Method completion = completers.remove(path);
            if (route.minArguments() < 0 || route.maxArguments() < route.minArguments())
                throw new IllegalStateException("Invalid command argument range: " + method);
            String usage = String.join("\n", route.usages()).trim();
            if (usage.isBlank()) usage = path.isBlank() ? "/" + root : "/" + root + " " + path;
            Supplier<String> permission = permission(component, binding.permission(), route);
            CommandNode canonical =
                    route(root, path, path, usage, route, component, method, completion, permission, sequence);
            tree = tree == null ? canonical : tree.merge(canonical);
            if (path.isBlank() && route.aliases().length > 0)
                throw new IllegalStateException("The root command cannot declare aliases");
            for (String alias : route.aliases()) {
                String alternate = normalize(alias);
                if (alternate.isBlank()) throw new IllegalArgumentException("Command alias cannot be blank");
                if (!alternate.contains(" ") && path.contains(" "))
                    alternate = path.substring(0, path.lastIndexOf(' ') + 1) + alternate;
                tree = tree.merge(route(root, alternate, path, usage, route, component, method, completion, permission,
                        sequence));
            }
            sequence++;
        }
        if (!completers.isEmpty())
            throw new IllegalStateException("Command completer has no handler: " + completers.keySet());
        return CommandSpec.configured(tree, binding.treeHelp(), groups);
    }

    private CommandNode route(
            String root,
            String lookupPath,
            String canonicalPath,
            String usage,
            CommandRoute route,
            Object component,
            Method handler,
            Method completer,
            Supplier<String> permission,
            int order
    ) {
        String[] parts = lookupPath.isBlank() ? new String[0] : lookupPath.split(" ");
        String name = parts.length == 0 ? root : parts[parts.length - 1];
        CommandNode.Builder leaf = CommandNode.literal(name)
                .usage(usage).arguments(route.minArguments(), route.maxArguments())
                .help(canonicalPath, route.helpGroup(), route.description().trim(), route.showInHelp())
                .order(canonicalPath.isBlank() ? -1 : order)
                .dynamicPermission(permission);
        if (route.executable()) leaf.execute(context -> invoke(component, handler, context));
        Map<Integer, CommandArgument> arguments = new HashMap<>();
        for (CommandArgument argument : route.arguments()) {
            if (argument.index() < 0 || argument.index() >= route.maxArguments()
                    || arguments.putIfAbsent(argument.index(), argument) != null)
                throw new IllegalStateException("Invalid command argument suggestion: " + handler);
        }
        int suggestionCount = arguments.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        for (int index = 0; index < suggestionCount; index++) {
            CommandArgument argument = arguments.get(index);
            leaf.suggest(argument == null ? SuggestionProvider.empty() : suggestions.apply(argument));
        }
        if (completer != null) leaf.complete(context -> {
            Object result = invoke(component, completer, context);
            if (result == null) return List.of();
            @SuppressWarnings("unchecked") List<String> values = (List<String>) result;
            return List.copyOf(values);
        });
        CommandNode current = leaf.build();
        for (int index = parts.length - 2; index >= 0; index--)
            current = CommandNode.literal(parts[index]).child(current).build();
        if (parts.length > 0) current = CommandNode.root(root).child(current).build();
        return current;
    }

    static Supplier<String> permission(Object component, String inherited, CommandRoute route) {
        String value = route.permission().trim();
        if (route.permissionMethod().isBlank()) {
            String resolved = value.isBlank() && route.inheritPermission() ? inherited.trim() : value;
            return () -> resolved;
        }
        if (!value.isBlank()) throw new IllegalStateException("Permission and permissionMethod cannot both be set");
        Method method = null;
        for (Class<?> type = component.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(route.permissionMethod().trim())) {
                    method = candidate;
                    break;
                }
            }
            if (method != null) break;
        }
        if (method == null || Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                || method.getReturnType() != String.class || !method.trySetAccessible())
            throw new IllegalStateException("Invalid permissionMethod: " + route.permissionMethod());
        Method resolved = method;
        return () -> {
            Object result = invoke(component, resolved, null);
            if (!(result instanceof String text))
                throw new IllegalStateException("Dynamic permission must be a string");
            return text.trim();
        };
    }

    private static Object invoke(Object component, Method method, CommandContext context) {
        try {
            return method.getParameterCount() == 0 ? method.invoke(component) : method.invoke(component, context);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Command method failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot invoke command method: " + method, exception);
        }
    }

    private static void validate(Method method, boolean completion) {
        if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != CommandContext.class
                || (completion ? !List.class.isAssignableFrom(method.getReturnType()) :
                method.getReturnType() != void.class)
                || !method.trySetAccessible())
            throw new IllegalStateException("Invalid command method: " + method);
    }

    private static String normalize(String path) {
        String value = path.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "";
        String[] parts = value.split("\\s+");
        for (String part : parts)
            if (part.equals("help")) throw new IllegalArgumentException("The help command is reserved");
        return String.join(" ", parts);
    }
}
