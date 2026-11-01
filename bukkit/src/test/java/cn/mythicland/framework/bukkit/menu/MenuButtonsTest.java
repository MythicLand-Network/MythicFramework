package cn.mythicland.framework.bukkit.menu;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MenuButtonsTest {
    @Test
    void selectionIgnoresShiftAndSoundsOnlyAfterSuccessfulActions() {
        Player player = mock(Player.class);
        AtomicInteger actions = new AtomicInteger();
        AtomicBoolean successful = new AtomicBoolean();
        AbstractButton button = MenuButtons.selection(mock(ItemStack.class), (viewer, click, menus) -> {
            actions.incrementAndGet();
            return successful.get();
        });
        try (MockedStatic<MenuSelection> feedback = mockStatic(MenuSelection.class)) {
            button.onClick(player, ClickType.SHIFT_LEFT, null);
            assertEquals(0, actions.get());
            button.onClick(player, ClickType.LEFT, null);
            feedback.verifyNoInteractions();
            successful.set(true);
            button.onClick(player, ClickType.RIGHT, null);
            feedback.verify(() -> MenuSelection.playClickSound(player));
            assertEquals(2, actions.get());
        }
    }

    @Test
    void plainActionsReceiveTheOwningManagerWithoutSelectionSound() {
        Player player = mock(Player.class);
        MenuManager menus = mock(MenuManager.class);
        AbstractButton button = MenuButtons.action(mock(ItemStack.class), (viewer, click, owner) -> {
            owner.refresh(viewer);
            return true;
        });
        try (MockedStatic<MenuSelection> feedback = mockStatic(MenuSelection.class)) {
            button.onClick(player, ClickType.LEFT, menus);
            verify(menus).refresh(player);
            feedback.verifyNoInteractions();
        }
    }
}
