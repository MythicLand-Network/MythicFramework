package cn.mythicland.framework.bukkit.task;

import cn.mythicland.framework.component.ComponentResolver;
import cn.mythicland.framework.component.InjectComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ScheduledTaskLifecycleTest {
    @Test
    void schedulesEveryAnnotatedMethodAndCancelsInReverseOrder() {
        ComponentResolver resolver = mock(ComponentResolver.class);
        RecordingScheduler scheduler = new RecordingScheduler();
        Fixture fixture = new Fixture();
        when(resolver.managedTypes()).thenReturn(List.of(Fixture.class));
        when(resolver.methodAnnotationOwners(ScheduledTask.class)).thenReturn(List.of(Fixture.class));
        when(resolver.resolve(Fixture.class)).thenReturn(fixture);

        ScheduledTaskLifecycle lifecycle = new ScheduledTaskLifecycle(resolver, scheduler);
        lifecycle.load();
        assertEquals(List.of(), scheduler.scheduledNames);
        lifecycle.start();
        lifecycle.unload();

        assertEquals(List.of("fast", "slow"), scheduler.scheduledNames);
        assertEquals(List.of("slow", "fast"), scheduler.cancelledNames);
    }

    @Test
    void rejectsInvalidTaskPeriodBeforeScheduling() {
        ComponentResolver resolver = mock(ComponentResolver.class);
        RecordingScheduler scheduler = new RecordingScheduler();
        when(resolver.managedTypes()).thenReturn(List.of(InvalidFixture.class));
        when(resolver.methodAnnotationOwners(ScheduledTask.class)).thenReturn(List.of(InvalidFixture.class));

        ScheduledTaskLifecycle lifecycle = new ScheduledTaskLifecycle(resolver, scheduler);

        assertThrows(IllegalStateException.class, lifecycle::load);
        assertEquals(List.of(), scheduler.scheduledNames);
    }

    @InjectComponent
    private static final class Fixture {
        @ScheduledTask(delayTicks = 0L, periodTicks = 1L)
        private void fast() {
        }

        @ScheduledTask(delayTicks = 2L, periodTicks = 5L)
        private void slow() {
        }
    }

    @InjectComponent
    private static final class InvalidFixture {
        @ScheduledTask(delayTicks = 0L, periodTicks = 0L)
        private void invalid() {
        }
    }

    private static final class RecordingScheduler implements TaskScheduler {
        private final List<String> scheduledNames = new ArrayList<>();
        private final List<String> cancelledNames = new ArrayList<>();

        @Override
        public void executeSync(Runnable task) {
        }

        @Override
        public void executeSyncIfNeeded(Runnable task) {
        }

        @Override
        public ScheduledTaskHandle scheduleSyncRepeating(Runnable task, long delayTicks, long periodTicks) {
            String name = delayTicks == 0L ? "fast" : "slow";
            this.scheduledNames.add(name);
            return () -> this.cancelledNames.add(name);
        }

        @Override
        public void executeSyncLater(Runnable task, long delayTicks) {
        }

        @Override
        public void executeAsync(Runnable task) {
        }
    }
}
