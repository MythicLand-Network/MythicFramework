package cn.mythicland.framework.bukkit.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PlayerInventoryStorage {
    private PlayerInventoryStorage() {
    }

    public static ItemStack[] contents(PlayerInventory inventory) {
        try {
            Object contents = inventory.getClass().getMethod("getStorageContents").invoke(inventory);
            if (contents instanceof ItemStack[] items) return items;
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return inventory.getContents();
    }
}
