package cn.mythicland.framework.moderation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class NameModerationLimitTest {
    @Test
    void fourInARollingMinuteSuspendOnlyThatSourceForOneMinute() {
        var now = new AtomicLong();
        var limit = new NameModerationLimit<String>(now::get);
        assertFalse(limit.recordRejection("player"));
        now.set(TimeUnit.SECONDS.toNanos(30));
        assertFalse(limit.recordRejection("player"));
        assertFalse(limit.recordRejection("player"));
        now.set(TimeUnit.MINUTES.toNanos(1));
        assertFalse(limit.recordRejection("player"));
        assertFalse(limit.isSuspended("player"));
        assertTrue(limit.recordRejection("player"));
        assertFalse(limit.isSuspended("other"));
        now.set(TimeUnit.MINUTES.toNanos(2) - 1);
        assertTrue(limit.isSuspended("player"));
        assertTrue(limit.recordRejection("player"));
        now.incrementAndGet();
        assertFalse(limit.isSuspended("player"));
        assertFalse(limit.recordRejection("player"));
    }
}
