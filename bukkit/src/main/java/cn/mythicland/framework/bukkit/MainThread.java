package cn.mythicland.framework.bukkit;

import org.bukkit.Bukkit;

/** Validates operations that must run on Bukkit's primary thread. */
public final class MainThread {
    private MainThread() {
    }

    /**
     * Requires the Bukkit primary thread.
     *
     * @param message failure message identifying the operation
     * @throws IllegalStateException when called from another thread
     */
    public static void require(String message) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException(message);
    }
}
