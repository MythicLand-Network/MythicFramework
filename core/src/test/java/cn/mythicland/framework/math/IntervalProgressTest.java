package cn.mythicland.framework.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IntervalProgressTest {
    @Test
    public void fractionalIntervals_shouldCountOnlyCompletedMilestones() {
        double interval = 17.5D;
        assertEquals(0, IntervalProgress.completedCount(-1.0D, interval));
        assertEquals(0, IntervalProgress.completedCount(17.4D, interval));
        assertEquals(1, IntervalProgress.completedCount(17.5D, interval));
        assertEquals(2, IntervalProgress.completedCount(35.0D, interval));
        assertEquals(3, IntervalProgress.crossedCount(1.6D, 6.4D, 2.1D));
    }

    @Test
    public void accumulatedFractionalProgress_shouldReachExactMilestone() {
        double progress = 0.0D;
        for (int step = 0; step < 50; step++) progress += 1.6D;
        assertEquals(8, IntervalProgress.completedCount(progress, 10.0D));
    }

    @Test
    public void invalidIntervalOrProgress_shouldFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> IntervalProgress.completedCount(1.0D, 0.0D));
        assertThrows(IllegalArgumentException.class, () -> IntervalProgress.completedCount(1.0D, -1.0D));
        assertThrows(IllegalArgumentException.class, () -> IntervalProgress.completedCount(1.0D, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> IntervalProgress.completedCount(1.0D, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> IntervalProgress.completedCount(Double.NaN, 1.0D));
    }
}
