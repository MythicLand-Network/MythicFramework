package cn.mythicland.framework.moderation;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

public final class NameModerationLimit<K> {
    private static final int LIMIT = 4;
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private static final int MAX_ENTRIES = 8192;

    // Ordered by last rejection. A full deque stays suspended until its newest entry expires.
    private final LinkedHashMap<K, ArrayDeque<Long>> rejections = new LinkedHashMap<>();
    private final LongSupplier clock;

    public NameModerationLimit() {
        this(System::nanoTime);
    }

    NameModerationLimit(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized boolean isSuspended(K key) {
        this.removeExpired(this.clock.getAsLong());
        var rejectionTimes = this.rejections.get(key);
        return rejectionTimes != null && rejectionTimes.size() == LIMIT;
    }

    public synchronized boolean recordRejection(K key) {
        long now = this.clock.getAsLong();
        this.removeExpired(now);
        var rejectionTimes = this.rejections.get(key);
        if (rejectionTimes == null) {
            if (this.rejections.size() == MAX_ENTRIES) this.rejections.pollFirstEntry();
            rejectionTimes = new ArrayDeque<>(LIMIT);
        } else {
            if (rejectionTimes.size() == LIMIT) return true;
            while (!rejectionTimes.isEmpty() && now - rejectionTimes.getFirst() >= WINDOW_NANOS) rejectionTimes.removeFirst();
        }
        rejectionTimes.addLast(now);
        this.rejections.putLast(key, rejectionTimes);
        return rejectionTimes.size() == LIMIT;
    }

    private void removeExpired(long now) {
        while (!this.rejections.isEmpty()) {
            if (now - this.rejections.firstEntry().getValue().getLast() < WINDOW_NANOS) return;
            this.rejections.pollFirstEntry();
        }
    }
}
