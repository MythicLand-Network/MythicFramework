package cn.mythicland.framework.bukkit.store;

/** The same idempotent transaction must be retried before its resources can be released. */
public final class StoredPlayerCommitUncertainException extends RuntimeException {
    public StoredPlayerCommitUncertainException(Throwable cause) {
        super("Player transaction commit outcome is unknown", cause);
    }
}
