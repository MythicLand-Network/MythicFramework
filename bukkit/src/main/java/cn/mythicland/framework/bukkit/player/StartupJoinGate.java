package cn.mythicland.framework.bukkit.player;

import cn.mythicland.framework.validation.TextValidation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rejects login until asynchronous startup succeeds, without admitting partially initialized players.
 */
public final class StartupJoinGate implements Listener, AutoCloseable {
    private final String waitingMessage;
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);

    /**
     * Registers the gate on the main thread. Readiness may complete on any thread; failure or
     * cancellation keeps the gate closed. The owner must report startup failures and close this
     * gate during shutdown. Completing readiness never overrides a previously closed gate.
     *
     * @param owner          plugin owning the login listeners
     * @param waitingMessage message returned to clients while startup is incomplete
     * @param readiness      completion of all startup work required before players may join
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if the waiting message is blank
     */
    public StartupJoinGate(JavaPlugin owner, String waitingMessage, CompletionStage<?> readiness) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(readiness, "readiness");
        this.waitingMessage = TextValidation.requireText(waitingMessage, "waitingMessage");
        owner.getServer().getPluginManager().registerEvents(this, owner);
        readiness.whenComplete((ignored, failure) -> {
            if (failure == null) state.compareAndSet(State.WAITING, State.READY);
        });
    }

    /**
     * @return whether startup succeeded and the gate has not been closed; safe on any thread
     */
    public boolean isAcceptingJoins() {
        return state.get() == State.READY;
    }

    /**
     * Rejects connections on the authentication thread while startup is incomplete.
     *
     * @param event asynchronous pre-login event
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!isAcceptingJoins()) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, waitingMessage);
    }

    /**
     * Rechecks readiness on the main thread without overriding another listener's rejection.
     *
     * @param event synchronous login event
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!isAcceptingJoins()) event.disallow(PlayerLoginEvent.Result.KICK_OTHER, waitingMessage);
    }

    /**
     * Unregisters the listeners on the main thread; repeated calls have no effect.
     */
    @Override
    public void close() {
        if (state.getAndSet(State.CLOSED) == State.CLOSED) return;
        HandlerList.unregisterAll(this);
    }

    private enum State {
        WAITING, READY, CLOSED
    }
}
