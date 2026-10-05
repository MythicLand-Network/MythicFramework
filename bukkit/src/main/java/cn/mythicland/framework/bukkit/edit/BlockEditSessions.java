package cn.mythicland.framework.bukkit.edit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BlockEditSessions {
    private final Map<UUID, Object> owners = new HashMap<>();

    public boolean claim(UUID player, Object owner) {
        return this.owners.putIfAbsent(player, owner) == null;
    }

    public void release(UUID player, Object owner) {
        this.owners.remove(player, owner);
    }
}
