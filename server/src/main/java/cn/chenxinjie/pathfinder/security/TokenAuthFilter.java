package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.entity.Role;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 会话 Token 认证过滤器：校验 Token（Cookie {@code pf_token} 或 Authorization: Bearer）→
 * Redis → 用户状态 → SecurityContext；认证通过后滑动续期并刷新会话映射（G6）。
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PathProperties pathProperties;

    public TokenAuthFilter(StringRedisTemplate redis,
                           UserRepository userRepository,
                           UserRoleRepository userRoleRepository,
                           RoleRepository roleRepository,
                           PathProperties pathProperties) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.pathProperties = pathProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String token = resolveToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String userIdStr = redis.opsForValue().get("auth:session:" + token);
                if (userIdStr != null) {
                    User user = userRepository.findById(Long.valueOf(userIdStr)).orElse(null);
                    if (user != null && user.getStatus() == 1 && user.getDelFlag() == 0) {
                        boolean mustChange = user.getMustChangePassword() == 1;
                        boolean allowed = !mustChange
                                || path.equals("/api/changePassword")
                                || path.equals("/api/logout")
                                || path.equals("/api/auth/me")
                                || path.equals("/api/publicKey");
                        if (allowed) {
                            AuthUser au = new AuthUser(user.getId(), user.getUsername(),
                                    user.getRealName(), resolveRoleCode(user.getId()), user.getOrgId(),
                                    user.getMustChangePassword());
                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(au, null, List.of());
                            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            // G6 滑动续期：命中有效会话即刷新会话与映射的 TTL
                            long timeout = Math.max(1L, pathProperties.getSecurity().getSessionTimeoutMinutes()) * 60L;
                            redis.expire("auth:session:" + token, Duration.ofSeconds(timeout));
                            redis.expire("auth:user:session:" + userIdStr, Duration.ofSeconds(timeout));
                        } else {
                            // 强制改密态访问其他接口
                            SecurityContextHolder.clearContext();
                            response.sendError(403, "请先修改初始密码");
                            return;
                        }
                    }
                }
            } catch (Exception ignore) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveRoleCode(Long userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId)
                .flatMap(rid -> roleRepository.findById(rid).stream())
                .map(Role::getRoleCode)
                .findFirst()
                .orElse("USER");
    }

    private String resolveToken(HttpServletRequest request) {
        // M2：优先 HttpOnly Cookie（JS 不可读，防 XSS 窃取），兼容 Authorization 头
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pf_token".equals(c.getName())) {
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
}
