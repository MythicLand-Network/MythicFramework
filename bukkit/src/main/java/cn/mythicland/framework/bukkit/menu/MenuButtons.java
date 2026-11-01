package cn.mythicland.framework.bukkit.menu;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Small buttons for previews and plain left/right selection controls.
 */
public final class MenuButtons {
    private MenuButtons() {
    }

    @FunctionalInterface
    public interface Action {
        /**
         * Returns whether the action succeeded and should receive selection feedback.
         */
        boolean execute(Player player, ClickType click, MenuManager menus);
    }

    public static AbstractButton display(ItemStack icon) {
        return new AbstractDisplayButton() {
            @Override
            public ItemStack buildDisplayItem(Player player) {
                return icon;
            }
        };
    }

    /**
     * Handles plain left/right clicks without sound or automatic refresh.
     */
    public static AbstractButton action(ItemStack icon, Action action) {
        return button(icon, action, false);
    }

    /**
     * Plays the shared click sound after a successful plain left/right action.
     */
    public static AbstractButton selection(ItemStack icon, Action action) {
        return button(icon, action, true);
    }

    private static AbstractButton button(ItemStack icon, Action action, boolean feedback) {
        Objects.requireNonNull(action, "action");
        return new AbstractButton() {
            @Override
            public ItemStack buildDisplayItem(Player player) {
                return icon;
            }

            @Override
            public void onClick(Player player, ClickType click, MenuManager menus) {
                if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
                if (action.execute(player, click, menus) && feedback) MenuSelection.playClickSound(player);
            }
        };
    }
}
