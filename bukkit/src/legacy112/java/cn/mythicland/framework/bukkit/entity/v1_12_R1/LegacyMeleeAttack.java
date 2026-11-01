package cn.mythicland.framework.bukkit.entity.v1_12_R1;

import cn.mythicland.framework.bukkit.MainThread;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Native Paper 1.12.2 melee targeting, arm animation and normal player attacks.
 */
public final class LegacyMeleeAttack {

    private LegacyMeleeAttack() {
    }

    /**
     * Finds the nearest living entity intersecting the crosshair within reach and before any block.
     * A rejected entity blocks entities behind it. Call on the server thread; returns null on a miss.
     */
    public static LivingEntity target(Player player, double reach, Predicate<LivingEntity> allowed) {
        MainThread.require("Melee targeting requires the server thread");
        if (!Double.isFinite(reach) || reach <= 0)
            throw new IllegalArgumentException("Reach must be finite and positive");
        try {
            Location eye = player.getEyeLocation();
            Vector direction = eye.getDirection().normalize().multiply(reach);
            Object start = Access.VECTOR.newInstance(eye.getX(), eye.getY(), eye.getZ());
            Object end = Access.VECTOR.newInstance(
                    eye.getX() + direction.getX(),
                    eye.getY() + direction.getY(),
                    eye.getZ() + direction.getZ()
            );
            Object world = Access.WORLD_HANDLE.invoke(player.getWorld());
            Object block = Access.BLOCK_TRACE.invoke(world, start, end, true, false, false);
            double nearestDistance = reach * reach;
            if (block != null) nearestDistance = distanceSquared(start, block);
            LivingEntity nearest = null;
            for (Entity entity : player.getNearbyEntities(reach, reach, reach)) {
                if (!(entity instanceof LivingEntity living) || living == player || living.isDead() || !living.isValid())
                    continue;
                Object handle = Access.ENTITY_HANDLE.invoke(living);
                Object box = Access.BOX.invoke(handle);
                box = Access.GROW.invoke(box, 0.1D, 0.1D, 0.1D);
                double distance = 0;
                if (!Boolean.TRUE.equals(Access.CONTAINS.invoke(box, start))) {
                    Object hit = Access.ENTITY_TRACE.invoke(box, start, end);
                    if (hit == null) continue;
                    distance = distanceSquared(start, hit);
                }
                if (distance >= nearestDistance) continue;
                nearestDistance = distance;
                nearest = living;
            }
            if (nearest == null || !allowed.test(nearest)) return null;
            return nearest;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot trace a Paper 1.12.2 melee target", exception);
        }
    }

    /**
     * Sends the native main-hand arm animation on the server thread, including misses.
     */
    public static void swing(Player player) {
        MainThread.require("Melee animation requires the server thread");
        try {
            Object handle = Access.ENTITY_HANDLE.invoke(player);
            Object world = Access.WORLD_HANDLE.invoke(player.getWorld());
            Object tracker = Access.TRACKER.invoke(world);
            Access.SEND_ANIMATION.invoke(tracker, handle, Access.ANIMATION.newInstance(handle, 0));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot animate a Paper 1.12.2 melee attack", exception);
        }
    }

    /**
     * Performs a native attack on the server thread, retaining Bukkit damage events and attribution.
     * The caller owns target eligibility, range and collateral-damage restrictions.
     */
    public static void attack(Player player, LivingEntity target) {
        MainThread.require("Melee attack requires the server thread");
        try {
            Access.ATTACK.invoke(Access.ENTITY_HANDLE.invoke(player), Access.ENTITY_HANDLE.invoke(target));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot perform a Paper 1.12.2 melee attack", exception);
        }
    }

    private static double distanceSquared(Object start, Object hit) throws ReflectiveOperationException {
        return ((Number) Access.DISTANCE.invoke(start, Access.HIT_POSITION.get(hit))).doubleValue();
    }

    private static final class Access {
        private static final Constructor<?> VECTOR;
        private static final Method ENTITY_HANDLE;
        private static final Method WORLD_HANDLE;
        private static final Method BLOCK_TRACE;
        private static final Method BOX;
        private static final Method GROW;
        private static final Method CONTAINS;
        private static final Method ENTITY_TRACE;
        private static final Method DISTANCE;
        private static final Field HIT_POSITION;
        private static final Method ATTACK;
        private static final Constructor<?> ANIMATION;
        private static final Method TRACKER;
        private static final Method SEND_ANIMATION;

        static {
            try {
                String nms = "net.minecraft.server.v1_12_R1.";
                Class<?> vector = Class.forName(nms + "Vec3D");
                Class<?> box = Class.forName(nms + "AxisAlignedBB");
                Class<?> entity = Class.forName(nms + "Entity");
                VECTOR = vector.getConstructor(double.class, double.class, double.class);
                ENTITY_HANDLE = Class.forName("org.bukkit.craftbukkit.v1_12_R1.entity.CraftLivingEntity").getMethod("getHandle");
                WORLD_HANDLE = Class.forName("org.bukkit.craftbukkit.v1_12_R1.CraftWorld").getMethod("getHandle");
                BLOCK_TRACE = Class.forName(nms + "World").getMethod(
                        "rayTrace", vector, vector, boolean.class, boolean.class, boolean.class
                );
                BOX = entity.getMethod("getBoundingBox");
                GROW = box.getMethod("grow", double.class, double.class, double.class);
                CONTAINS = box.getMethod("b", vector);
                ENTITY_TRACE = box.getMethod("b", vector, vector);
                DISTANCE = vector.getMethod("distanceSquared", vector);
                HIT_POSITION = Class.forName(nms + "MovingObjectPosition").getField("pos");
                ATTACK = Class.forName(nms + "EntityHuman").getMethod("attack", entity);
                ANIMATION = Class.forName(nms + "PacketPlayOutAnimation").getConstructor(entity, int.class);
                TRACKER = Class.forName(nms + "WorldServer").getMethod("getTracker");
                SEND_ANIMATION = Class.forName(nms + "EntityTracker").getMethod(
                        "sendPacketToEntity", entity, Class.forName(nms + "Packet")
                );
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }
}
