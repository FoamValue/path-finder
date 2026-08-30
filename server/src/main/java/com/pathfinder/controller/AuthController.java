package com.pathfinder.controller;

import com.pathfinder.dto.ApiResponse;
import com.pathfinder.security.AuthUser;
import com.pathfinder.security.SecurityUtil;
import com.pathfinder.service.AuthService;
import com.pathfinder.util.RsaKeyHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口：验证码 / 公钥 / 登录 / 登出 / 改密 / 当前用户（PRD F1）。
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final RsaKeyHolder rsaKeyHolder;

    public AuthController(AuthService authService, RsaKeyHolder rsaKeyHolder) {
        this.authService = authService;
        this.rsaKeyHolder = rsaKeyHolder;
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
    public ApiResponse<Map<String, String>> login(@RequestBody LoginForm form, HttpServletRequest request) {
        String token = authService.login(form.getUsername(), form.getEncryptedPassword(),
                form.getCaptchaUuid(), form.getCaptchaCode(), clientIp(request), request.getHeader("User-Agent"));
        Map<String, String> map = new HashMap<>();
        map.put("token", token);
        return ApiResponse.ok(map);
    }

    @PostMapping("/api/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String token = resolveToken(request);
        authService.logout(token);
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
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
