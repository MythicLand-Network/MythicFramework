package cn.mythicland.framework.bukkit.content;

import java.util.Objects;

/**
 * A prepared in-memory update and its inverse. Both actions run on the owning server thread;
 * preparation must finish before any update is applied. Actions must not persist runtime data.
 *
 * @param apply publishes the prepared snapshot
 * @param rollback restores the previously published snapshot
 */
public record ContentUpdate(Runnable apply, Runnable rollback) {
    public ContentUpdate {
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(rollback, "rollback");
    }
}
