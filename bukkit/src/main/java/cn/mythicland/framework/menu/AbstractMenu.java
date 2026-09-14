package cn.mythicland.framework.menu;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public abstract class AbstractMenu {
    public abstract String buildTitle(Player player);

    protected abstract int resolveRowCount(Player player);

    protected void populateMenu(Player player, Menu menu) {
    }

    public final String resolveTitle(Player player) {
        String title = ChatColor.translateAlternateColorCodes('&', this.buildTitle(player));
        return title.length() <= 32 ? title : title.substring(0, 32);
    }

    public final Menu buildMenu(Player player) {
        int rows = Math.clamp(this.resolveRowCount(player), 1, 6);
        Menu menu = new Menu(rows * 9);
        MenuSlotCompiler.populate(this, player, menu);
        this.populateMenu(player, menu);
        return menu;
    }
}
