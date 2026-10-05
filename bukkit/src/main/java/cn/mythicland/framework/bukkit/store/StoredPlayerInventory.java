package cn.mythicland.framework.bukkit.store;

import org.bukkit.entity.Player;

public interface StoredPlayerInventory<I> {
    I capture(Player player);

    void apply(Player player, I inventory);

    default I mergeCaptured(I captured, I existing) {
        return captured;
    }
}
