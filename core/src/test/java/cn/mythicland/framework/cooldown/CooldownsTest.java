package cn.mythicland.framework.cooldown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownsTest {
    @Test
    void tryStart_shouldPreserveActiveDeadlineAndAllowExactExpiry() {
        Cooldowns<String> cooldowns = new Cooldowns<>();
        assertTrue(cooldowns.tryStart("attack", 100, 1000));
        assertFalse(cooldowns.tryStart("attack", 500, 1050));
        assertEquals(50, cooldowns.remainingMillis("attack", 1050));
        assertTrue(cooldowns.tryStart("heal", 200, 1050));
        assertTrue(cooldowns.tryStart("attack", 100, 1100));
        assertEquals(100, cooldowns.remainingMillis("attack", 1100));
        assertFalse(cooldowns.tryStart(null, 100, 1100));
        assertFalse(cooldowns.tryStart("invalid", 0, 1100));
        assertEquals(2, cooldowns.size());
    }

    @Test
    void expiryAndReduction_shouldRemoveOnlyExpiredEntries() {
        Cooldowns<String> cooldowns = new Cooldowns<>();
        cooldowns.start("first", 100, 1000);
        cooldowns.start("second", 200, 1000);
        cooldowns.reduce("first", 40, 1010);

        assertEquals(50, cooldowns.remainingMillis("first", 1010));
        assertEquals(0, cooldowns.remainingMillis("first", 1060));
        assertEquals(140, cooldowns.remainingMillis("second", 1060));
        assertEquals(1, cooldowns.size());
        cooldowns.reduce("second", 500, 1060);
        assertEquals(0, cooldowns.remainingMillis("second", 1060));
        assertEquals(0, cooldowns.size());
    }

    @Test
    void clear_shouldPreserveOtherOwnersAndIgnoreInvalidDurations() {
        Cooldowns<String> cooldowns = new Cooldowns<>();
        cooldowns.start("alice:attack", 100, 1000);
        cooldowns.start("alice:heal", 100, 1000);
        cooldowns.start("bob:attack", 100, 1000);
        cooldowns.start("bob:attack", 0, 1000);
        cooldowns.start(null, 100, 1000);
        assertEquals(java.util.Map.of("alice:attack", 50L, "alice:heal", 50L),
                cooldowns.remaining(key -> key.startsWith("alice:"), 1050));
        cooldowns.clear(key -> key.startsWith("alice:"));

        assertEquals(1, cooldowns.size());
        assertEquals(100, cooldowns.remainingMillis("bob:attack", 1000));
    }
}
