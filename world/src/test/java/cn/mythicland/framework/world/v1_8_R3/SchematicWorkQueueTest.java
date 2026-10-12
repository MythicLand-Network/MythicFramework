package cn.mythicland.framework.world.v1_8_R3;

import cn.mythicland.framework.bukkit.task.TaskScheduler;
import net.minecraft.server.v1_8_R3.Chunk;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.DispenserRegistry;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchematicWorkQueueTest {
    @Test
    void jobsShareChunkBudgetRotateFairlyAndCancelWithoutLoading() {
        DispenserRegistry.c();
        Queue<Runnable> ticks = new ArrayDeque<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(call -> { ticks.add(call.getArgument(0)); return null; }).when(scheduler).executeSyncLater(any(), anyLong());
        World world = mock(World.class);
        when(world.getMaxHeight()).thenReturn(256);
        Chunk handle = mock(Chunk.class);
        when(handle.getSections()).thenReturn(new ChunkSection[16]);
        CraftChunk chunk = mock(CraftChunk.class);
        when(chunk.getHandle()).thenReturn(handle);
        List<Integer> loaded = new ArrayList<>();
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> {
            loaded.add(call.getArgument(0));
            return chunk;
        });
        try (SchematicWorkQueue edits = new SchematicWorkQueue(scheduler, Runnable::run,
                new SchematicWorkQueue.Budget(1000, 1_000_000_000, 1, 2))) {
            var first = edits.prepare(world, new SchematicWorkQueue.Bounds(0, 64, 0, 32, 1, 16), progress -> {});
            var second = edits.prepare(world, new SchematicWorkQueue.Bounds(160, 64, 0, 32, 1, 16), progress -> {});
            var cancelled = edits.prepare(world, new SchematicWorkQueue.Bounds(320, 64, 0, 16, 1, 16), progress -> {});
            cancelled.cancel().run();
            for (int tick = 0; !ticks.isEmpty() && tick < 10; tick++) {
                int previous = loaded.size();
                ticks.remove().run();
                assertTrue(loaded.size() - previous <= 1);
            }
            assertEquals(List.of(0, 10, 1, 11), loaded);
            assertEquals(SchematicWorkQueue.Outcome.COMPLETED, first.result().join());
            assertEquals(SchematicWorkQueue.Outcome.COMPLETED, second.result().join());
            assertEquals(SchematicWorkQueue.Outcome.CANCELLED, cancelled.result().join());
        }
    }
}
