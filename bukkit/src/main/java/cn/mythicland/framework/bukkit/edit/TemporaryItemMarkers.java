package cn.mythicland.framework.bukkit.edit;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TemporaryItemMarkers {
    private final Map<Item, Location> positions = new IdentityHashMap<>();

    public Item add(Location position, Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setLore(List.of(UUID.randomUUID().toString()));
        stack.setItemMeta(meta);
        Item item = position.getWorld().dropItem(position, stack);
        item.setPickupDelay(Integer.MAX_VALUE);
        item.setVelocity(new Vector());
        this.positions.put(item, position.clone());
        return item;
    }

    public boolean contains(Item item) {
        return this.positions.containsKey(item);
    }

    public void remove(Item item) {
        this.positions.remove(item);
        item.remove();
    }

    public void keepAlive() {
        for (Map.Entry<Item, Location> entry : this.positions.entrySet()) {
            Item item = entry.getKey();
            if (!item.isValid()) continue;
            Location position = entry.getValue();
            Location current = item.getLocation();
            if (!current.getWorld().equals(position.getWorld()) || current.distanceSquared(position) > 0)
                item.teleport(position);
            if (item.getVelocity().lengthSquared() > 0) item.setVelocity(new Vector());
            if (item.getTicksLived() > 20) item.setTicksLived(1);
        }
    }

}
