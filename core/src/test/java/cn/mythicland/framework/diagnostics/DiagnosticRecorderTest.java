package cn.mythicland.framework.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import java.io.IOException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticRecorderTest {
    @TempDir
    Path directory;

    private DiagnosticRecorder.Limits limits(Duration duration, long sessionBytes) {
        return new DiagnosticRecorder.Limits(duration, 8192, sessionBytes, 4 * 1024 * 1024, 0, 4096);
    }

    @Test
    void stop_shouldDrainQueueAndProduceReadableSegmentsAndFooter() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder(
                this.directory,
                this.limits(Duration.ofMinutes(30), 1024 * 1024),
                Map.of()
        );
        for (int i = 0; i < 40; i++) recorder.record("sample", Map.of("index", i, "label", "记录".repeat(80)));
        recorder.stop("command");
        DiagnosticRecorder.Summary summary = recorder.completion().get(5, TimeUnit.SECONDS);
        assertEquals("command", summary.reason());
        assertEquals(41, summary.records());
        assertEquals(0, summary.dropped());
        List<String> lines = this.readSegments(recorder.directory());
        assertEquals(42, lines.size());
        assertEquals("session.end", JsonParser.parseString(lines.getLast()).getAsJsonObject().get("stage").getAsString());
        assertTrue(Files.exists(recorder.directory().resolve("session.json")));
        assertFalse(recorder.record("late", Map.of()));
    }

    @Test
    void duration_shouldStopWithoutAnySchedulerTicks() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder(
                this.directory,
                this.limits(Duration.ofMillis(80), 1024 * 1024),
                Map.of()
        );
        DiagnosticRecorder.Summary summary = recorder.completion().get(3, TimeUnit.SECONDS);
        assertEquals("duration_limit", summary.reason());
        assertFalse(recorder.isRecording());
        assertThrows(IllegalArgumentException.class, () -> this.limits(Duration.ofMinutes(31), 1024 * 1024));
    }

    @Test
    void size_shouldStopWithExplicitLossCountsAndPreservePriorFiles() throws Exception {
        Path old = this.directory.resolve("previous.jsonl.gz");
        Files.writeString(old, "previous diagnostic");
        long budget = 128 * 1024;
        DiagnosticRecorder recorder = new DiagnosticRecorder(this.directory, this.limits(Duration.ofMinutes(30), budget), Map.of());
        for (int i = 0; i < 1000 && recorder.isRecording(); i++)
            recorder.record("large", Map.of("payload", "x".repeat(4096)));
        DiagnosticRecorder.Summary summary = recorder.completion().get(5, TimeUnit.SECONDS);
        assertEquals("session_size_limit", summary.reason());
        assertTrue(summary.uncompressedBytes() <= budget);
        assertTrue(summary.dropped() > 0);
        assertEquals("previous diagnostic", Files.readString(old));
        assertEquals("session_size_limit", JsonParser.parseString(this.readSegments(recorder.directory()).getLast())
                .getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void directoryQuota_shouldRejectBeforeStartingAnotherCapture() throws Exception {
        Files.write(this.directory.resolve("existing"), new byte[128 * 1024]);
        var limits = new DiagnosticRecorder.Limits(Duration.ofMinutes(30), 8192, 128 * 1024, 160 * 1024, 0, 8);
        assertThrows(IOException.class, () -> new DiagnosticRecorder(this.directory, limits, Map.of()));
    }

    @Test
    void identities_shouldDistinguishEqualObjectsAndReuseSameReference() {
        AtomicLong sequence = new AtomicLong();
        DiagnosticIds ids = new DiagnosticIds(sequence::incrementAndGet);
        String first = new String("same");
        String second = new String("same");
        assertNotEquals(ids.id(first), ids.id(second));
        assertEquals(ids.id(first), ids.id(first));
    }

    @Test
    void oversizedSnapshot_shouldKeepIdentityAndContinueWithExplicitTruncation() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder(
                this.directory,
                this.limits(Duration.ofMinutes(30), 1024 * 1024),
                Map.of()
        );
        recorder.record("large", Map.of("packetId", 42L, "payload", "\u0001".repeat(100000)));
        recorder.record("next", Map.of());
        recorder.stop("command");
        var summary = recorder.completion().get(5, TimeUnit.SECONDS);
        assertEquals("command", summary.reason());
        assertEquals(3, summary.records());
        var fields = JsonParser.parseString(this.readSegments(recorder.directory()).get(1)).getAsJsonObject().getAsJsonObject("fields");
        assertEquals(42L, fields.get("packetId").getAsLong());
        assertEquals("record_size_limit", fields.get("omitted").getAsString());
    }

    @Test
    void history_shouldWaitForTriggerThenSaveBeforeAndAfterWithOriginalIds() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder(this.directory,
                this.limits(Duration.ofMillis(300), 1024 * 1024), Map.of("target", "Baozha"),
                new DiagnosticRecorder.History(Duration.ofSeconds(5), 128 * 1024));
        recorder.record("before", Map.of("slot", 4));
        Thread.sleep(400);
        assertTrue(recorder.isBuffering(), "Post-trigger duration must not limit the watch period");
        assertTrue(this.readSegments(recorder.directory()).isEmpty(), "Watch should not continuously write packet files");
        recorder.trigger();
        recorder.record("marker", Map.of("label", "helmet_missing"));
        recorder.record("after", Map.of("slot", 4));
        recorder.trigger();
        var summary = recorder.completion().get(3, TimeUnit.SECONDS);
        assertEquals("duration_limit", summary.reason());
        assertEquals(0, summary.dropped());
        var lines = this.readSegments(recorder.directory());
        assertEquals(List.of("session.start", "before", "history.trigger", "marker", "after", "session.end"),
                lines.stream().map(line -> JsonParser.parseString(line).getAsJsonObject().get("stage").getAsString()).toList());
        assertEquals(2, JsonParser.parseString(lines.get(1)).getAsJsonObject().get("id").getAsLong());
        assertTrue(JsonParser.parseString(Files.readString(recorder.directory().resolve("session.json")))
                .getAsJsonObject().has("history"));
    }

    @Test
    void historyStop_shouldSaveCurrentWindowWithoutRequiringAMarker() throws Exception {
        DiagnosticRecorder recorder = new DiagnosticRecorder(this.directory,
                this.limits(Duration.ofSeconds(1), 1024 * 1024), Map.of(),
                new DiagnosticRecorder.History(Duration.ofSeconds(5), 128 * 1024));
        recorder.record("equipment", Map.of("slot", 4));
        recorder.stop("command");
        assertEquals(2, recorder.completion().get(3, TimeUnit.SECONDS).records());
        assertEquals(3, this.readSegments(recorder.directory()).size());
    }

    private List<String> readSegments(Path folder) throws Exception {
        List<Path> files;
        try (var stream = Files.list(folder)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".jsonl.gz"))
                    .sorted()
                    .toList();
        }
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
                lines.addAll(reader.lines()
                        .toList());
            }
        }
        return lines;
    }
}
