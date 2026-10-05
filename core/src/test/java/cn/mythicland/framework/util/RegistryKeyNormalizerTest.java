package cn.mythicland.framework.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegistryKeyNormalizerTest {
    @Test
    void normalize_shouldHandleCamelCaseAndExistingKeysWithoutGameplayAliases() {
        assertEquals("http_server_key", RegistryKeyNormalizer.normalize(" HTTPServerKey "));
        assertEquals("kill_streak", RegistryKeyNormalizer.normalize("Kill-Streak"));
        assertEquals("kill_streak", RegistryKeyNormalizer.normalize("Kill Streak"));
        assertEquals("already_key", RegistryKeyNormalizer.normalize("ALREADY_KEY"));
        assertEquals("leech", RegistryKeyNormalizer.normalize("leech"));
        assertEquals("", RegistryKeyNormalizer.normalize("  "));
        assertNull(RegistryKeyNormalizer.normalize(null));
    }
}
