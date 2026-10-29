package cn.mythicland.framework.bukkit.message;

import org.bukkit.Sound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoundCompatTest {
    @Test
    void requireAny_prefersFirstNameAndFallsBackToSecond() {
        Sound sound = Sound.values()[0];
        assertEquals(sound, SoundCompat.requireAny(sound.name(), "INVALID_SOUND"));
        assertEquals(sound, SoundCompat.requireAny("INVALID_SOUND", sound.name()));
        assertThrows(IllegalArgumentException.class, () -> SoundCompat.requireAny("INVALID_SOUND", "ALSO_INVALID"));
    }
}
