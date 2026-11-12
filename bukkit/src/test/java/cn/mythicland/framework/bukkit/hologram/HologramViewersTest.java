package cn.mythicland.framework.bukkit.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HologramViewersTest {
    @Test
    void sharedDisplaysTrackVisibilityAndUpdateOnlyCurrentViewers() {
        HologramDisplay display = mock(HologramDisplay.class);
        Player first = this.player(UUID.randomUUID());
        Player second = this.player(UUID.randomUUID());
        HologramViewers<HologramDisplay> viewers = HologramViewers.shared(display);
        viewers.refresh(List.of(first, second), player -> true, 1);
        viewers.refresh(List.of(first, second), player -> true, 1);
        verify(display, times(1)).show(first);
        verify(display, times(1)).show(second);
        viewers.hide(first);
        viewers.updateShared(List.of("Changed"));
        verify(display).update(List.of("Changed"), List.of(second));
        viewers.forget(second, false);
        viewers.close();
        verify(display, never()).hide(second);
        assertNull(viewers.visibleDisplay(second));
    }

    @Test
    void personalDisplaysKeepCreationBudgetAndRefreshTextBeforeReappearing() {
        List<HologramDisplay> created = new ArrayList<>();
        AtomicReference<String> text = new AtomicReference<>("Initial");
        HologramViewers<HologramDisplay> viewers = HologramViewers.personalized(
                player -> List.of(text.get()),
                (player, lines) -> {
                    HologramDisplay display = mock(HologramDisplay.class);
                    created.add(display);
                    return display;
                }
        );
        Player first = this.player(UUID.randomUUID());
        Player second = this.player(UUID.randomUUID());
        assertFalse(viewers.refresh(List.of(first, second), player -> true, 1));
        assertEquals(1, created.size());
        assertTrue(viewers.refresh(List.of(first, second), player -> true, 1));
        assertEquals(2, created.size());
        viewers.hide(first);
        text.set("Changed");
        assertTrue(viewers.refresh(List.of(first, second), player -> true, 1));
        assertEquals(2, created.size());
        var order = inOrder(created.getFirst());
        order.verify(created.getFirst()).show(first);
        order.verify(created.getFirst()).hide(first);
        order.verify(created.getFirst()).update(List.of("Changed"), List.of());
        order.verify(created.getFirst()).show(first);
        viewers.refreshText();
        verify(created.get(1)).update(List.of("Changed"), List.of(second));
        viewers.close();
        verify(created.get(1)).hide(second);
    }

    @Test
    void ignoresStalePlayerEventsAfterReconnectAndClosesIdempotently() {
        HologramDisplay display = mock(HologramDisplay.class);
        HologramViewers<HologramDisplay> viewers = HologramViewers.shared(display);
        UUID id = UUID.randomUUID();
        Player previous = this.player(id);
        Player current = this.player(id);
        viewers.refresh(List.of(previous), player -> true, 1);
        viewers.refresh(List.of(current), player -> true, 1);
        viewers.forget(previous, true);
        assertSame(display, viewers.visibleDisplay(current));
        assertNull(viewers.visibleDisplay(previous));
        viewers.close();
        viewers.close();
        verify(display, times(1)).hide(current);
        assertThrows(IllegalStateException.class, () -> viewers.refresh(List.of(current), player -> true, 1));
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        return player;
    }
}
