package cn.mythicland.framework.bukkit.store;

import cn.mythicland.framework.bukkit.task.TaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SimpleStoredPlayerSaveServiceTest {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService caller = Executors.newSingleThreadExecutor();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final StoredPlayerCache<Balance, String> cache = new StoredPlayerCache<>();
    private final List<StoredPlayerFlush<Balance, String>> written = new CopyOnWriteArrayList<>();
    private final StoredPlayerRepository<Balance, String> repository = mock(StoredPlayerRepository.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final SimpleStoredPlayerSaveService<Balance, String> saves = new SimpleStoredPlayerSaveService<>(
            Logger.getAnonymousLogger(), this.queued::add, this.repository,
            mock(StoredPlayerInventory.class), mock(TaskScheduler.class), 20L, ignored -> {
    });

    @AfterEach
    void stopWorkers() throws InterruptedException {
        this.release.countDown();
        this.worker.shutdown();
        this.caller.shutdown();
        assertTrue(this.worker.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(this.caller.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void releaseCallbackRunsAfterFinalInventoryCaptureAndBeforeFlushPreparation() throws Exception {
        var session = this.session(10L);
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(session.uniqueId());
        StoredPlayerInventory<String> inventory = mock(StoredPlayerInventory.class);
        when(inventory.capture(player)).thenReturn("final");
        when(inventory.mergeCaptured(any(), any())).thenAnswer(call -> call.getArgument(0));
        var service = new SimpleStoredPlayerSaveService<>(Logger.getAnonymousLogger(), this.queued::add,
                this.repository, inventory, mock(TaskScheduler.class), 20L, ignored -> {});
        service.saveAndRemove(player, this.cache, closing -> {
            assertSame(session, closing);
            assertEquals("final", closing.snapshot().inventoryData());
            closing.updateProfile(old -> new Balance(30L, old.version()));
            assertEquals(2L, closing.profileRevision());
        });
        doAnswer(call -> { this.written.addAll(call.getArgument(0)); return null; }).when(this.repository).flush(any());
        this.queued.removeFirst().run();
        assertEquals(30L, this.written.getFirst().profileData().coins());
        assertEquals("final", this.written.getFirst().inventoryData());
        assertEquals(2L, this.written.getFirst().profileRevision());
    }

    @Test
    void immediateFlushDoesNotWaitForAnotherPlayerInTheBackgroundBatch() throws Exception {
        StoredPlayerSession<Balance, String> first = this.session(10L);
        StoredPlayerSession<Balance, String> second = this.session(20L);
        this.blockFirstFlush();
        this.saves.flushAsync(first.uniqueId(), this.cache);
        this.saves.flushAsync(second.uniqueId(), this.cache);
        Future<?> background = this.worker.submit(this.queued.removeFirst());
        assertTrue(this.entered.await(5, TimeUnit.SECONDS));

        Future<Boolean> immediate = this.caller.submit(() -> this.saves.flushNow(second.uniqueId(), this.cache));
        assertTrue(immediate.get(2, TimeUnit.SECONDS));
        assertEquals(1L, this.release.getCount());
        assertFalse(second.hasPendingChanges());
        this.release.countDown();
        background.get(5, TimeUnit.SECONDS);
        assertEquals(2, this.written.size());
        assertFalse(first.hasPendingChanges());
    }

    @Test
    void samePlayerWaitsForThePreviousCommitAndSavesTheNewRevision() throws Exception {
        StoredPlayerSession<Balance, String> session = this.session(10L);
        this.blockFirstFlush();
        this.saves.flushAsync(session.uniqueId(), this.cache);
        Future<?> background = this.worker.submit(this.queued.removeFirst());
        assertTrue(this.entered.await(5, TimeUnit.SECONDS));
        session.updateProfile(old -> new Balance(30L, old.version()));
        session.captureInventory("latest");
        CountDownLatch calling = new CountDownLatch(1);
        Future<Boolean> immediate = this.caller.submit(() -> {
            calling.countDown();
            return this.saves.flushNow(session.uniqueId(), this.cache);
        });
        assertTrue(calling.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> immediate.get(100, TimeUnit.MILLISECONDS));

        this.release.countDown();
        assertTrue(immediate.get(5, TimeUnit.SECONDS));
        background.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(10L, 30L), this.written.stream().map(r -> r.profileData().coins()).toList());
        assertEquals(List.of(1L, 2L), this.written.stream().map(StoredPlayerFlush::profileVersion).toList());
        assertEquals("latest", this.written.getLast().inventoryData());
        assertEquals(3L, session.profileData().version());
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void failedCommitKeepsTheRevisionAvailableForRetry() throws Exception {
        StoredPlayerSession<Balance, String> session = this.session(10L);
        doThrow(new IllegalStateException("database failure")).doAnswer(invocation -> {
            this.written.addAll(invocation.getArgument(0));
            return null;
        }).when(this.repository).flush(any());

        assertThrows(IllegalStateException.class, () -> this.saves.flushNow(session.uniqueId(), this.cache));
        assertTrue(session.hasPendingChanges());
        assertEquals(1L, session.profileData().version());
        assertTrue(this.saves.flushNow(session.uniqueId(), this.cache));
        assertEquals(1L, this.written.getFirst().profileVersion());
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void shutdownWaitsForTheWorkerAndPersistsChangesMadeDuringItsCommit() throws Exception {
        StoredPlayerSession<Balance, String> session = this.session(10L);
        this.blockFirstFlush();
        this.saves.flushAsync(session.uniqueId(), this.cache);
        Future<?> background = this.worker.submit(this.queued.removeFirst());
        assertTrue(this.entered.await(5, TimeUnit.SECONDS));
        session.updateProfile(old -> new Balance(40L, old.version()));
        session.captureInventory("final");
        CountDownLatch calling = new CountDownLatch(1);
        Future<?> shutdown = this.caller.submit(() -> {
            calling.countDown();
            this.saves.shutdownAndFlush(this.cache, false);
        });
        assertTrue(calling.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));

        this.release.countDown();
        shutdown.get(5, TimeUnit.SECONDS);
        background.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(10L, 40L), this.written.stream().map(r -> r.profileData().coins()).toList());
        assertEquals("final", this.written.getLast().inventoryData());
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void retainedProfileUpdateSurvivesAnInFlightLogoutFlushAndReactivation() throws Exception {
        StoredPlayerSession<Balance, String> session = this.session(10L);
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(session.uniqueId());
        this.blockFirstFlush();
        this.saves.saveAndRemove(player, this.cache);
        Future<?> background = this.worker.submit(this.queued.removeFirst());
        assertTrue(this.entered.await(5, TimeUnit.SECONDS));
        assertTrue(this.saves.updateRetainedProfile(session.uniqueId(), this.cache, old -> new Balance(50L, old.version())));
        this.release.countDown();
        background.get(5, TimeUnit.SECONDS);
        while (!this.queued.isEmpty()) this.queued.removeFirst().run();
        assertEquals(List.of(10L, 50L), this.written.stream().map(row -> row.profileData().coins()).toList());
        assertFalse(session.hasPendingChanges());
        assertFalse(this.saves.updateRetainedProfile(UUID.randomUUID(), this.cache, old -> old));
    }

    private void blockFirstFlush() throws Exception {
        doAnswer(invocation -> {
            this.written.addAll(invocation.getArgument(0));
            if (this.entered.getCount() > 0) {
                this.entered.countDown();
                assertTrue(this.release.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(this.repository).flush(any());
    }

    private StoredPlayerSession<Balance, String> session(long coins) {
        UUID uniqueId = UUID.randomUUID();
        StoredPlayerSession<Balance, String> session = new StoredPlayerSession<>(
                new StoredPlayerData<>(uniqueId, new Balance(coins, 1L), "initial", 1L),
                new StoredPlayerProfile<>() {
                    @Override
                    public long version(Balance profile) {
                        return profile.version();
                    }

                    @Override
                    public Balance nextVersion(Balance profile) {
                        return new Balance(profile.coins(), profile.version() + 1L);
                    }
                }
        );
        session.updateProfile(old -> old);
        this.cache.putSession(uniqueId, session);
        return session;
    }

    private record Balance(long coins, long version) {
    }
}
