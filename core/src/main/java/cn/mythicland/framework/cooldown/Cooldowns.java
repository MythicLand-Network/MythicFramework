package cn.mythicland.framework.cooldown;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public final class Cooldowns<K> {
    private final Map<K, Long> cooldownUntilMillis = new HashMap<>();

    public void start(K key, long durationMillis, long nowMillis) {
        if (key == null || durationMillis <= 0L) return;
        this.cooldownUntilMillis.put(key, nowMillis + durationMillis);
    }

    public long remainingMillis(K key, long nowMillis) {
        if (key == null) return 0L;
        long cooldownUntil = this.cooldownUntilMillis.getOrDefault(key, 0L);
        if (cooldownUntil <= nowMillis) {
            this.cooldownUntilMillis.remove(key);
            return 0L;
        }
        return cooldownUntil - nowMillis;
    }

    public void reduce(K key, long reductionMillis, long nowMillis) {
        if (key == null || reductionMillis <= 0L) return;
        long cooldownUntil = this.cooldownUntilMillis.getOrDefault(key, 0L);
        if (cooldownUntil <= nowMillis) {
            this.cooldownUntilMillis.remove(key);
            return;
        }
        this.cooldownUntilMillis.put(key, Math.max(nowMillis, cooldownUntil - reductionMillis));
    }

    public void clear(Predicate<K> predicate) {
        this.cooldownUntilMillis.keySet().removeIf(predicate);
    }

    public int size() {
        return this.cooldownUntilMillis.size();
    }
}
