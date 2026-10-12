package cn.mythicland.framework.bukkit;

import cn.mythicland.framework.bukkit.task.TaskScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatInputSessionsTest {
    @Test
    void replacementDiscardsQueuedCallbacksAndRetryRetainsCurrentInput() {
        List<Runnable> callbacks = new ArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(call -> callbacks.add(call.getArgument(0))).when(scheduler).executeSync(any());
        ChatInputSessions sessions = new ChatInputSessions(scheduler);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        Object owner = new Object();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger cleaned = new AtomicInteger();
        sessions.begin(player, owner, (sender, text, cancel) -> {
            accepted.incrementAndGet();
            return ChatInputSessions.Result.FINISH;
        }, () -> {
            cleaned.incrementAndGet();
            sessions.clear(player.getUniqueId(), owner);
        });
        sessions.onChat(chat(player, "old input"));
        sessions.begin(player, owner, (sender, text, cancel) -> cancel
                ? ChatInputSessions.Result.FINISH : ChatInputSessions.Result.RETRY, cleaned::incrementAndGet);
        callbacks.removeFirst().run();
        assertEquals(0, accepted.get());
        assertEquals(1, cleaned.get());
        sessions.onChat(chat(player, "invalid"));
        callbacks.removeFirst().run();
        assertTrue(sessions.contains(player.getUniqueId(), owner));
        sessions.onChat(chat(player, "  CaNcEl  "));
        callbacks.removeFirst().run();
        assertFalse(sessions.contains(player.getUniqueId(), owner));
        assertEquals(2, cleaned.get());
    }

    @Test
    void cancellationOnlyInputLeavesNormalChatUntouched() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ChatInputSessions sessions = new ChatInputSessions(scheduler);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        sessions.begin(player, this, text -> false, (sender, text, cancel) -> ChatInputSessions.Result.FINISH, () -> {});
        AsyncPlayerChatEvent normal = chat(player, "hello");
        sessions.onChat(normal);
        verify(normal, never()).setCancelled(true);
        AsyncPlayerChatEvent cancel = chat(player, "cancel");
        sessions.onChat(cancel);
        verify(cancel).setCancelled(true);
        verify(scheduler).executeSync(any());
    }

    private AsyncPlayerChatEvent chat(Player player, String text) {
        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(text);
        return event;
    }
}
