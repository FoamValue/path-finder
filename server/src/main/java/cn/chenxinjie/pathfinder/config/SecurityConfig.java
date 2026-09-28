package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.entity.Role;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import cn.chenxinjie.pathfinder.security.TokenAuthFilter;
import cn.chenxinjie.pathfinder.util.RedisTtlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security 配置：无状态 Token 会话 + 端点鉴权矩阵（TSDD 5.1）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           StringRedisTemplate redis,
                                           UserRepository userRepository,
                                           UserRoleRepository userRoleRepository,
                                           RoleRepository roleRepository,
                                           PathProperties pathProperties) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource(pathProperties)))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 放行（匿名）
                .requestMatchers("/api/captcha", "/api/publicKey", "/api/login", "/error").permitAll()
                // 其余（/api/**、/logout、/changePassword、/upload、/download）均需认证
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                        res.sendError(401, "未登录或会话已失效"))
                .accessDeniedHandler((req, res, e) ->
                        res.sendError(403, "无权限")));
        http.addFilterBefore(
                new TokenAuthFilter(redis, userRepository, userRoleRepository, roleRepository, pathProperties),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(PathProperties pathProperties) {
        CorsConfiguration cfg = new CorsConfiguration();
        // H1 修复：显式 Origin 白名单（禁止通配 + 凭据的组合）。
        // allowCredentials=true 时 Spring 要求精确来源而非 pattern；从配置读取可信前端源。
        List<String> origins = pathProperties.getSecurity().getCorsAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            origins = new ArrayList<>(List.of("http://localhost:5173"));
        }
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
