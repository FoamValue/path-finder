package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.util.RedisTtlPolicy;
import cn.chenxinjie.pathfinder.util.RsaKeyHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 认证服务：验证码、凭证校验、失败锁定、多登录踢出。
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RsaKeyHolder rsa;
    private RedisTtlPolicy ttl;
    private StringRedisTemplate redis;
    private LogService logService;
    private AuthService authService;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        rsa = mock(RsaKeyHolder.class);
        ttl = mock(RedisTtlPolicy.class);
        redis = mock(StringRedisTemplate.class);
        logService = mock(LogService.class);

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPassword("$2a$encoded");
        admin.setStatus(1);
        admin.setMustChangePassword(1);
        when(userRepository.findByUsernameAndDelFlag(eq("admin"), eq(0))).thenReturn(Optional.of(admin));
        when(rsa.decrypt(any())).thenReturn("Init@123".getBytes(StandardCharsets.UTF_8));
        when(ttl.get(anyString())).thenReturn("CODE");

        authService = new AuthService(userRepository, passwordEncoder, rsa, ttl, redis, logService);
    }

    @Test
    void captchaError_rejected() {
        when(ttl.get("auth:captcha:uuid-x")).thenReturn(null);
        BizException e = assertThrows(BizException.class,
                () -> authService.login("admin", "enc", "uuid-x", "XXXX", "127.0.0.1", "test"));
        assertEquals(400, e.getStatus());
    }

    @Test
    void wrongPassword_incrementsFailAndLocksAfterFive() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        // 计数从 0 到 5：前 4 次不锁定
        long[] counter = {0};
        when(ttl.increment(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(
                inv -> ++counter[0]);
        for (int i = 1; i <= 4; i++) {
            assertThrows(BizException.class,
                    () -> authService.login("admin", "enc", "u", "CODE", "127.0.0.1", "test"));
        }
        when(counter[0] >= 5).thenAnswer(inv -> counter[0] >= 5);
        BizException lock = assertThrows(BizException.class,
                () -> authService.login("admin", "enc", "u", "CODE", "127.0.0.1", "test"));
        assertTrue(lock.getMessage().contains("锁定"));
    }

    @Test
    void lockedAccount_rejectedEvenWithCorrectPassword() {
        when(redis.hasKey("auth:lock:admin")).thenReturn(true);
        BizException e = assertThrows(BizException.class,
                () -> authService.login("admin", "enc", "u", "CODE", "127.0.0.1", "test"));
        assertEquals(403, e.getStatus());
    }

    @Test
    void correctLogin_returnsTokenAndSetsSessions() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(ttl.get("auth:user:session:1")).thenReturn("oldToken");
        String token = authService.login("admin", "enc", "u", "CODE", "127.0.0.1", "test");
        assertNotNull(token);
        // 多登录踢出：删除旧会话
        org.mockito.Mockito.verify(redis).delete("auth:session:oldToken");
    }
}
