package cn.chenxinjie.pathfinder.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁定登录会话有效期：application.yml 中 session-timeout（分钟）必须为 480，即 8 小时。
 * 该值经 AuthService.sessionTimeoutSeconds() 统一供给登录会话写盘、Cookie Max-Age 与滑动续期。
 */
class SessionTimeoutConfigTest {

    @Test
    void sessionTimeoutShouldBeEightHours() throws IOException {
        String yml = new String(
                new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^\\s*session-timeout:\\s*(\\d+)\\s*#?.*$").matcher(yml);
        assertEquals(true, m.find(), "application.yml 必须配置 session-timeout");
        assertEquals(480, Integer.parseInt(m.group(1)),
                "session-timeout 应为 480 分钟（8 小时），登录会话将保持 8 小时");
    }
}