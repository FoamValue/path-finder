package cn.chenxinjie.pathfinder.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis TTL 策略：默认 10min ± 20% 抖动；显式精确 TTL 不被抖动。
 */
class RedisTtlPolicyTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisTtlPolicy policy = new RedisTtlPolicy(redis);

    RedisTtlPolicyTest() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForValue().increment(anyString())).thenReturn(1L);
        doAnswer(inv -> null).when(redis).expire(anyString(), any(Duration.class));
    }

    @Test
    void defaultTtl_isWithinJitterRange() {
        java.util.List<Long> ttls = new java.util.ArrayList<>();
        doAnswer(inv -> {
            ttls.add(inv.getArgument(2, Duration.class).toSeconds());
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        for (int i = 0; i < 100; i++) {
            policy.setWithDefaultTtl("k" + i, "v");
        }
        for (long t : ttls) {
            assertTrue(t >= 480 && t <= 720, "TTL out of [8min,12min]: " + t);
        }
    }

    @Test
    void explicitTtl_exactWhenNoJitter() {
        policy.setWithExplicitTtl("captcha", "code", 300, false);
        verify(valueOps).set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(300)));
    }

    @Test
    void increment_firstCallSetsExpire() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        long c = policy.increment("auth:fail:x", 600);
        assertEquals(1L, c);
        verify(redis).expire("auth:fail:x", Duration.ofSeconds(600));
    }
}
