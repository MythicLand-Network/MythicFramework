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

    @Test
    void tabCompletionBelongsToCurrentSessionAndStopsAfterCancellationReplacementAndClose() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(scheduler).executeSync(any());
        ChatInputSessions sessions = new ChatInputSessions(scheduler);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        var event = mock(org.bukkit.event.player.PlayerChatTabCompleteEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getChatMessage()).thenReturn("附魔");
        List<String> offers = new ArrayList<>(List.of("Player"));
        when(event.getTabCompletions()).thenReturn(offers);
        Object owner = new Object();
        sessions.begin(player, owner, text -> true, (sender, text, cancel) -> ChatInputSessions.Result.FINISH,
                () -> {}, text -> List.of(text + "一", text + "二"));
        sessions.onTabComplete(event);
        assertEquals(List.of("附魔一", "附魔二"), offers);
        sessions.onChat(chat(player, "cancel"));
        offers.clear();
        offers.add("Player");
        sessions.onTabComplete(event);
        assertEquals(List.of("Player"), offers);
        sessions.begin(player, owner, text -> true, (sender, text, cancel) -> ChatInputSessions.Result.RETRY,
                () -> {}, text -> List.of("old"));
        sessions.begin(player, new Object(), (sender, text, cancel) -> ChatInputSessions.Result.RETRY, () -> {});
        sessions.onTabComplete(event);
        assertEquals(List.of("Player"), offers);
        sessions.close();
        sessions.onTabComplete(event);
        assertEquals(List.of("Player"), offers);
    }


    private AsyncPlayerChatEvent chat(Player player, String text) {
        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(text);
        return event;
    }
}
