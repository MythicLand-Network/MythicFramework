package cn.mythicland.framework.bukkit.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Method;

public final class PlayerInventoryStorage {
    private static final ClassValue<Method> STORAGE_CONTENTS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> inventoryType) {
            try {
                return inventoryType.getMethod("getStorageContents");
            } catch (NoSuchMethodException | LinkageError ignored) {
                // Legacy inventories expose the storage slots through getContents().
                return null;
            }
        }
    };

    private PlayerInventoryStorage() {
    }

    public static ItemStack[] contents(PlayerInventory inventory) {
        Method accessor = STORAGE_CONTENTS.get(inventory.getClass());
        if (accessor == null) return inventory.getContents();
        try {
            Object contents = accessor.invoke(inventory);
            if (contents instanceof ItemStack[] items) return items;
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return inventory.getContents();
    }
}
