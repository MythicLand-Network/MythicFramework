package cn.mythicland.framework.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LruCacheTest {
    @Test
    void getShouldKeepRecentlyUsedEntriesAndUseKeyEquality() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("first", 1);
        cache.put("second", 2);

        assertEquals(1, cache.get(new String("first")));
        cache.put("third", 3);

        assertNull(cache.get("second"));
        assertEquals(1, cache.get("first"));
        assertEquals(3, cache.get("third"));
    }

    @Test
    void putShouldIgnoreNullsAndKeepAtLeastOneEntry() {
        LruCache<String, Integer> cache = new LruCache<>(0);
        cache.put("first", 1);
        cache.put(null, 2);
        cache.put("first", null);
        assertEquals(1, cache.get("first"));
        assertNull(cache.get(null));

        cache.put("second", 2);
        assertNull(cache.get("first"));
        assertEquals(2, cache.get("second"));

        cache.clear();
        assertNull(cache.get("second"));
    }

    @Test
    void removeShouldOnlyDiscardTheRequestedKey() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("first", 1);
        cache.put("second", 2);

        assertEquals(1, cache.remove(new String("first")));
        assertNull(cache.get("first"));
        assertEquals(2, cache.get("second"));
        assertNull(cache.remove(null));
        assertNull(cache.remove("missing"));
    }
}
