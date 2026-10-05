package cn.mythicland.framework.bukkit.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemEditorsTest {
    @Test
    void composedEditors_shouldApplyInOrderAndPreserveColorTranslation() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(item.getItemMeta()).thenReturn(meta);
        ItemEditor editor = ItemEditors.type(Material.DIAMOND_SWORD)
                .andThen(ItemEditors.amount(2))
                .andThen(ItemEditors.name("&aSword"))
                .andThen(ItemEditors.lore("&7Description"));

        editor.apply(item);

        var order = inOrder(item, meta);
        order.verify(item).setType(Material.DIAMOND_SWORD);
        order.verify(item).setAmount(2);
        order.verify(meta).setDisplayName("§aSword");
        order.verify(item).setItemMeta(meta);
        order.verify(meta).setLore(List.of("§7Description"));
        order.verify(item).setItemMeta(meta);
    }
}
