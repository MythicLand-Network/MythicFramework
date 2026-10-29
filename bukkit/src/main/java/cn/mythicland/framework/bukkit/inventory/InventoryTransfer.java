package cn.mythicland.framework.bukkit.inventory;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class InventoryTransfer {
    private InventoryTransfer() {
    }

    public static ItemStack transferIntoUnlockedSlots(ItemStack[] target, ItemStack source, int unlockedSlots) {
        if (target == null || isEmpty(source)) return null;
        ItemStack remaining = source.clone();
        int limit = Math.clamp(unlockedSlots, 0, target.length);
        for (int slot = 0; slot < limit && !isEmpty(remaining); slot++) {
            ItemStack existing = target[slot];
            if (isEmpty(existing) || !canStackTogether(existing, remaining)) continue;
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) continue;
            int moved = Math.min(space, remaining.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            remaining.setAmount(remaining.getAmount() - moved);
        }
        for (int slot = 0; slot < limit && !isEmpty(remaining); slot++) {
            ItemStack existing = target[slot];
            if (!isEmpty(existing)) continue;
            int moved = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
            ItemStack placed = remaining.clone();
            placed.setAmount(moved);
            target[slot] = placed;
            remaining.setAmount(remaining.getAmount() - moved);
        }
        return isEmpty(remaining) ? null : remaining;
    }

    public static int insert(ItemStack[] target, ItemStack source, int amount) {
        if (amount <= 0 || isEmpty(source)) return amount;
        int remaining = amount;
        for (int slot = 0; slot < target.length && remaining > 0; slot++) {
            ItemStack existing = target[slot];
            if (isEmpty(existing)) continue;
            int capacity = Math.min(existing.getMaxStackSize(), source.getMaxStackSize()) - existing.getAmount();
            if (capacity <= 0 || !existing.isSimilar(source)) continue;
            int moved = Math.min(capacity, remaining);
            ItemStack merged = existing.clone();
            merged.setAmount(existing.getAmount() + moved);
            target[slot] = merged;
            remaining -= moved;
        }
        for (int slot = 0; slot < target.length && remaining > 0; slot++) {
            if (!isEmpty(target[slot])) continue;
            int moved = Math.min(source.getMaxStackSize(), remaining);
            ItemStack placed = source.clone();
            placed.setAmount(moved);
            target[slot] = placed;
            remaining -= moved;
        }
        return remaining;
    }

    private static boolean isEmpty(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0;
    }

    private static boolean canStackTogether(ItemStack first, ItemStack second) {
        if (first == null || second == null) return false;
        try {
            return first.isSimilar(second);
        } catch (NullPointerException ignored) {
            return first.getType() == second.getType();
        }
    }
}
