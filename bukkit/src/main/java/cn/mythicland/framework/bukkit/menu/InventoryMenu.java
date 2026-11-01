package cn.mythicland.framework.bukkit.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * A menu that renders and handles the Bukkit inventory directly.
 */
public abstract class InventoryMenu extends AbstractMenu {
    public abstract int size(Player player);

    public abstract void render(Player player, Inventory inventory);

    public void handleClick(Player player, InventoryClickEvent event) {
    }

    public void handleClick(Player player, InventoryClickEvent event, MenuManager menus) {
        handleClick(player, event);
    }

    public void handleDrag(Player player, InventoryDragEvent event) {
    }

    public void handleDrag(Player player, InventoryDragEvent event, MenuManager menus) {
        handleDrag(player, event);
    }

    public void onQuit(Player player, Inventory inventory) {
    }

    @Override
    protected final int resolveRowCount(Player player) {
        int size = size(player);
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("Menu size must be a multiple of nine between 9 and 54");
        }
        return size / 9;
    }

    @Override
    public final String resolveTitle(Player player) {
        return buildTitle(player);
    }
}
