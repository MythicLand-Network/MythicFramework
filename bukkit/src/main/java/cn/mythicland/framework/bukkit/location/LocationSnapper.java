package cn.mythicland.framework.bukkit.location;

import org.bukkit.Location;

public final class LocationSnapper {
    private static final float VIEW_STEP = 45.0F;
    private static final float MIN_PITCH = -90.0F;
    private static final float MAX_PITCH = 90.0F;

    private LocationSnapper() {
    }

    public static Location snapBlockAndView(Location location) {
        if (location == null) throw new IllegalArgumentException("location must not be null");
        if (location.getWorld() == null) throw new IllegalArgumentException("location world must not be null");

        double snappedX = Math.floor(location.getX()) + 0.5D;
        double snappedZ = Math.floor(location.getZ()) + 0.5D;
        float snappedYaw = normalizeYaw(snapToStep(location.getYaw()));
        float snappedPitch = clampPitch(snapToStep(location.getPitch()));
        return new Location(location.getWorld(), snappedX, location.getY(), snappedZ, snappedYaw, snappedPitch);
    }

    private static float snapToStep(float angle) {
        return Math.round(angle / VIEW_STEP) * VIEW_STEP;
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0F;
        if (normalized <= -180.0F) {
            normalized += 360.0F;
        } else if (normalized > 180.0F) {
            normalized -= 360.0F;
        }
        return normalized;
    }

    private static float clampPitch(float pitch) {
        return Math.clamp(pitch, MIN_PITCH, MAX_PITCH);
    }
}
