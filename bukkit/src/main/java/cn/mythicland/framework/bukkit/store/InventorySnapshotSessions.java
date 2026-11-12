package cn.mythicland.framework.bukkit.store;

import cn.mythicland.framework.bukkit.item.v1_8_R3.InventoryItemCodec.SnapshotEncoder;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import org.bukkit.inventory.ItemStack;

/**
 * Reuses inventory encodings for live session keys without retaining expired sessions.
 * Use one key per inventory and create, access and close this collection on its owning thread.
 * Keys must have stable equality and must remain strongly held by their owning feature.
 */
public final class InventorySnapshotSessions<K> implements AutoCloseable {
    private final InventoryItemSerializer serializer;
    private final Thread ownerThread = Thread.currentThread();
    private final Map<K, SnapshotEncoder> encoders = new WeakHashMap<>();
    private boolean closed;

    InventorySnapshotSessions(InventoryItemSerializer serializer) {
        this.serializer = serializer;
    }

    public String serialize(K session, ItemStack[] contents) {
        this.requireOwnerThread();
        if (this.closed) throw new IllegalStateException("Inventory snapshot sessions are closed");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(contents, "contents");
        SnapshotEncoder encoder = this.encoders.computeIfAbsent(session, ignored -> new SnapshotEncoder());
        return this.serializer.serializeItems(contents, encoder);
    }

    public void release(K session) {
        this.requireOwnerThread();
        this.encoders.remove(session);
    }

    @Override
    public void close() {
        this.requireOwnerThread();
        this.closed = true;
        this.encoders.clear();
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("Inventory snapshot sessions require their owning thread");
        }
    }
}
