package cn.mythicland.framework.bukkit.item;

import cn.mythicland.framework.menu.MenuManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerItemHoldingsTest {
    private Player player;
    private PlayerInventory inventory;
    private Inventory top;
    private MenuManager menus;
    private PlayerItemHoldings holdings;

    @BeforeEach
    void setUp() {
        this.player = mock(Player.class);
        this.inventory = mock(PlayerInventory.class);
        this.top = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(this.player.getInventory()).thenReturn(this.inventory);
        when(this.player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(this.top);
        when(this.inventory.getSize()).thenReturn(40);
        when(this.top.getSize()).thenReturn(9);
        this.menus = mock(MenuManager.class);
        this.holdings = new PlayerItemHoldings(this.menus);
    }

    @Test
    void count_shouldIncludeEquipmentCursorAndContainerButExcludeMenuAndDuplicateInventory() {
        when(this.inventory.getItem(39)).thenReturn(new ItemStack(Material.SLIME_BALL));
        when(this.player.getItemOnCursor()).thenReturn(new ItemStack(Material.SLIME_BALL, 2));
        when(this.top.getItem(0)).thenReturn(new ItemStack(Material.SLIME_BALL));

        assertEquals(4, this.holdings.count(this.player, item -> item.getType() == Material.SLIME_BALL));
        when(this.menus.isManagedInventory(this.top)).thenReturn(true);
        assertEquals(3, this.holdings.count(this.player, item -> item.getType() == Material.SLIME_BALL));
        when(this.player.getOpenInventory().getTopInventory()).thenReturn(this.inventory);
        assertEquals(3, this.holdings.count(this.player, item -> item.getType() == Material.SLIME_BALL));
    }

    @Test
    void updateFirst_shouldWriteCursorAndContainerChangesWithoutModifyingMenuIcons() {
        ItemStack pickaxe = new ItemStack(Material.IRON_PICKAXE);
        when(this.player.getItemOnCursor()).thenReturn(pickaxe);

        assertTrue(this.holdings.updateFirst(
                this.player, item -> item.getType() == Material.IRON_PICKAXE,
                item -> item.setDurability((short) 1)));

        ArgumentCaptor<ItemStack> updated = ArgumentCaptor.forClass(ItemStack.class);
        verify(this.player).setItemOnCursor(updated.capture());
        assertEquals(1, updated.getValue().getDurability());
        assertEquals(0, pickaxe.getDurability());
        when(this.player.getItemOnCursor()).thenReturn(null);
        when(this.top.getItem(0)).thenReturn(pickaxe);

        assertTrue(this.holdings.updateFirst(
                this.player, item -> item.getType() == Material.IRON_PICKAXE,
                item -> item.setDurability((short) 2)));

        verify(this.top).setItem(eq(0), updated.capture());
        assertEquals(2, updated.getValue().getDurability());
        when(this.menus.isManagedInventory(this.top)).thenReturn(true);
        assertFalse(this.holdings.updateFirst(
                this.player, item -> item.getType() == Material.IRON_PICKAXE,
                item -> fail("Menu icon must not be updated")));
    }
}
