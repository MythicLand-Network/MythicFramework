package cn.mythicland.framework.world;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SquareSpiralTest {
    @Test
    void expandsCompleteSquaresWithoutOverlappingOrSkippingAdjacentPlots() {
        HashSet<SquareSpiral.Coordinate> plots = new HashSet<>();
        SquareSpiral.Coordinate previous = SquareSpiral.at(0);
        plots.add(previous);
        for (int i = 1; i < 121; i++) {
            SquareSpiral.Coordinate current = SquareSpiral.at(i);
            assertTrue(plots.add(current));
            assertEquals(1, Math.abs(current.x() - previous.x()) + Math.abs(current.z() - previous.z()));
            assertTrue(Math.abs(current.x()) <= 5 && Math.abs(current.z()) <= 5);
            previous = current;
        }
        assertEquals(121, plots.size());
        assertTrue(plots.contains(new SquareSpiral.Coordinate(-5, -5)));
    }
}
