package cn.mythicland.framework.bukkit.hologram;

import java.util.Collection;
import java.util.List;

import org.bukkit.entity.Player;

/**
 * Packet display operations. All calls require the Bukkit primary thread.
 */
public interface HologramDisplay {
    void show(Player player);

    void hide(Player player);

    void update(List<String> text, Collection<Player> viewers);

    default boolean isClickTarget(int entityId) {
        return false;
    }

    default boolean acceptsAttack(Player player, int entityId) {
        return false;
    }

    default boolean acceptsDistantAttack(Player player) {
        return false;
    }
}
