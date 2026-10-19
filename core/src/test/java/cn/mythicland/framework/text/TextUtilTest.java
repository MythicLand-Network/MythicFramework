package cn.mythicland.framework.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextUtilTest {
    @Test
    void translation_shouldPreserveEachCallersExistingCaseAndNullContract() {
        assertEquals("§AName &z §Lbold", TextUtil.color("&AName &z &Lbold"));
        assertEquals("§aName &z §lbold", TextUtil.translate("&AName &z &Lbold"));
        assertEquals("", TextUtil.color(null));
        assertNull(TextUtil.translate(null));
    }

    @Test
    void placeholders_shouldKeepReplacementValuesUncolored() {
        assertEquals("§a&cPlayer ", TextUtil.placeholders("&a{0} {1}", "&cPlayer", null));
    }
}
