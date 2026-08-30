package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.util.RedisTtlPolicy;
import cn.chenxinjie.pathfinder.util.RsaKeyHolder;
import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 认证服务：验证码、RSA 解密、BCrypt、失败锁定、多登录踢出、会话（PRD F1 / TSDD 5.2）。
 */
@Service
public class AuthService {

    private static final long SESSION_MINUTES = 30;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RsaKeyHolder rsaKeyHolder;
    private final RedisTtlPolicy ttl;
    private final StringRedisTemplate redis;
    private final LogService logService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       RsaKeyHolder rsaKeyHolder,
                       RedisTtlPolicy ttl,
                       StringRedisTemplate redis,
                       LogService logService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rsaKeyHolder = rsaKeyHolder;
        this.ttl = ttl;
        this.redis = redis;
        this.logService = logService;
    }

    @Data
    public static class CaptchaVo {
        private final String uuid;
        private final String image;
    }

    public CaptchaVo captcha() {
        String code = cn.chenxinjie.pathfinder.util.CaptchaUtil.randomCode(4);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        ttl.setWithExplicitTtl("auth:captcha:" + uuid, code, 300, false);
        return new CaptchaVo(uuid, cn.chenxinjie.pathfinder.util.CaptchaUtil.imageBase64(code));
    }

    /**
     * 登录：验证码 → 锁定 → 解密 → BCrypt → 多登录踢出 → 会话。
     */
    @Transactional
    public String login(String username, String encryptedPassword, String captchaUuid, String captchaCode,
                        String ip, String ua) {
        String lockKey = "auth:lock:" + username;
        if (redis.hasKey(lockKey)) {
            logService.recordLogin(null, username, ip, ua, false, "账号锁定中");
            throw BizException.forbidden("账号已锁定，请 10 分钟后再试");
        }
        // 1. 验证码（一次性）
        String code = ttl.get("auth:captcha:" + captchaUuid);
        if (code == null || !code.equalsIgnoreCase(captchaCode)) {
            logService.recordLogin(null, username, ip, ua, false, "验证码错误或已过期");
            throw BizException.badRequest("验证码错误或已过期");
        }
        ttl.delete("auth:captcha:" + captchaUuid);

        // 2. 解密 + 校验凭证
        String password = decryptPassword(encryptedPassword);
        User user = userRepository.findByUsernameAndDelFlag(username, 0).orElse(null);

        boolean ok = user != null && user.getStatus() == 1 && passwordEncoder.matches(password, user.getPassword());
        if (!ok) {
            String failKey = "auth:fail:" + username;
            long count = ttl.increment(failKey, 600);
            if (count >= 5) {
                ttl.setWithExplicitTtl(lockKey, "1", 600, false);
                ttl.delete(failKey);
                logService.recordLogin(user == null ? null : user.getId(), username, ip, ua, false, "连续失败 5 次，锁定 10 分钟");
                throw BizException.forbidden("账号已锁定，请 10 分钟后再试");
            }
            logService.recordLogin(user == null ? null : user.getId(), username, ip, ua, false, "用户名或密码错误");
            throw BizException.badRequest("用户名或密码错误");
        }
        ttl.delete("auth:fail:" + username);

        // 3. 多登录踢出 + 会话
        String token = UUID.randomUUID().toString().replace("-", "");
        String sessionMapKey = "auth:user:session:" + user.getId();
        String oldToken = ttl.get(sessionMapKey);
        if (oldToken != null) {
            redis.delete("auth:session:" + oldToken);
        }
        ttl.setWithExplicitTtl("auth:session:" + token, String.valueOf(user.getId()), SESSION_MINUTES * 60, false);
        ttl.setWithExplicitTtl(sessionMapKey, token, SESSION_MINUTES * 60, false);

        user.setLastLoginAt(java.time.LocalDateTime.now());
        userRepository.save(user);
        logService.recordLogin(user.getId(), username, ip, ua, true, "登录成功");
        return token;
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String userIdStr = redis.opsForValue().get("auth:session:" + token);
        if (userIdStr != null) {
            redis.delete("auth:user:session:" + userIdStr);
        }
        redis.delete("auth:session:" + token);
    }

    @Transactional
    public void changePassword(String oldEncrypted, String newEncrypted, AuthUser current, String ip, String ua) {
        User user = userRepository.findById(current.getId())
                .orElseThrow(() -> BizException.notFound("用户不存在"));
        String oldPwd = decryptPassword(oldEncrypted);
        if (!passwordEncoder.matches(oldPwd, user.getPassword())) {
            throw BizException.badRequest("原密码不正确");
        }
        String newPwd = decryptPassword(newEncrypted);
        if (newPwd.length() < 8) {
            throw BizException.badRequest("新密码长度不能少于 8 位");
        }
        user.setPassword(passwordEncoder.encode(newPwd));
        user.setMustChangePassword(0);
        userRepository.save(user);
        logService.record(current, "PASSWORD", "USER", String.valueOf(user.getId()), user.getUsername(), "修改密码", true);
    }

    private String decryptPassword(String encrypted) {
        try {
            byte[] data = java.util.Base64.getDecoder().decode(encrypted);
            return new String(rsaKeyHolder.decrypt(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw BizException.badRequest("密码解密失败，请刷新页面重新登录");
        }
    }

    public void touchSession(String token) {
        if (token != null && !token.isBlank()) {
            String key = "auth:session:" + token;
            String v = redis.opsForValue().get(key);
            if (v != null) {
                redis.expire(key, Duration.ofMinutes(SESSION_MINUTES));
                // 滑动续期同步刷新会话映射（G6）
                redis.expire("auth:user:session:" + v, Duration.ofMinutes(SESSION_MINUTES));
            }
        }
    }
}
