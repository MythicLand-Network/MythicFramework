package cn.mythicland.framework.bukkit.container;

import cn.mythicland.framework.bukkit.message.SoundCompat;
import org.bukkit.Sound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the vanilla ender-chest animation contract without starting Bukkit.
 */
class ContainerAnimationSpecTest {

    @Test
    void enderChestUsesTheVanillaBlockActionAndSounds() {
        ContainerAnimationSpec specification = ContainerAnimationSpec.enderChest();

        assertEquals(1, specification.blockAction());
        assertEquals(SoundCompat.requireAny("CHEST_OPEN", "BLOCK_ENDERCHEST_OPEN"), specification.openSound());
        assertEquals(SoundCompat.requireAny("CHEST_CLOSE", "BLOCK_ENDERCHEST_CLOSE"), specification.closeSound());
        assertEquals(1.0F, specification.openVolume());
        assertEquals(1.0F, specification.openPitch());
        assertEquals(1.0F, specification.closeVolume());
        assertEquals(0.8F, specification.closePitch());
        assertEquals(64.0D, specification.broadcastDistance());
    }

    @Test
    void negativeBroadcastDistanceIsRejectedBeforePacketUse() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContainerAnimationSpec(
                        1,
                        SoundCompat.requireAny("CHEST_OPEN", "BLOCK_ENDERCHEST_OPEN"),
                        SoundCompat.requireAny("CHEST_CLOSE", "BLOCK_ENDERCHEST_CLOSE"),
                        1.0F,
                        1.0F,
                        1.0F,
                        0.8F,
                        -1.0D
                )
        );
    }
}
