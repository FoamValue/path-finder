package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.config.PathProperties;
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

        authService = new AuthService(userRepository, passwordEncoder, rsa, ttl, redis, logService, new PathProperties());
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

    @Test
    void captcha_generatesUuidStores5MinExactTtl() {
        AuthService.CaptchaVo vo = authService.captcha();
        assertNotNull(vo.getUuid());
        assertTrue(!vo.getImage().isBlank(), "验证码图片 base64 非空");
        org.mockito.Mockito.verify(ttl)
                .setWithExplicitTtl(org.mockito.ArgumentMatchers.eq("auth:captcha:" + vo.getUuid()), anyString(),
                        org.mockito.ArgumentMatchers.eq(300L),
                        org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void captchaDisabled_loginSkipsCaptchaValidation() {
        // E2E 测试开关：captcha-enabled=false 时不校验验证码，仅校验凭证
        when(ttl.get("auth:captcha:whatever")).thenReturn(null);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        PathProperties props = new PathProperties();
        props.getSecurity().setCaptchaEnabled(false);
        AuthService bypass = new AuthService(userRepository, passwordEncoder, rsa, ttl, redis, logService, props);
        String token = bypass.login("admin", "enc", "whatever", "ignore", "127.0.0.1", "test");
        assertNotNull(token, "验证码关闭后任意验证码文本均可通过");
        org.mockito.Mockito.verify(ttl, org.mockito.Mockito.never()).delete("auth:captcha:whatever");
    }

    @Test
    void wrongCaptchaCode_rejected() {
        when(ttl.get("auth:captcha:uuid-x")).thenReturn("CODE");
        BizException e = assertThrows(BizException.class,
                () -> authService.login("admin", "enc", "uuid-x", "WRONG", "127.0.0.1", "test"));
        assertEquals(400, e.getStatus());
        assertEquals("验证码错误或已过期", e.getMessage());
    }

    @Test
    void login_unknownUser_genericMessageWithoutLeak() {
        when(userRepository.findByUsernameAndDelFlag(eq("ghost"), eq(0))).thenReturn(java.util.Optional.empty());
        BizException e = assertThrows(BizException.class,
                () -> authService.login("ghost", "enc", "u", "CODE", "127.0.0.1", "test"));
        assertEquals(400, e.getStatus());
        assertEquals("用户名或密码错误", e.getMessage(), "不得泄露用户名是否存在");
    }

    @Test
    void loginSuccess_clearsFailCounter_andAuditsSuccess() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(ttl.get("auth:fail:admin")).thenReturn(null);
        authService.login("admin", "enc", "u", "CODE", "127.0.0.1", "UA-1");
        org.mockito.Mockito.verify(ttl).delete("auth:fail:admin");
        org.mockito.Mockito.verify(logService)
                .recordLogin(eq(1L), eq("admin"), eq("127.0.0.1"), eq("UA-1"), eq(true), anyString());
    }

    @Test
    void logout_removesSessionAndUserMapping() {
        when(redis.opsForValue().get("auth:session:tok-1")).thenReturn("7");
        authService.logout("tok-1");
        org.mockito.Mockito.verify(redis).delete("auth:user:session:7");
        org.mockito.Mockito.verify(redis).delete("auth:session:tok-1");
    }

    @Test
    void logout_blankOrUnknownToken_isNoOp() {
        authService.logout("  ");
        authService.logout(null);
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.startsWith("auth:"));
    }

    @Test
    void touchSession_renewsSessionAndUserMappingTtl() {
        // G6：滑动续期需同步刷新 auth:user:session，保持踢出映射一致
        when(redis.opsForValue().get("auth:session:tok-1")).thenReturn("9");
        authService.touchSession("tok-1");
        org.mockito.Mockito.verify(redis).expire("auth:session:tok-1", java.time.Duration.ofMinutes(30));
        org.mockito.Mockito.verify(redis).expire("auth:user:session:9", java.time.Duration.ofMinutes(30));
    }

    private AuthUser currentUser(long id) {
        return new AuthUser(id, "admin", "系统管理员", "ADMIN", 1L, 0);
    }

    private void stubPasswordChange(String oldPwd, String newPwd, User user) {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.ofNullable(user));
        when(rsa.decrypt(any())).thenReturn(oldPwd.getBytes(StandardCharsets.UTF_8),
                newPwd.getBytes(StandardCharsets.UTF_8));
        when(passwordEncoder.matches(oldPwd, user.getPassword())).thenReturn(true);
        when(passwordEncoder.encode(newPwd)).thenReturn("HASH-" + newPwd);
    }

    @Test
    void changePassword_wrongOldPassword_rejected() {
        User u = new User();
        u.setId(1L);
        u.setPassword("$2a$old");
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(u));
        when(rsa.decrypt(any())).thenReturn("wrong-old".getBytes(StandardCharsets.UTF_8));
        when(passwordEncoder.matches("wrong-old", "$2a$old")).thenReturn(false);

        BizException e = assertThrows(BizException.class,
                () -> authService.changePassword("e1", "e2", currentUser(1L), "127.0.0.1", "test"));
        assertEquals(400, e.getStatus());
        assertEquals("原密码不正确", e.getMessage());
    }

    @Test
    void changePassword_shortNewPassword_rejected() {
        User u = new User();
        u.setId(1L);
        u.setPassword("$2a$old");
        stubPasswordChange("oldpass", "short", u);

        BizException e = assertThrows(BizException.class,
                () -> authService.changePassword("e1", "e2", currentUser(1L), "127.0.0.1", "test"));
        assertEquals(400, e.getStatus());
        assertEquals("新密码长度不能少于 8 位", e.getMessage());
    }

    @Test
    void changePassword_success_updatesPasswordClearsMustChangeAndAudits() {
        User u = new User();
        u.setId(1L);
        u.setUsername("admin");
        u.setPassword("$2a$old");
        u.setMustChangePassword(1);
        stubPasswordChange("oldpass", "newpass123", u);

        authService.changePassword("e1", "e2", currentUser(1L), "127.0.0.1", "test");

        assertEquals(0, u.getMustChangePassword(), "改密后解除强制改密态");
        assertEquals("HASH-newpass123", u.getPassword());
        org.mockito.Mockito.verify(userRepository).save(u);
        org.mockito.Mockito.verify(logService)
                .record(eq(currentUser(1L)), eq("PASSWORD"), eq("USER"), eq("1"), eq("admin"), anyString(), eq(true));
    }
}
