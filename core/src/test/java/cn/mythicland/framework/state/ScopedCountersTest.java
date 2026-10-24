package cn.mythicland.framework.state;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScopedCountersTest {
    @Test
    void isolatesOwnersKeysAndCounterpartsAndConsumesTheWholeCounter() {
        var counters = new ScopedCounters<String, String>();
        assertEquals(1, counters.preview("player", "sword", "target"));
        assertEquals(1, counters.preview("player", "sword", "target"));
        counters.record("player", "sword", "target");
        assertEquals(2, counters.preview("player", "sword", "target"));
        assertEquals(1, counters.preview("other", "sword", "target"));
        assertEquals(1, counters.preview("player", "bow", "target"));
        assertEquals(1, counters.preview("player", "sword", "other"));
        assertEquals(1, counters.preview("player", "sword", null));
        assertFalse(counters.consume("player", "sword", "target", 2));
        counters.record("player", "sword", "target");
        counters.record("player", "sword", "target");
        assertTrue(counters.consume("player", "sword", "target", 2));
        assertEquals(1, counters.preview("player", "sword", "target"));
        assertThrows(IllegalArgumentException.class, () -> counters.consume("player", "sword", null, 0));
    }

    @Test
    void snapshotsAndCleanupPreserveUnrelatedState() {
        var counters = new ScopedCounters<String, String>();
        counters.set("player", "combo", null, 4);
        counters.record("player", "combo", "target");
        counters.record("player", "charge", "target");
        counters.record("other", "combo", "target");
        Map<String, Integer> snapshot = new HashMap<>();
        counters.forEach("player", (key, target, count) -> {
            if (target == null) snapshot.put(key, count);
        });
        assertEquals(Map.of("combo", 4), snapshot);

        counters.clear("player", "combo", "target");
        assertEquals(5, counters.preview("player", "combo", null));
        assertEquals(1, counters.preview("player", "combo", "target"));
        counters.clear("player", "combo"::equals);
        assertEquals(1, counters.preview("player", "combo", null));
        assertEquals(2, counters.preview("player", "charge", "target"));
        assertEquals(2, counters.preview("other", "combo", "target"));

        counters.clearMatching((key, target) -> key.equals("charge") && "target".equals(target));
        assertEquals(1, counters.preview("player", "charge", "target"));
        counters.clearOwner("player");
        assertEquals(2, counters.preview("other", "combo", "target"));
        counters.clear();
        assertEquals(1, counters.preview("other", "combo", "target"));
    }

    @Test
    void overflowDoesNotReplaceTheStoredCount() {
        var counters = new ScopedCounters<String, String>();
        counters.set("player", "combo", null, Integer.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> counters.record("player", "combo", null));
        assertTrue(counters.consume("player", "combo", null, Integer.MAX_VALUE));
        assertEquals(1, counters.preview("player", "combo", null));
    }
}
