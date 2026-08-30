package com.pathfinder.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis TTL 策略：默认固定基础值 + 随机抖动（防缓存雪崩）。
 * 业务精确 TTL 通过 {@link #setWithExplicitTtl} 且 jitter=false 覆盖。
 */
@Component
public class RedisTtlPolicy {

    private static final long DEFAULT_BASE_SECONDS = 600;   // 10 分钟
    private static final double JITTER_RATIO = 0.2;          // ±20%

    private final StringRedisTemplate redis;

    public RedisTtlPolicy(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 默认策略：固定 10min + 随机 ±20%。 */
    public void setWithDefaultTtl(String key, String value) {
        setWithExplicitTtl(key, value, DEFAULT_BASE_SECONDS, true);
    }

    /**
     * 显式基础 TTL。jitter=true 叠加随机抖动；jitter=false 使用精确值（业务语义必须精确）。
     */
    public void setWithExplicitTtl(String key, String value, long baseSeconds, boolean jitter) {
        long ttl = jitter ? randomized(baseSeconds) : baseSeconds;
        redis.opsForValue().set(key, value, Duration.ofSeconds(Math.max(1, ttl)));
    }

    public long randomized(long baseSeconds) {
        double ratio = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * JITTER_RATIO;
        return Math.round(baseSeconds * ratio);
    }

    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    public void delete(String key) {
        redis.delete(key);
    }

    public long increment(String key, long baseSeconds) {
        Long v = redis.opsForValue().increment(key);
        if (v != null && v == 1L) {
            redis.expire(key, Duration.ofSeconds(baseSeconds));
        }
        return v == null ? 0 : v;
    }
}
