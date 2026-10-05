package cn.mythicland.framework.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowCounterTest {
    @Test
    void countShouldExpireEventsAtTheWindowBoundary() {
        SlidingWindowCounter counter = new SlidingWindowCounter(60000L, 44);
        counter.record(0L);
        counter.record(1000L);
        assertEquals(2, counter.count(59999L));
        assertEquals(1, counter.count(60000L));
        assertEquals(0, counter.count(61000L));
    }

    @Test
    void cappedCounterShouldRetainNewestEvents() {
        SlidingWindowCounter counter = new SlidingWindowCounter(10L, 2);
        counter.record(0L);
        counter.record(5L);
        counter.record(8L);
        assertEquals(2, counter.count(10L));
        assertEquals(1, counter.count(15L));
        assertEquals(0, counter.count(18L));
    }

    @Test
    void counterShouldRejectInvalidWindowAndCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounter(0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounter(1L, 0));
    }
}
