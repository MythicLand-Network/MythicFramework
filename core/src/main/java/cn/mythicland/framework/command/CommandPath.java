package cn.mythicland.framework.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Traverses literal command paths while leaving execution and presentation to the caller. */
public final class CommandPath {
    private CommandPath() {
    }

    public static <N> Match<N> walk(
            N root,
            String[] arguments,
            int limit,
            BiFunction<N, String, N> childLookup,
            Predicate<N> allowed
    ) {
        N node = root;
        List<N> visited = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            N child = childLookup.apply(node, arguments[index]);
            if (child == null) break;
            if (!allowed.test(child)) return new Match<>(node, List.copyOf(visited), true);
            node = child;
            visited.add(node);
        }
        return new Match<>(node, List.copyOf(visited), false);
    }

    public record Match<N>(N node, List<N> visited, boolean denied) {
        public int consumed() {
            return this.visited.size();
        }
    }
}
