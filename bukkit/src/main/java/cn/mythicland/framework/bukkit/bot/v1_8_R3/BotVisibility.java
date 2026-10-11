package cn.mythicland.framework.bukkit.bot.v1_8_R3;

import cn.mythicland.framework.task.StaggeredDeadline;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.ArmorStand;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class BotVisibility {
    private static final int PROFILE_REMOVE_DELAY_TICKS = 2;
    private final Map<UUID, BotVisibilitySettings> configurations = new HashMap<>();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<UUID, Activity> activity = new HashMap<>();
    private final Map<UUID, EntityArmorStand> nameplates = new HashMap<>();
    private final Set<UUID> online = new HashSet<>();
    private long currentTick;

    protected final boolean hasNameplate(UUID bot) {
        return nameplates.containsKey(bot);
    }

    public void configure(UUID bot, BotVisibilitySettings config) {
        BotVisibilitySettings previous = configurations.put(bot, Objects.requireNonNull(config, "config"));
        if (config.equals(previous)) return;
        for (Viewer viewer : viewers.values()) {
            Display display = viewer.entities.get(bot);
            if (display != null) display.checkedAt = Long.MIN_VALUE / 2;
        }
    }

    public void activity(Collection<? extends Player> players, long tick) {
        currentTick = tick;
        online.clear();
        for (Player player : players) {
            UUID id = player.getUniqueId();
            online.add(id);
            Viewer viewer = viewers.get(id);
            if (viewer != null) {
                viewer.retiredTeams.entrySet().removeIf(entry -> {
                    if (tick < entry.getValue()) return false;
                    send(player, new PacketPlayOutScoreboardTeam(new Scoreboard().createTeam(entry.getKey()), 1));
                    return true;
                });
            }
            Location location = player.getLocation();
            Activity previous = activity.get(id);
            boolean moved = previous == null || !previous.location().getWorld().equals(location.getWorld())
                    || previous.location().distanceSquared(location) > 0.03 * 0.03
                    || Math.abs(angle(previous.location().getYaw() - location.getYaw())) > 0.5
                    || Math.abs(previous.location().getPitch() - location.getPitch()) > 0.5;
            if (moved) activity.put(id, new Activity(location, tick));
        }
        activity.keySet().retainAll(online);
        viewers.keySet().retainAll(online);
    }

    public void update(
            EntityPlayer bot,
            Collection<? extends Player> players,
            long tick,
            UUID opponent,
            Set<UUID> combatViewers,
            BotVisibilitySettings config,
            String name,
            boolean hasEquipment
    ) {
        configure(bot.getUniqueID(), config);
        EntityArmorStand nameplate = name != null ? nameplates.computeIfAbsent(bot.getUniqueID(), id -> {
            EntityArmorStand stand = new EntityArmorStand(bot.world);
            stand.setInvisible(true);
            stand.setGravity(false);
            ((ArmorStand) stand.getBukkitEntity()).setMarker(true);
            stand.setCustomName(name);
            stand.setCustomNameVisible(true);
            return stand;
        }) : null;
        if (nameplate != null) nameplate.setPosition(bot.locX, bot.locY + 1.8, bot.locZ);
        Location location = bot.getBukkitEntity().getLocation();
        PacketPlayOutEntityMetadata metadata = bot.getDataWatcher().a()
                ? new PacketPlayOutEntityMetadata(bot.getId(), bot.getDataWatcher(), false) : null;
        PacketPlayOutEntityTeleport teleport = null, plateTeleport = null;
        PacketPlayOutEntityHeadRotation rotation = null;
        PacketPlayOutEntityStatus hurt = null;
        for (Player player : players) {
            Viewer viewer = viewers.computeIfAbsent(player.getUniqueId(), id -> new Viewer());
            boolean fighting = player.getUniqueId().equals(opponent) || combatViewers.contains(player.getUniqueId());
            Display display = viewer.entities.get(bot.getUniqueID());
            boolean visible = fighting || display == null || tick - display.checkedAt >= config.visibilityCheckTicks()
                    ? visible(player, bot, tick, fighting, config) : display.visible;
            if (display != null && tick - display.checkedAt >= config.visibilityCheckTicks()) {
                display.checkedAt = tick;
                display.visible = visible;
            }
            if (!visible) {
                if (display != null) hide(player, bot, viewer);
                continue;
            }
            if (display == null) {
                if (viewer.revealTick != tick) {
                    viewer.revealTick = tick;
                    viewer.reveals = 0;
                }
                if (viewer.reveals >= config.maxRevealsPerTick()) continue;
                viewer.reveals++;
                display = new Display(tick);
                viewer.entities.put(bot.getUniqueID(), display);
            }
            if (!display.spawned && tick - display.revealedAt < config.spawnDelayTicks()) continue;
            if (!display.spawned) {
                send(player, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, bot));
                display.team = "b" + bot.getUniqueID().toString().replace("-", "").substring(0, 15);
                viewer.retiredTeams.remove(display.team);
                Scoreboard scoreboard = new Scoreboard();
                ScoreboardTeam team = scoreboard.createTeam(display.team);
                team.setNameTagVisibility(ScoreboardTeamBase.EnumNameTagVisibility.NEVER);
                scoreboard.addPlayerToTeam(bot.getProfile().getName(), display.team);
                send(player, new PacketPlayOutScoreboardTeam(team, 0));
                send(player, new PacketPlayOutNamedEntitySpawn(bot));
                if (nameplate != null) send(player, new PacketPlayOutSpawnEntityLiving(nameplate));
                display.spawned = true;
                display.profileRemoveAt = tick + PROFILE_REMOVE_DELAY_TICKS;
                if (hasEquipment) {
                    equipment(player, bot);
                    display.armor = Arrays.stream(bot.getBukkitEntity().getInventory().getArmorContents())
                            .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
                    ItemStack hand = bot.getBukkitEntity().getItemInHand();
                    display.hand = hand == null ? null : hand.clone();
                    display.nextEquipmentCheck =
                            StaggeredDeadline.after(tick, -bot.getId(), config.equipmentCheckTicks());
                }
            }
            if (display.profileRemoveAt > 0 && tick >= display.profileRemoveAt) {
                send(player,
                        new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, bot));
                display.profileRemoveAt = 0;
            }
            if (metadata != null) send(player, metadata);
            if (display.location == null || !display.location.equals(location)) {
                if (teleport == null) teleport = new PacketPlayOutEntityTeleport(bot);
                send(player, teleport);
                if (nameplate != null && (display.location == null || display.location.distanceSquared(location) > 0)) {
                    if (plateTeleport == null) plateTeleport = new PacketPlayOutEntityTeleport(nameplate);
                    send(player, plateTeleport);
                }
                if (display.location == null || display.location.getYaw() != location.getYaw()) {
                    if (rotation == null)
                        rotation = new PacketPlayOutEntityHeadRotation(bot, (byte) (bot.yaw * 256 / 360));
                    send(player, rotation);
                }
                display.location = location;
            }
            if (hasEquipment && tick >= display.nextEquipmentCheck) {
                display.nextEquipmentCheck = StaggeredDeadline.after(tick, -bot.getId(), config.equipmentCheckTicks());
                ItemStack[] equipment = bot.getBukkitEntity().getInventory().getArmorContents();
                ItemStack hand = bot.getBukkitEntity().getItemInHand();
                if (!Arrays.equals(display.armor, equipment) || !Objects.equals(display.hand, hand)) {
                    equipment(player, bot);
                    display.armor = Arrays.stream(equipment).map(item -> item == null ? null : item.clone())
                            .toArray(ItemStack[]::new);
                    display.hand = hand == null ? null : hand.clone();
                }
            }
            if (bot.hurtTicks > display.hurtTicks) {
                if (hurt == null) hurt = new PacketPlayOutEntityStatus(bot, (byte) 2);
                send(player, hurt);
            }
            display.hurtTicks = bot.hurtTicks;

        }
    }

    private boolean visible(
            Player player,
            EntityPlayer bot,
            long tick,
            boolean fighting,
            BotVisibilitySettings config
    ) {
        if (!player.getWorld().equals(bot.getBukkitEntity().getWorld())) return false;
        if (!player.getWorld().isChunkLoaded((int) Math.floor(bot.locX) >> 4, (int) Math.floor(bot.locZ) >> 4))
            return false;
        if (!player.canSee(bot.getBukkitEntity())) return false;
        Location eye = player.getEyeLocation();
        double dx = bot.locX - eye.getX();
        double dy = bot.locY + bot.getHeadHeight() - eye.getY();
        double dz = bot.locZ - eye.getZ();
        double distance = dx * dx + dy * dy + dz * dz;
        if (distance > config.viewDistance() * config.viewDistance()) return false;
        if (fighting) return true;
        Activity current = activity.get(player.getUniqueId());
        if (eye.getY() > config.maxViewerY()) return false;
        if (current != null && tick - current.tick() >= config.idleSeconds() * 20L) {
            this.onIdleHidden(player);
            return false;
        }
        if (distance <= config.nearbyDistance() * config.nearbyDistance()) return true;
        double yaw = Math.toRadians(eye.getYaw());
        double pitch = Math.toRadians(eye.getPitch());
        double horizontal = Math.cos(pitch);
        double dot = (-Math.sin(yaw) * horizontal * dx - Math.sin(pitch) * dy
                + Math.cos(yaw) * horizontal * dz) / Math.sqrt(distance);
        if (dot < Math.cos(Math.toRadians(config.viewAngle()))) return false;
        return player.hasLineOfSight(bot.getBukkitEntity());
    }

    public void equipment(Player player, EntityPlayer bot) {
        for (int slot = 0; slot < 5; slot++)
            send(player, new PacketPlayOutEntityEquipment(bot.getId(), slot, bot.getEquipment(slot)));
    }

    public boolean muteCombatSounds(UUID player, UUID bot, long tick) {
        BotVisibilitySettings config = configurations.get(bot);
        if (config == null) return false;
        Activity current = activity.get(player);
        if (current == null || tick - current.tick() < config.idleSeconds() * 20) return false;
        Viewer viewer = viewers.get(player);
        Display display = viewer == null ? null : viewer.entities.get(bot);
        return display == null || !display.spawned;
    }

    public boolean isVisible(UUID player, UUID bot) {
        Viewer viewer = viewers.get(player);
        Display display = viewer == null ? null : viewer.entities.get(bot);
        return display != null && display.spawned;
    }

    protected void onIdleHidden(Player player) {
    }

    public void restoreIdle(Player player, long tick, long idleTicks) {
        activity.put(player.getUniqueId(), new Activity(player.getLocation(), tick - idleTicks));
    }

    public void action(EntityPlayer bot, int action) {
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && isVisible(entry.getKey(), bot.getUniqueID())) {
                send(player, new PacketPlayOutAnimation(bot, action));
            }
        }
    }

    public void destroy(EntityPlayer bot) {
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && entry.getValue().entities.containsKey(bot.getUniqueID()))
                hide(player, bot, entry.getValue());
        }
        nameplates.remove(bot.getUniqueID());
        configurations.remove(bot.getUniqueID());
    }

    private void hide(Player player, EntityPlayer bot, Viewer viewer) {
        Display display = viewer.entities.remove(bot.getUniqueID());
        if (!display.spawned) return;
        send(player, new PacketPlayOutEntityDestroy(bot.getId()));
        send(player, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, bot));
        EntityArmorStand nameplate = nameplates.get(bot.getUniqueID());
        if (nameplate != null) send(player, new PacketPlayOutEntityDestroy(nameplate.getId()));
        if (display.team != null) {
            viewer.retiredTeams.put(display.team, currentTick + 2);
        }
    }

    private void send(Player player, Packet<?> packet) {
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
    }

    public void clear() {
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            for (String team : entry.getValue().retiredTeams.keySet()) {
                send(player, new PacketPlayOutScoreboardTeam(new Scoreboard().createTeam(team), 1));
            }
        }
        configurations.clear();
        viewers.clear();
        activity.clear();
        online.clear();
        nameplates.clear();
    }

    private static final class Viewer {
        private final Map<UUID, Display> entities = new HashMap<>();
        private final Map<String, Long> retiredTeams = new HashMap<>();
        private long revealTick;
        private int reveals;
    }

    private static final class Display {
        private final long revealedAt;
        private long checkedAt;
        private boolean visible = true, spawned;
        private String team;
        private Location location;
        private ItemStack[] armor;
        private ItemStack hand;
        private int hurtTicks;
        private long nextEquipmentCheck;
        private long profileRemoveAt;

        private Display(long tick) {
            revealedAt = tick;
            checkedAt = tick;
        }
    }

    private static double angle(double degrees) {
        return ((degrees + 180) % 360 + 360) % 360 - 180;
    }

    private record Activity(Location location, long tick) {
    }
}
