package cn.mythicland.framework.menu;

import cn.mythicland.framework.bukkit.task.TaskScheduler;
import cn.mythicland.framework.component.ComponentResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class MenuManagerTest {
    @Test
    void refreshOnlyWritesChangedSlotsAndKeepsCurrentButtons() throws ReflectiveOperationException {
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTitle()).thenReturn("test");
        when(inventory.getSize()).thenReturn(9);
        ItemStack unchanged = mock(ItemStack.class);
        ItemStack removed = mock(ItemStack.class);
        ItemStack added = mock(ItemStack.class);
        ItemStack[] slots = new ItemStack[9];
        slots[0] = unchanged;
        slots[1] = removed;
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots[call.getArgument(0)]);
        doAnswer(call -> {
            slots[call.getArgument(0)] = call.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        AbstractButton currentButton = mock(AbstractButton.class);
        when(currentButton.buildDisplayItem(player)).thenReturn(unchanged);
        AbstractButton addedButton = mock(AbstractButton.class);
        when(addedButton.buildDisplayItem(player)).thenReturn(added);
        AbstractMenu menu = new AbstractMenu() {
            @Override
            public String buildTitle(Player viewer) {
                return "test";
            }

            @Override
            protected int resolveRowCount(Player viewer) {
                return 1;
            }

            @Override
            protected void populateMenu(Player viewer, Menu contents) {
                contents.setButton(0, currentButton);
                contents.setButton(2, addedButton);
            }
        };
        Object holder = newHolder(UUID.randomUUID(), menu, new Menu(9));
        invoke(holder, "bind", inventory);
        Class<?> sessionType = Class.forName(MenuManager.class.getName() + "$OpenMenuSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(AbstractMenu.class, holder.getClass(), long.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(menu, holder, 0L);
        MenuManager manager = newManager();

        invoke(manager, "refreshInPlace", player, session, false);

        verify(inventory, never()).setItem(0, unchanged);
        verify(inventory).setItem(1, null);
        verify(inventory).setItem(2, added);
        assertSame(currentButton, ((Menu) invoke(holder, "contents")).getButton(0));
        clearInvocations(inventory);
        invoke(manager, "refreshInPlace", player, session, false);
        verify(inventory, never()).setItem(anyInt(), any());
        verify(player, never()).updateInventory();
    }

    @Test
    void cancelsDragThatOnlyTargetsPlayerInventory() {
        UUID playerUniqueId = UUID.randomUUID();
        AbstractMenu menu = menu();
        Menu contents = new Menu(9);
        Object holder = newHolder(playerUniqueId, menu, contents);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn((InventoryHolder) holder);
        when(inventory.getSize()).thenReturn(9);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUniqueId);
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getRawSlots()).thenReturn(Set.of(9));

        newManager().handleDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    void interactionPolicyBlocksButtonUntilPlayerIsReady() {
        UUID playerUniqueId = UUID.randomUUID();
        AbstractButton button = mock(AbstractButton.class);
        Menu contents = new Menu(9);
        contents.setButton(0, button);
        Object holder = newHolder(playerUniqueId, menu(), contents);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn((InventoryHolder) holder);
        when(inventory.getSize()).thenReturn(9);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUniqueId);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.LEFT);
        when(event.getRawSlot()).thenReturn(0);
        MenuManager manager = newManager();
        manager.interactionAllowed(viewer -> false);

        manager.handleClick(event);

        verify(button, never()).onClick(any(), any(), any());
        verify(event).setCancelled(true);
        manager.interactionAllowed(viewer -> true);
        manager.handleClick(event);
        verify(button).onClick(player, ClickType.LEFT, manager);
    }

    @Test
    void holderReleasesInventoryEvenWhenCloseCallbackFails() {
        IllegalStateException failure = new IllegalStateException("close failed");
        AbstractMenu menu = new AbstractMenu() {
            @Override
            public String buildTitle(Player player) {
                return "test";
            }

            @Override
            protected int resolveRowCount(Player player) {
                return 1;
            }

            @Override
            public void onClose(Player player, Inventory inventory) {
                throw failure;
            }
        };
        Menu contents = new Menu(9);
        Object holder = newHolder(UUID.randomUUID(), menu, contents);
        Inventory inventory = mock(Inventory.class);
        assertDoesNotThrow(() -> invoke(holder, "bind", inventory));

        InvocationTargetException exception = assertThrows(
                InvocationTargetException.class,
                () -> invoke(holder, "close", mock(Player.class))
        );

        assertSame(failure, exception.getCause());
        assertNull(((InventoryHolder) holder).getInventory());
    }

    @Test
    void holderClearsContentsWhenInventoryWasNeverBound() throws ReflectiveOperationException {
        Menu contents = new Menu(9);
        contents.setButton(0, mock(AbstractButton.class));
        Object holder = newHolder(UUID.randomUUID(), menu(), contents);

        invoke(holder, "close", mock(Player.class));

        assertNull(((InventoryHolder) holder).getInventory());
        assertTrue(contents.getButtons().isEmpty());
    }

    @Test
    void buildFailureDoesNotCreateMenuSession() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(scheduler).executeSyncIfNeeded(any(Runnable.class));
        MenuManager manager = new MenuManager(mock(JavaPlugin.class), mock(ComponentResolver.class), scheduler);
        Player player = mock(Player.class);
        UUID playerUniqueId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUniqueId);
        AbstractMenu failingMenu = new AbstractMenu() {
            @Override
            public String buildTitle(Player player) {
                return "test";
            }

            @Override
            protected int resolveRowCount(Player player) {
                return 1;
            }

            @Override
            protected void populateMenu(Player player, Menu menu) {
                throw new IllegalStateException("build failed");
            }
        };

        assertThrows(IllegalStateException.class, () -> manager.open(player, failingMenu));
        assertFalse(manager.hasOpenMenu(playerUniqueId));
    }

    private static MenuManager newManager() {
        return new MenuManager(mock(JavaPlugin.class), mock(ComponentResolver.class), mock(TaskScheduler.class));
    }

    private static AbstractMenu menu() {
        return new AbstractMenu() {
            @Override
            public String buildTitle(Player player) {
                return "test";
            }

            @Override
            protected int resolveRowCount(Player player) {
                return 1;
            }
        };
    }

    private static Object newHolder(UUID playerUniqueId, AbstractMenu menu, Menu contents) {
        try {
            Class<?> holderType = Class.forName(MenuManager.class.getName() + "$MenuHolder");
            Constructor<?> constructor = holderType.getDeclaredConstructor(UUID.class, AbstractMenu.class, Menu.class);
            constructor.setAccessible(true);
            return constructor.newInstance(playerUniqueId, menu, contents);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to create menu holder fixture", exception);
        }
    }

    private static Object invoke(Object target, String methodName, Object... arguments)
            throws ReflectiveOperationException {
        Method method = null;
        for (Method candidate : target.getClass().getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) {
                method = candidate;
                break;
            }
        }
        if (method == null) throw new NoSuchMethodException(methodName);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
}
