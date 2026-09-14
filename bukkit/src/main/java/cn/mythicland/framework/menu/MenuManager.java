package cn.mythicland.framework.menu;

import cn.mythicland.framework.bukkit.ListenerComponent;
import cn.mythicland.framework.component.InjectComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

@InjectComponent
@ListenerComponent
public final class MenuManager implements Listener {
    public void open(Player player, AbstractMenu menu) {
        Menu contents = menu.buildMenu(player);
        Holder holder = new Holder(menu, contents);
        Inventory inventory = Bukkit.createInventory(holder, contents.getSize(), menu.resolveTitle(player));
        holder.inventory = inventory;
        contents.getButtons().forEach((slot, button) -> inventory.setItem(slot, button.buildDisplayItem(player)));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= holder.contents.getSize()) return;
        AbstractButton button = holder.contents.getButton(event.getRawSlot());
        if (button == null) return;
        ClickType clickType = ClickType.fromClickType(event.getClick());
        if (clickType == null) return;
        button.onClick(player, clickType, this);
        if (button.shouldClose(player, clickType)) {
            player.closeInventory();
        } else if (button.shouldRefresh(player, clickType)) {
            this.open(player, holder.menu);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    private static final class Holder implements InventoryHolder {
        private final AbstractMenu menu;
        private final Menu contents;
        private Inventory inventory;

        private Holder(AbstractMenu menu, Menu contents) {
            this.menu = menu;
            this.contents = contents;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
    }
}
