package cn.mythicland.framework.cooldown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownsTest {
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
        cooldowns.clear(key -> key.startsWith("alice:"));

        assertEquals(1, cooldowns.size());
        assertEquals(100, cooldowns.remainingMillis("bob:attack", 1000));
    }
}
