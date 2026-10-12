package cn.mythicland.framework.world;

import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.Plugin;

public final class VoidWorlds {
    private VoidWorlds() {}

    public static World create(Plugin plugin, String name) {
        Listener initialization = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onInit(WorldInitEvent event) {
                if (event.getWorld().getName().equals(name)) event.getWorld().setKeepSpawnInMemory(false);
            }
        };
        plugin.getServer().getPluginManager().registerEvents(initialization, plugin);
        try {
            WorldCreator creator = new WorldCreator(name)
                    .generator(new VoidChunkGenerator())
                    .generateStructures(false);
            World world = plugin.getServer().createWorld(creator);
            if (world == null) throw new IllegalStateException("Void world was not created: " + name);
            world.setKeepSpawnInMemory(false);
            return world;
        } finally { HandlerList.unregisterAll(initialization); }
    }
}
