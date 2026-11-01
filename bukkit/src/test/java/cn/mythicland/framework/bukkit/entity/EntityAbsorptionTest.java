package cn.mythicland.framework.bukkit.entity;

import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityAbsorptionTest {

    @Test
    void readsTheNativeValueAndRejectsUnsupportedEntityImplementations() {
        var entity = mock(LivingEntity.class, withSettings().extraInterfaces(HandleOwner.class));
        when(((HandleOwner) entity).getHandle()).thenReturn(new NativeHealth());
        assertEquals(6.5, EntityAbsorption.get(entity));
        assertThrows(IllegalStateException.class, () -> EntityAbsorption.get(mock(LivingEntity.class)));
    }

    public interface HandleOwner {
        NativeHealth getHandle();
    }

    public static final class NativeHealth {
        public float getAbsorptionHearts() {
            return 6.5F;
        }
    }
}
