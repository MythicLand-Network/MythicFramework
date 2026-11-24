package cn.mythicland.framework.bukkit.inventory;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
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
            target[slot] = copyWithAmount(remaining, moved);
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
            target[slot] = copyWithAmount(existing, existing.getAmount() + moved);
            remaining -= moved;
        }
        for (int slot = 0; slot < target.length && remaining > 0; slot++) {
            if (!isEmpty(target[slot])) continue;
            int moved = Math.min(source.getMaxStackSize(), remaining);
            target[slot] = copyWithAmount(source, moved);
            remaining -= moved;
        }
        return remaining;
    }

    /** Inserts into an inclusive slot range, merging existing stacks before filling empty slots. */
    public static int insert(
            Inventory target,
            ItemStack source,
            int amount,
            int firstSlot,
            int lastSlot
    ) {
        if (amount <= 0 || isEmpty(source)) return amount;
        int remaining = amount;
        for (int slot = firstSlot; slot <= lastSlot && remaining > 0; slot++) {
            ItemStack existing = target.getItem(slot);
            if (isEmpty(existing)) continue;
            int capacity = Math.min(existing.getMaxStackSize(), source.getMaxStackSize()) - existing.getAmount();
            if (capacity <= 0 || !existing.isSimilar(source)) continue;
            int moved = Math.min(capacity, remaining);
            target.setItem(slot, copyWithAmount(existing, existing.getAmount() + moved));
            remaining -= moved;
        }
        for (int slot = firstSlot; slot <= lastSlot && remaining > 0; slot++) {
            if (!isEmpty(target.getItem(slot))) continue;
            int moved = Math.min(source.getMaxStackSize(), remaining);
            target.setItem(slot, copyWithAmount(source, moved));
            remaining -= moved;
        }
        return remaining;
    }

    private static ItemStack copyWithAmount(ItemStack item, int amount) {
        ItemStack copy = item.clone();
        copy.setAmount(amount);
        return copy;
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
