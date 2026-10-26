package cn.mythicland.framework.diagnostics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticHistoryTest {
    @Test
    void windowAndCapacity_shouldEvictOldPacketsButKeepSessionMetadata() {
        DiagnosticHistory history = new DiagnosticHistory(new DiagnosticRecorder.History(Duration.ofSeconds(2), 65536));
        byte[] metadata = new byte[1024];
        byte[] old = new byte[32768];
        byte[] recent = new byte[32768];
        history.add(record(1, 0, "session.start"), metadata);
        history.add(record(2, 1, "packet"), old);
        history.add(record(3, 2, "packet"), recent);
        assertEquals(1L, history.summary().get("capacityEvictedRecords"));
        assertTrue(history.bytes() <= 65536);
        history.trim(Duration.ofSeconds(3).toNanos());
        assertEquals(1L, history.summary().get("expiredRecords"));
        assertEquals(1, history.size());
        var saved = history.drain();
        assertSame(metadata, saved.removeFirst());
        assertTrue(saved.isEmpty());
        assertEquals(0, history.bytes());
    }

    private Map<String, Object> record(long id, long nanos, String stage) {
        return Map.of("id", id, "nanoTime", nanos, "stage", stage, "fields", Map.of());
    }
}
