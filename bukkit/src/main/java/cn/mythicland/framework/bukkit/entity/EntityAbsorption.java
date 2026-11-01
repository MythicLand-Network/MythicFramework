package cn.mythicland.framework.bukkit.entity;

import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Method;

/**
 * Reads legacy living-entity absorption that Bukkit exposes only for players.
 */
public final class EntityAbsorption {

    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> type) {
            try {
                Method handle = type.getMethod("getHandle");
                return new Access(handle, handle.getReturnType().getMethod("getAbsorptionHearts"));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot read living-entity absorption: " + type.getName(), exception);
            }
        }
    };

    private EntityAbsorption() {
    }

    /**
     * Returns the current absorption in health points. Call on the server thread with a CraftBukkit
     * entity. Unsupported implementations fail explicitly; accessors are retained per entity class.
     */
    public static double get(LivingEntity entity) {
        Access access = ACCESS.get(entity.getClass());
        try {
            return ((Number) access.absorption().invoke(access.handle().invoke(entity))).doubleValue();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read absorption for entity " + entity.getUniqueId(), exception);
        }
    }

    private record Access(Method handle, Method absorption) {
    }
}
