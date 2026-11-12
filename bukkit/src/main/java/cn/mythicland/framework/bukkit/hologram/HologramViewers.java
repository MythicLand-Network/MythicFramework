package cn.mythicland.framework.bukkit.hologram;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.entity.Player;

/**
 * Owns viewer state for a shared display or one personalized display per player.
 * Call on the Bukkit primary thread. Refresh at the owning feature's visibility cadence, forget
 * departing players and close the group when its location or feature is removed.
 */
public final class HologramViewers<D extends HologramDisplay> implements AutoCloseable {
    private final D sharedDisplay;
    private final Function<Player, List<String>> text;
    private final BiFunction<Player, List<String>, D> factory;
    private final Map<UUID, Viewer<D>> viewers = new HashMap<>();
    private boolean closed;

    private HologramViewers(
            D sharedDisplay,
            Function<Player, List<String>> text,
            BiFunction<Player, List<String>, D> factory
    ) {
        this.sharedDisplay = sharedDisplay;
        this.text = text;
        this.factory = factory;
    }

    public static <D extends HologramDisplay> HologramViewers<D> shared(D display) {
        Objects.requireNonNull(display, "display");
        return new HologramViewers<>(display, player -> List.of(), (player, text) -> display);
    }

    public static <D extends HologramDisplay> HologramViewers<D> personalized(
            Function<Player, List<String>> text,
            BiFunction<Player, List<String>, D> factory
    ) {
        return new HologramViewers<>(
                null, Objects.requireNonNull(text, "text"), Objects.requireNonNull(factory, "factory")
        );
    }

    /**
     * Reconciles visibility and returns false when creation must continue on the next tick.
     * Hidden personalized displays are retained until forget/close and do not consume creation budget.
     */
    public boolean refresh(Collection<? extends Player> players, Predicate<Player> visible, int maxCreations) {
        this.requireOpen();
        if (maxCreations <= 0) throw new IllegalArgumentException("maxCreations must be positive");
        int creations = 0;
        boolean complete = true;
        for (Player player : players) {
            UUID id = player.getUniqueId();
            Viewer<D> viewer = this.viewers.get(id);
            if (viewer != null && viewer.player != player) {
                this.viewers.remove(id);
                viewer = null;
            }
            if (!visible.test(player)) {
                this.hide(player);
                continue;
            }
            if (viewer != null && viewer.visible) continue;
            if (viewer == null) {
                if (this.sharedDisplay == null && creations >= maxCreations) {
                    complete = false;
                    continue;
                }
                D display = this.factory.apply(player, this.text.apply(player));
                viewer = new Viewer<>(player, display);
                this.viewers.put(id, viewer);
                creations++;
            } else if (this.sharedDisplay == null) {
                viewer.display.update(this.text.apply(player), List.of());
            }
            viewer.display.show(player);
            viewer.visible = true;
        }
        return complete;
    }

    /**
     * Returns this exact player's visible display, or null when it is hidden or absent.
     */
    public D visibleDisplay(Player player) {
        Viewer<D> viewer = this.viewers.get(player.getUniqueId());
        if (viewer == null || viewer.player != player || !viewer.visible) return null;
        return viewer.display;
    }

    public void hide(Player player) {
        Viewer<D> viewer = this.viewers.get(player.getUniqueId());
        if (viewer == null || viewer.player != player || !viewer.visible) return;
        viewer.display.hide(player);
        viewer.visible = false;
        if (this.sharedDisplay != null) this.viewers.remove(player.getUniqueId());
    }

    /**
     * Removes state without sending packets when the client has already disconnected.
     */
    public void forget(Player player, boolean hide) {
        Viewer<D> viewer = this.viewers.get(player.getUniqueId());
        if (viewer == null || viewer.player != player) return;
        if (hide) this.hide(player);
        this.viewers.remove(player.getUniqueId());
    }

    public void updateShared(List<String> text) {
        this.requireOpen();
        if (this.sharedDisplay == null) throw new IllegalStateException("Hologram displays are personalized");
        List<Player> visible = this.viewers.values().stream()
                .filter(viewer -> viewer.visible)
                .map(viewer -> viewer.player)
                .toList();
        this.sharedDisplay.update(text, visible);
    }

    public void update(Player player, List<String> text) {
        this.requireOpen();
        D display = this.visibleDisplay(player);
        if (display != null) display.update(text, List.of(player));
    }

    public void refreshText() {
        this.requireOpen();
        if (this.sharedDisplay != null) throw new IllegalStateException("Hologram display is shared");
        for (Viewer<D> viewer : this.viewers.values()) {
            if (viewer.visible) viewer.display.update(this.text.apply(viewer.player), List.of(viewer.player));
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        for (Viewer<D> viewer : this.viewers.values()) {
            if (viewer.visible && viewer.player.isOnline()) viewer.display.hide(viewer.player);
        }
        this.viewers.clear();
        this.closed = true;
    }

    private void requireOpen() {
        if (this.closed) throw new IllegalStateException("Hologram viewers are closed");
    }

    private static final class Viewer<D extends HologramDisplay> {
        private final Player player;
        private final D display;
        private boolean visible;

        private Viewer(Player player, D display) {
            this.player = player;
            this.display = Objects.requireNonNull(display, "display");
        }
    }
}
