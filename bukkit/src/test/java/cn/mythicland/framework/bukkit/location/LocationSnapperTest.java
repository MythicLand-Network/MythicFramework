package cn.mythicland.framework.bukkit.location;


import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.Mockito.mock;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LocationSnapperTest {
    private World world;

    @BeforeEach
    public void setUp() {
        this.world = mock(World.class);
    }

    @Test
    public void snapBlockAndView_shouldSnapToBlockCenterAndNearestFortyFiveView() {
        Location source = new Location(
                this.world,
                10.12D,
                64.0D,
                -3.88D,
                68.0F,
                17.0F
        );

        Location snapped = LocationSnapper.snapBlockAndView(source);

        assertEquals(10.5D, snapped.getX(), 0.0001D);
        assertEquals(64.0D, snapped.getY(), 0.0001D);
        assertEquals(-3.5D, snapped.getZ(), 0.0001D);
        assertEquals(90.0F, snapped.getYaw(), 0.0001F);
        assertEquals(0.0F, snapped.getPitch(), 0.0001F);
    }

    @Test
    public void snapBlockAndView_shouldNormalizeWrappedYaw() {
        Location source = new Location(
                this.world,
                0.0D,
                70.0D,
                0.0D,
                226.0F,
                0.0F
        );

        Location snapped = LocationSnapper.snapBlockAndView(source);

        assertEquals(-135.0F, snapped.getYaw(), 0.0001F);
    }

    @Test
    public void snapBlockAndView_shouldSnapPitchToNearestFortyFiveAndClampExtremes() {
        Location source = new Location(
                this.world,
                0.0D,
                70.0D,
                0.0D,
                0.0F,
                67.0F
        );

        Location snapped = LocationSnapper.snapBlockAndView(source);

        assertEquals(45.0F, snapped.getPitch(), 0.0001F);
    }
}
