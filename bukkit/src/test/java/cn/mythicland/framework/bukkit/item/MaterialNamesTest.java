package cn.mythicland.framework.bukkit.item;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialNamesTest {
    @Test
    void firstAvailable_shouldPreserveCandidateOrderAndFallbacks() {
        assertEquals(Material.STONE, MaterialNames.firstAvailable(Material.PAPER, "STONE", "DIRT"));
        assertEquals(Material.DIRT, MaterialNames.firstAvailable("UNAVAILABLE", "DIRT", Material.PAPER));
        assertEquals(Material.PAPER, MaterialNames.firstAvailable("UNAVAILABLE", Material.PAPER));
        assertEquals(Material.PAPER, MaterialNames.firstAvailable(Material.PAPER, (String[]) null));
        assertEquals(Material.STONE, MaterialNames.firstAvailable(Material.PAPER, null, "", " ", "STONE"));
    }

    @Test
    void requireAny_shouldResolveAnAvailableNameOrFail() {
        assertEquals(Material.DIRT, MaterialNames.requireAny("test material", "UNAVAILABLE", "DIRT"));
        assertThrows(IllegalStateException.class, () -> MaterialNames.requireAny("test material", "UNAVAILABLE"));
    }
}
