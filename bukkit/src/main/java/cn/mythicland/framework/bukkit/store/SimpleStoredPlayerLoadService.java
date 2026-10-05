package cn.mythicland.framework.bukkit.store;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SimpleStoredPlayerLoadService<P, I> implements StoredPlayerLoadService<P, I> {
    private final Logger logger;
    private final Executor ioExecutor;
    private final StoredPlayerRepository<P, I> repository;
    private final StoredPlayerInventory<I> inventoryAdapter;
    private final StoredPlayerProfile<P> profileAdapter;
    private final Consumer<Player> loadFailureHandler;
    private final Consumer<Runnable> mainThreadExecutor;

    public SimpleStoredPlayerLoadService(
            Logger logger,
            Executor ioExecutor,
            StoredPlayerRepository<P, I> repository,
            StoredPlayerInventory<I> inventoryAdapter,
            StoredPlayerProfile<P> profileAdapter,
            Consumer<Player> loadFailureHandler,
            Consumer<Runnable> mainThreadExecutor
    ) {
        this.logger = logger;
        this.ioExecutor = ioExecutor;
        this.repository = repository;
        this.inventoryAdapter = inventoryAdapter;
        this.profileAdapter = profileAdapter;
        this.loadFailureHandler = loadFailureHandler;
        this.mainThreadExecutor = mainThreadExecutor;
    }

    @Override
    public CompletableFuture<StoredPlayerData<P, I>> load(Player player, StoredPlayerCache<P, I> cache) {
        UUID playerUniqueId = player.getUniqueId();
        StoredPlayerSession<P, I> existing = cache.findSession(playerUniqueId);
        if (existing != null) return CompletableFuture.completedFuture(existing.snapshot());

        return cache.getOrStartLoading(
                playerUniqueId,
                player,
                () -> this.loadAsync(playerUniqueId, player, cache)
        );
    }

    private CompletableFuture<StoredPlayerData<P, I>> loadAsync(
            UUID playerUniqueId,
            Player player,
            StoredPlayerCache<P, I> cache
    ) {
        return CompletableFuture.supplyAsync(
                        () -> this.loadSnapshot(playerUniqueId),
                        this.ioExecutor
                )
                .handle((loadedData, throwable) -> {
                    if (throwable != null || loadedData == null) {
                        Throwable cause = throwable == null
                                ? new IllegalStateException("Loaded player state is null for " + playerUniqueId)
                                : throwable;
                        this.logger.log(
                                Level.SEVERE,
                                "Failed to load player data for " + player.getName(),
                                cause
                        );
                        return this.disconnectOnMain(playerUniqueId, player, cache, cause);
                    }
                    return this.applyLoadedDataOnMain(playerUniqueId, player, loadedData, cache);
                })
                .thenCompose(completion -> completion);
    }

    private StoredPlayerData<P, I> loadSnapshot(UUID playerUniqueId) {
        try {
            return this.repository.load(playerUniqueId);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load player data for " + playerUniqueId, exception);
        }
    }

    private CompletableFuture<StoredPlayerData<P, I>> applyLoadedDataOnMain(
            UUID playerUniqueId,
            Player fallbackPlayer,
            StoredPlayerData<P, I> loadedData,
            StoredPlayerCache<P, I> cache
    ) {
        CompletableFuture<StoredPlayerData<P, I>> completion = new CompletableFuture<>();
        try {
            this.mainThreadExecutor.accept(() -> {
                if (!cache.isCurrentLoading(playerUniqueId, fallbackPlayer)) {
                    completion.complete(loadedData);
                    return;
                }
                try {
                    Player player = this.resolveOnlinePlayer(playerUniqueId, fallbackPlayer);
                    if (player == null || !player.isOnline()) {
                        completion.complete(loadedData);
                        return;
                    }
                    cache.putSession(playerUniqueId, new StoredPlayerSession<>(loadedData, this.profileAdapter));
                    this.inventoryAdapter.apply(player, loadedData.inventoryData());
                    completion.complete(loadedData);
                } catch (Throwable throwable) {
                    completion.completeExceptionally(throwable);
                } finally {
                    cache.finishLoading(playerUniqueId, fallbackPlayer);
                }
            });
        } catch (Throwable throwable) {
            cache.finishLoading(playerUniqueId, fallbackPlayer);
            completion.completeExceptionally(throwable);
        }
        return completion;
    }

    private CompletableFuture<StoredPlayerData<P, I>> disconnectOnMain(
            UUID playerUniqueId,
            Player fallbackPlayer,
            StoredPlayerCache<P, I> cache,
            Throwable cause
    ) {
        CompletableFuture<StoredPlayerData<P, I>> completion = new CompletableFuture<>();
        try {
            this.mainThreadExecutor.accept(() -> {
                if (!cache.isCurrentLoading(playerUniqueId, fallbackPlayer)) {
                    completion.completeExceptionally(cause);
                    return;
                }
                try {
                    Player player = this.resolveOnlinePlayer(playerUniqueId, fallbackPlayer);
                    if (player != null && player.isOnline()) {
                        this.loadFailureHandler.accept(player);
                    }
                    completion.completeExceptionally(cause);
                } catch (Throwable throwable) {
                    cause.addSuppressed(throwable);
                    completion.completeExceptionally(cause);
                } finally {
                    cache.finishLoading(playerUniqueId, fallbackPlayer);
                }
            });
        } catch (Throwable throwable) {
            cache.finishLoading(playerUniqueId, fallbackPlayer);
            completion.completeExceptionally(throwable);
        }
        return completion;
    }

    private Player resolveOnlinePlayer(UUID playerUniqueId, Player fallbackPlayer) {
        Player player = Bukkit.getPlayer(playerUniqueId);
        if (player != null) return player == fallbackPlayer ? player : null;
        if (fallbackPlayer != null && fallbackPlayer.isOnline()
                && playerUniqueId.equals(fallbackPlayer.getUniqueId())) {
            return fallbackPlayer;
        }
        return null;
    }
}
