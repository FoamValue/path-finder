package cn.chenxinjie.pathfinder.controller;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.service.AuthService;
import cn.chenxinjie.pathfinder.util.RsaKeyHolder;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口：验证码 / 公钥 / 登录 / 登出 / 改密 / 当前用户（PRD F1）。
 */
@RestController
public class AuthController {

    static final String SESSION_COOKIE = "pf_token";

    private final AuthService authService;
    private final RsaKeyHolder rsaKeyHolder;
    private final PathProperties pathProperties;

    public AuthController(AuthService authService, RsaKeyHolder rsaKeyHolder, PathProperties pathProperties) {
        this.authService = authService;
        this.rsaKeyHolder = rsaKeyHolder;
        this.pathProperties = pathProperties;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginForm {
        private String username;
        private String encryptedPassword;
        private String captchaUuid;
        private String captchaCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangePwdForm {
        private String oldPassword;
        private String newPassword;
    }

    @GetMapping("/api/captcha")
    public ApiResponse<AuthService.CaptchaVo> captcha() {
        return ApiResponse.ok(authService.captcha());
    }

    @GetMapping("/api/publicKey")
    public ApiResponse<Map<String, String>> publicKey() {
        Map<String, String> map = new HashMap<>();
        map.put("publicKey", rsaKeyHolder.publicKeyBase64());
        return ApiResponse.ok(map);
    }

    @PostMapping("/api/login")
    public ApiResponse<Map<String, String>> login(@RequestBody LoginForm form, HttpServletRequest request,
                                                  HttpServletResponse response) {
        String token = authService.login(form.getUsername(), form.getEncryptedPassword(),
                form.getCaptchaUuid(), form.getCaptchaCode(), clientIp(request), request.getHeader("User-Agent"));
        // M2：Token 下发到 HttpOnly + SameSite=Lax Cookie，JS 不可读；同时在响应体返回以便兼容旧前端
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token, authService.sessionTimeoutSeconds(),
                request.isSecure()).toString());
        Map<String, String> map = new HashMap<>();
        map.put("token", token);
        return ApiResponse.ok(map);
    }

    @PostMapping("/api/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = resolveToken(request);
        authService.logout(token);
        // 清除 HttpOnly Cookie
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0, request.isSecure()).toString());
        return ApiResponse.ok();
    }

    @PostMapping("/api/changePassword")
    public ApiResponse<Void> changePassword(@RequestBody ChangePwdForm form, HttpServletRequest request) {
        authService.changePassword(form.getOldPassword(), form.getNewPassword(),
                SecurityUtil.current(), clientIp(request), request.getHeader("User-Agent"));
        return ApiResponse.ok();
    }

    @GetMapping("/api/auth/me")
    public ApiResponse<AuthUser> me() {
        return ApiResponse.ok(SecurityUtil.current());
    }

    private String resolveToken(HttpServletRequest request) {
        // M2：优先 HttpOnly Cookie，兼容 Authorization 头
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SESSION_COOKIE.equals(c.getName())) {
                    String v = c.getValue();
                    if (v != null && !v.isBlank()) {
                        return v;
                    }
                    return null;
                }
            }
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private ResponseCookie sessionCookie(String value, long maxAgeSeconds, boolean secure) {
        return ResponseCookie.from(SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(Math.max(0, maxAgeSeconds)))
                .build();
    }

    private String clientIp(HttpServletRequest request) {
        // M3：仅在显式信任反向代理（trust-proxy-header=true）时才读取 X-Forwarded-For，否则回退直连地址，
        // 防止审计 IP 被伪造
        if (pathProperties.getSecurity().isTrustProxyHeader()) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) {
                return ip.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
