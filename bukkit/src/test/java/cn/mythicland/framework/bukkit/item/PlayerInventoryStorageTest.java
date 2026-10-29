package cn.mythicland.framework.bukkit.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class PlayerInventoryStorageTest {
    public interface StorageAccessor {
        ItemStack[] getStorageContents();
    }

    @Test
    void usesStorageSlotsWhenAvailable() {
        PlayerInventory inventory = mock(PlayerInventory.class, withSettings().extraInterfaces(StorageAccessor.class));
        ItemStack[] storage = new ItemStack[36];
        when(((StorageAccessor) inventory).getStorageContents()).thenReturn(storage);
        assertSame(storage, PlayerInventoryStorage.contents(inventory));
        verify(inventory, never()).getContents();
    }

    @Test
    void fallsBackToLegacyContents() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack[] contents = new ItemStack[36];
        when(inventory.getContents()).thenReturn(contents);
        assertSame(contents, PlayerInventoryStorage.contents(inventory));
    }
}
