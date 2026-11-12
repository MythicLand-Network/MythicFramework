package cn.mythicland.framework.bukkit.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GridPathfinderTest {
    interface BlockWorld extends World {
        int getBlockTypeIdAt(int x, int y, int z);
    }

    @Test
    void directRouteReadsEachCoveredColumnOnceAndRechecksChangedBlocks() {
        BlockWorld world = flatWorld();
        GridPathfinder finder = new GridPathfinder();
        Location start = new Location(world, .5, 64, .5), goal = new Location(world, 2.5, 64, .5);

        assertTrue(finder.canWalkDirectly(start, goal));

        for (int x = 0; x <= 2; x++) {
            for (int y = 63; y <= 65; y++) verify(world).getBlockTypeIdAt(x, y, 0);
        }
        verify(world, times(9)).getBlockTypeIdAt(anyInt(), anyInt(), anyInt());
        when(world.getBlockTypeIdAt(1, 64, 0)).thenReturn(Material.STONE.getId());
        assertFalse(finder.canWalkDirectly(start, goal), "A new check must see the newly placed obstacle");
    }

    @Test
    void directRouteKeepsFootprintEdgesAcrossNegativeCoordinatesAndDiagonals() {
        BlockWorld world = flatWorld();
        GridPathfinder finder = new GridPathfinder();
        Location start = new Location(world, -2.99, 64, -.99), goal = new Location(world, 1.01, 64, -.99);
        assertTrue(finder.canWalkDirectly(start, goal));
        assertTrue(finder.canWalkDirectly(goal, start));
        when(world.getBlockTypeIdAt(1, 64, -2)).thenReturn(Material.STONE.getId());
        assertFalse(finder.canWalkDirectly(start, goal), "The far footprint edge must not alias an earlier column");
        assertFalse(finder.canWalkDirectly(goal, start));
        Location diagonalStart = new Location(world, -3.1, 64, -3.1), diagonalGoal = new Location(world, -.3, 64, -.3);
        assertTrue(finder.canWalkDirectly(diagonalStart, diagonalGoal));
        when(world.getBlockTypeIdAt(0, 65, 0)).thenReturn(Material.STONE.getId());
        assertFalse(finder.canWalkDirectly(diagonalStart, diagonalGoal), "Headroom is required at the diagonal endpoint");
    }

    @Test
    void directRouteDoesNotReadAcrossAnUnloadedChunkBoundary() {
        BlockWorld world = flatWorld();
        when(world.isChunkLoaded(1, 0)).thenReturn(false);
        GridPathfinder finder = new GridPathfinder();

        assertFalse(finder.canWalkDirectly(new Location(world, 15.5, 64, .5), new Location(world, 16.5, 64, .5)));

        verify(world, never()).getBlockTypeIdAt(eq(16), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    private static BlockWorld flatWorld() {
        BlockWorld world = mock(BlockWorld.class);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockTypeIdAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                call.<Integer>getArgument(1) < 64 ? Material.STONE.getId() : Material.AIR.getId());
        return world;
    }

    @Test
    void carpetRoutesUseTheGroundBelowAndRemainAtTheCarpetHeight() {
        BlockWorld world = mock(BlockWorld.class);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockTypeIdAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int y = call.getArgument(1);
            return y < 64 ? Material.STONE.getId() : y == 64 ? Material.CARPET.getId() : Material.AIR.getId();
        });
        GridPathfinder finder = new GridPathfinder();
        Location start = new Location(world, .5, 64 + 1.0 / 16, .5);
        Location goal = new Location(world, 2.5, 64 + 1.0 / 16, .5);
        assertTrue(finder.isWalkable(goal));
        assertTrue(finder.canWalkDirectly(start, goal));
        var path = finder.find(start, goal, 128, .6, (fx, fy, fz, tx, ty, tz) -> true);
        assertFalse(path.isEmpty());
        assertTrue(path.stream().allMatch(point -> point.getBlockY() == 64));
        assertFalse(finder.isWalkable(goal.clone().add(0, 1, 0)));
        when(world.getBlockTypeIdAt(2, 63, 0)).thenReturn(Material.AIR.getId());
        assertFalse(finder.isWalkable(goal));
        assertFalse(finder.canWalkDirectly(start, goal));
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void reusableSearchFindsRoutesAndSeesChangedBlocks() {
        BlockWorld world = mock(BlockWorld.class, withSettings().stubOnly());
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockTypeIdAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                call.<Integer>getArgument(1) < 64 ? Material.STONE.getId() : Material.AIR.getId());
        GridPathfinder finder = new GridPathfinder();
        Location start = new Location(world, -.5, 64, -.5), goal = new Location(world, -5.5, 64, -5.5);
        assertEquals(5, finder.find(start, goal, 128, .6, (fx, fy, fz, tx, ty, tz) -> true).size());
        assertTrue(finder.find(start, goal, 128, .6, (fx, fy, fz, tx, ty, tz) -> false).isEmpty());
        assertFalse(finder.find(start, goal, 128, .6, (fx, fy, fz, tx, ty, tz) -> true).isEmpty());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertTrue(finder.find(start, goal, 128, .6, (fx, fy, fz, tx, ty, tz) -> true).isEmpty());
    }
}
