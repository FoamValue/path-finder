package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.entity.Role;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话 Token 认证过滤器（TSDD 5.1/5.2，对应 TC-LOGIN-016/019/020/021/022/023）。
 * 覆盖：有效会话认证、强制改密白名单（仅 /api/changePassword|logout|me）、
 * 停用/软删用户不认证、会话过期 401、无/畸形 Bearer。
 * 纯单元，不依赖 Redis/MySQL。
 */
class TokenAuthFilterTest {

    private StringRedisTemplate redis;
    private UserRepository userRepository;
    private UserRoleRepository userRoleRepository;
    private RoleRepository roleRepository;
    private ValueOperations<String, String> valueOps;
    private TokenAuthFilter filter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        userRepository = mock(UserRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        roleRepository = mock(RoleRepository.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> vo = mock(ValueOperations.class);
        valueOps = vo;
        when(redis.opsForValue()).thenReturn(valueOps);
        filter = new TokenAuthFilter(redis, userRepository, userRoleRepository, roleRepository);

        Role role = new Role();
        role.setId(2L);
        role.setRoleCode("USER");
        when(userRoleRepository.findByUserId(anyLong())).thenReturn(List.of());
        when(roleRepository.findById(anyLong())).thenReturn(Optional.of(role));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User activeUser(long id, int mustChange) {
        User u = new User();
        u.setId(id);
        u.setUsername("zhangsan");
        u.setRealName("张三");
        u.setDeptId(1L);
        u.setStatus(1);
        u.setDelFlag(0);
        u.setMustChangePassword(mustChange);
        return u;
    }

    private void stubSession(String token, User user) {
        when(valueOps.get("auth:session:" + token)).thenReturn(user == null ? null : String.valueOf(user.getId()));
        when(userRepository.findById(user == null ? -1 : user.getId())).thenReturn(Optional.ofNullable(user));
    }

    private MockHttpServletRequest request(String method, String uri, String authHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (authHeader != null) {
            req.addHeader("Authorization", authHeader);
        }
        return req;
    }

    private Authentication afterAuthenticate(User user) throws Exception {
        MockHttpServletRequest req = request("GET", "/api/file/page", "Bearer tok-1");
        stubSession("tok-1", user);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void validSession_activeUser_isAuthenticatedAndProceeds() throws Exception {
        User user = activeUser(1L, 0);
        Authentication auth = afterAuthenticate(user);

        assertNotNull(auth, "有效会话应建立认证");
        AuthUser principal = (AuthUser) auth.getPrincipal();
        assertEquals(1L, principal.getId());
        assertEquals("USER", principal.getRoleCode());
    }

    @Test
    void mustChangePassword_otherApi_returns403AndDropsChain() throws Exception {
        User user = activeUser(1L, 1);
        MockHttpServletRequest req = request("GET", "/api/file/page", "Bearer tok-mc");
        stubSession("tok-mc", user);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(403, resp.getStatus(), "强制改密态访问其余接口应 403");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void mustChangePassword_whitelistChangePasswordLogoutMe_allowed() throws Exception {
        User user = activeUser(1L, 1);
        for (String uri : List.of("/api/changePassword", "/api/logout", "/api/auth/me")) {
            MockHttpServletRequest req = request("POST", uri, "Bearer tok-mc");
            stubSession("tok-mc", user);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(200, resp.getStatus(), "强制改密白名单应放行: " + uri);
            assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "白名单路径应建立认证: " + uri);
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void disabledUser_notAuthenticated() throws Exception {
        User user = activeUser(2L, 0);
        user.setStatus(0);
        Authentication auth = afterAuthenticate(user);
        assertNull(auth, "停用用户即使持有旧 token 也不应被认证（后续返回 401）");
    }

    @Test
    void softDeletedUser_notAuthenticated() throws Exception {
        User user = activeUser(3L, 0);
        user.setDelFlag(1);
        Authentication auth = afterAuthenticate(user);
        assertNull(auth);
    }

    @Test
    void expiredSession_noAuthAndChainProceeds() throws Exception {
        stubSession("tok-expired", null);
        Authentication auth = afterAuthenticate(null);
        assertNull(auth, "会话过期/无效 token 不建立认证");
    }

    @Test
    void noToken_noAuth() throws Exception {
        MockHttpServletRequest req = request("GET", "/api/file/page", null);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void malformedBearer_noToken() throws Exception {
        MockHttpServletRequest req = request("GET", "/api/file/page", "BearerXtok");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
