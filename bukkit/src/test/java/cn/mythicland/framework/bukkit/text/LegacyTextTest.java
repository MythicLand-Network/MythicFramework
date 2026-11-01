package cn.mythicland.framework.bukkit.text;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyTextTest {

    @Test
    void translatesLegacyColoursAndPreservesLineOrder() {
        assertEquals("§aGreen", LegacyText.colorize("&aGreen"));
        assertEquals("§b§lDreamRPG", LegacyText.colorize("&b&lDreamRPG"));
        assertEquals("Green", LegacyText.stripColor("&aGreen"));
        assertEquals(List.of("§cRed", "§bBlue"), LegacyText.colorize(List.of("&cRed", "&bBlue")));
    }
}
