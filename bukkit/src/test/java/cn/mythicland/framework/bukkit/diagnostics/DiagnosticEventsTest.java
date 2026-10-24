package cn.mythicland.framework.bukkit.diagnostics;

import cn.mythicland.framework.diagnostics.DiagnosticIds;
import cn.mythicland.framework.diagnostics.DiagnosticRecorder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticEventsTest {
    @TempDir
    Path directory;

    @Test
    void unselectedListenerFailure_shouldPropagateWithoutCallingOriginalTwice() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Listener listener = new Listener() {
        };
        AtomicInteger calls = new AtomicInteger();
        RuntimeException originalFailure = new IllegalStateException("original listener failed");
        EventExecutor original = (ignored, event) -> {
            calls.incrementAndGet();
            throw originalFailure;
        };
        RegisteredListener registration = new RegisteredListener(listener, original, EventPriority.NORMAL, plugin, false);
        HandlerList handlers = new HandlerList();
        handlers.register(registration);
        DiagnosticRecorder recorder = this.recorder();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            DiagnosticEvents probe = new DiagnosticEvents(plugin, recorder, new DiagnosticIds(recorder::nextId), Set.of(UUID.randomUUID()), () -> 0, () -> 0, listenerOptions());
            try {
                assertSame(originalFailure, assertThrows(IllegalStateException.class,
                        () -> registration.callEvent(new CombatDiagnosticEvent(UUID.randomUUID()))));
                assertEquals(1, calls.get());
            } finally {
                probe.close();
            }
            var executor = RegisteredListener.class.getDeclaredField("executor");
            executor.setAccessible(true);
            assertSame(original, executor.get(registration));
        } finally {
            handlers.unregister(registration);
            recorder.stop("test");
            recorder.completion().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void selectedListener_shouldRecordCancellationAndRestoreOriginalExecutor() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("Fixture");
        Listener listener = new Listener() {
        };
        UUID selected = UUID.randomUUID();
        RegisteredListener registration = new RegisteredListener(listener, (ignored, event) -> ((Cancellable) event).setCancelled(true),
                EventPriority.HIGH, plugin, false);
        HandlerList handlers = new HandlerList();
        handlers.register(registration);
        DiagnosticRecorder recorder = this.recorder();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            DiagnosticEvents probe = new DiagnosticEvents(plugin, recorder, new DiagnosticIds(recorder::nextId), Set.of(selected), () -> 17, () -> 19, listenerOptions());
            try {
                CombatDiagnosticEvent event = new CombatDiagnosticEvent(selected);
                registration.callEvent(event);
                assertTrue(event.isCancelled());
                assertTrue(recorder.isRecording(), "Snapshotting must not fail on a simple custom combat event");
            } finally {
                probe.close();
            }
        } finally {
            handlers.unregister(registration);
            recorder.stop("test");
        }
        assertEquals("", recorder.completion().get(3, TimeUnit.SECONDS).error());
        try (var files = Files.list(recorder.directory())) {
            boolean cancelledState = false;
            for (Path file : files.filter(path -> path.toString().endsWith(".jsonl.gz"))
                    .toList()) {
                try (var reader = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
                    cancelledState |= reader.lines()
                            .map(line -> new JsonParser().parse(line))
                            .map(JsonElement::getAsJsonObject)
                            .filter(record -> record.get("stage").getAsString().equals("event.state"))
                            .anyMatch(record -> record.getAsJsonObject("fields").getAsJsonObject("state").get("cancelled").getAsBoolean());
                }
            }
            assertTrue(cancelledState, "Listener cancellation must be present in the recorded event state");
        }
    }

    private static DiagnosticOptions listenerOptions() {
        return new DiagnosticOptions(Set.of(DiagnosticOptions.PacketGroup.COMBAT),
                Set.of(DiagnosticOptions.Detail.LISTENERS), 1800, 1);
    }

    private DiagnosticRecorder recorder() throws Exception {
        return new DiagnosticRecorder(this.directory, new DiagnosticRecorder.Limits(
                Duration.ofMinutes(30),
                8192,
                1024 * 1024,
                4 * 1024 * 1024,
                0,
                128
        ), Map.of());
    }

    private static final class CombatDiagnosticEvent extends Event implements Cancellable {
        private final UUID player;
        private boolean cancelled;
        private final HandlerList handlers = new HandlerList();

        CombatDiagnosticEvent(UUID player) {
            this.player = player;
        }

        @Override
        public HandlerList getHandlers() {
            return this.handlers;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
