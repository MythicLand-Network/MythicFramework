package cn.mythicland.framework.bukkit.hologram.v1_8_R3;

import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Stream;

public class PacketHologram {
    private final double entityAttackDistance;
    private final double clickDistance;
    private final List<EntityArmorStand> stands;
    private final List<EntityArmorStand> clickTargets;
    private List<String> text;

    public PacketHologram(
            List<Location> locations,
            List<String> text,
            boolean clickable,
            double entityAttackDistance,
            double clickDistance
    ) {
        if (locations.size() != text.size()) throw new IllegalArgumentException("Each location requires one text line");
        if (!Double.isFinite(entityAttackDistance) || !Double.isFinite(clickDistance)
                || entityAttackDistance < 0 || clickDistance < entityAttackDistance) {
            throw new IllegalArgumentException("Invalid hologram click distances");
        }
        this.entityAttackDistance = entityAttackDistance;
        this.clickDistance = clickDistance;
        List<EntityArmorStand> stands = new ArrayList<>();
        List<EntityArmorStand> clickTargets = new ArrayList<>();
        var world = locations.isEmpty() ? null : Objects.requireNonNull(locations.getFirst().getWorld());
        for (int index = 0; index < locations.size(); index++) {
            Location location = locations.get(index);
            if (!world.equals(location.getWorld()))
                throw new IllegalArgumentException("Hologram lines must share a world");
            CraftWorld craftWorld = (CraftWorld) world;
            EntityArmorStand stand = new EntityArmorStand(craftWorld.getHandle());
            stand.setInvisible(true);
            stand.setGravity(false);
            ((ArmorStand) stand.getBukkitEntity()).setMarker(true);
            // Marker stands have zero height; the client adds 0.5 blocks above them for the name.
            stand.setLocation(location.getX(), location.getY() - 0.5D, location.getZ(), 0, 0);
            stand.setCustomName(text.get(index));
            stand.setCustomNameVisible(!text.get(index).isEmpty());
            stands.add(stand);
            if (clickable) {
                EntityArmorStand target = new EntityArmorStand(craftWorld.getHandle());
                target.setInvisible(true);
                target.setGravity(false);
                target.setSmall(true);
                target.width *= 0.5F;
                target.length *= 0.5F;
                target.setLocation(location.getX(), location.getY() - 0.5D, location.getZ(), 0, 0);
                clickTargets.add(target);
            }
        }
        this.stands = List.copyOf(stands);
        this.clickTargets = List.copyOf(clickTargets);
        this.text = List.copyOf(text);
    }

    private static void send(Player player, Packet<?> packet) {
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
    }

    public void show(Player player) {
        for (EntityArmorStand stand : this.stands) send(player, new PacketPlayOutSpawnEntityLiving(stand));
        for (EntityArmorStand stand : this.clickTargets) send(player, new PacketPlayOutSpawnEntityLiving(stand));
    }

    public void hide(Player player) {
        send(
                player, new PacketPlayOutEntityDestroy(Stream.concat(
                        this.stands.stream(), this.clickTargets.stream()
                ).mapToInt(EntityArmorStand::getId).toArray())
        );
    }

    public boolean isClickTarget(int entityId) {
        return this.clickTargets.stream().anyMatch(stand -> stand.getId() == entityId);
    }

    public boolean acceptsAttack(Player player, int entityId) {
        EntityArmorStand target = this.clickTargets.stream()
                .filter(stand -> stand.getId() == entityId).findFirst().orElse(null);
        return target != null && this.acceptsAttack(player, List.of(target), 0.0D, this.entityAttackDistance);
    }

    public boolean acceptsDistantAttack(Player player) {
        return this.acceptsAttack(player, this.clickTargets, this.entityAttackDistance, this.clickDistance);
    }

    private boolean acceptsAttack(
            Player player,
            List<EntityArmorStand> targets,
            double minimumDistance,
            double maximumDistance
    ) {
        if (targets.isEmpty() || !player.getWorld().equals(targets.getFirst().world.getWorld())) return false;
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize().multiply(maximumDistance);
        Vec3D start = new Vec3D(eye.getX(), eye.getY(), eye.getZ());
        Vec3D end = start.add(direction.getX(), direction.getY(), direction.getZ());
        MovingObjectPosition hit = targets.stream()
                .map(target -> target.getBoundingBox().grow(0.1D, 0.1D, 0.1D).a(start, end))
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(targetHit -> start.distanceSquared(targetHit.pos)))
                .orElse(null);
        if (hit == null ||
                (minimumDistance > 0.0D && start.distanceSquared(hit.pos) <= minimumDistance * minimumDistance)
                || start.distanceSquared(hit.pos) > maximumDistance * maximumDistance) return false;
        MovingObjectPosition blockHit = ((CraftWorld) player.getWorld()).getHandle()
                .rayTrace(start, end, true, false, false);
        return blockHit == null || blockHit.pos == null
                || start.distanceSquared(hit.pos) < start.distanceSquared(blockHit.pos);
    }

    public void update(List<String> text, Collection<Player> viewers) {
        if (text.size() != this.stands.size()) throw new IllegalArgumentException("Text line count cannot change");
        for (int index = 0; index < this.stands.size(); index++) {
            if (this.text.get(index).equals(text.get(index))) continue;
            EntityArmorStand stand = this.stands.get(index);
            stand.setCustomName(text.get(index));
            stand.setCustomNameVisible(!text.get(index).isEmpty());
            PacketPlayOutEntityMetadata packet =
                    new PacketPlayOutEntityMetadata(stand.getId(), stand.getDataWatcher(), true);
            for (Player player : viewers) send(player, packet);
        }
        this.text = List.copyOf(text);
    }
}
