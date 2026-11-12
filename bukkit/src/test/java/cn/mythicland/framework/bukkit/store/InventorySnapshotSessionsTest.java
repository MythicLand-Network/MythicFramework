package cn.mythicland.framework.bukkit.store;

import java.util.concurrent.CompletableFuture;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventorySnapshotSessionsTest {
    @Test
    void reusesUnchangedEncodingsAndSeparatesInventoryOwners() {
        InventoryItemSerializer serializer = new InventoryItemSerializer();
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        try (InventorySnapshotSessions<Object> sessions = serializer.snapshotSessions()) {
            String first = sessions.serialize(firstOwner, new ItemStack[9]);
            String second = sessions.serialize(secondOwner, new ItemStack[27]);
            assertSame(first, sessions.serialize(firstOwner, new ItemStack[9]));
            assertEquals(9, serializer.deserializeItems(first).length);
            assertEquals(27, serializer.deserializeItems(second).length);
            sessions.release(firstOwner);
            String recaptured = sessions.serialize(firstOwner, new ItemStack[9]);
            assertEquals(first, recaptured);
            assertNotSame(first, recaptured);
        }
    }

    @Test
    void rejectsForeignThreadsAndUseAfterClose() {
        InventorySnapshotSessions<Object> sessions = new InventoryItemSerializer().snapshotSessions();
        Throwable failure = CompletableFuture.supplyAsync(() -> {
            try {
                sessions.serialize(new Object(), new ItemStack[0]);
                return null;
            } catch (IllegalStateException exception) {
                return exception;
            }
        }).join();
        assertInstanceOf(IllegalStateException.class, failure);
        sessions.close();
        sessions.close();
        assertThrows(IllegalStateException.class, () -> sessions.serialize(new Object(), new ItemStack[0]));
    }
}
