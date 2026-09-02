package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.config.SecurityConfig;
import cn.chenxinjie.pathfinder.entity.Role;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端点鉴权矩阵（TSDD 5.1 / PRD F1，对应 TC-LOGIN-016/019/020/021/022/023）。
 * 通过 @WebMvcTest + 真实 SecurityConfig 过滤链（Redis/Repo 均 mock）验证：
 * 匿名放行端点（captcha/publicKey/login）、其余端点需认证（401）、
 * 强制改密白名单（仅 changePassword/logout/me）、停用用户 401。
 * 不依赖 MySQL/Redis 实例。
 */
@WebMvcTest(controllers = EndpointSecurityMatrixTest.TestApi.class)
@Import({SecurityConfig.class, EndpointSecurityMatrixTest.TestApi.class})
class EndpointSecurityMatrixTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StringRedisTemplate redis;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserRoleRepository userRoleRepository;
    @MockitoBean
    private RoleRepository roleRepository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        Role role = new Role();
        role.setId(2L);
        role.setRoleCode("USER");
        when(userRoleRepository.findByUserId(anyLong())).thenReturn(List.of());
        when(roleRepository.findById(anyLong())).thenReturn(Optional.of(role));
    }

    private User user(long id, int status, int mustChange) {
        User u = new User();
        u.setId(id);
        u.setUsername("zhangsan");
        u.setRealName("张三");
        u.setDeptId(1L);
        u.setStatus(status);
        u.setDelFlag(0);
        u.setMustChangePassword(mustChange);
        return u;
    }

    private void stubSession(String token, User user) {
        when(redis.opsForValue().get("auth:session:" + token))
                .thenReturn(user == null ? null : String.valueOf(user.getId()));
        when(userRepository.findById(user == null ? -1 : user.getId())).thenReturn(Optional.ofNullable(user));
    }

    /* ---------- 匿名放行（TSDD 5.1 白名单） ---------- */

    @Test
    void anonymous_canAccessCaptchaPublicKeyAndLogin() throws Exception {
        mvc.perform(get("/api/captcha")).andExpect(status().isOk());
        mvc.perform(get("/api/publicKey")).andExpect(status().isOk());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());
    }

    @Test
    void anonymous_protectedApi_rejected401() throws Exception {
        mvc.perform(get("/api/protected")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymous_changePasswordAndLogout_rejected401() throws Exception {
        // TC-LOGIN-023：无会话调用改密/登出被拒
        mvc.perform(post("/api/changePassword")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/logout")).andExpect(status().isUnauthorized());
    }

    /* ---------- 有效会话 ---------- */

    @Test
    void validSession_accessProtectedAndSensitive() throws Exception {
        stubSession("tok-ok", user(1L, 1, 0));
        mvc.perform(get("/api/protected").header("Authorization", "Bearer tok-ok"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/changePassword").header("Authorization", "Bearer tok-ok"))
                .andExpect(status().isOk());
    }

    /* ---------- 强制改密态（TC-LOGIN-019/020） ---------- */

    @Test
    void mustChangePassword_otherApi_forbidden403() throws Exception {
        stubSession("tok-mc", user(1L, 1, 1));
        mvc.perform(get("/api/protected").header("Authorization", "Bearer tok-mc"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mustChangePassword_whitelistSensitiveEndpoints_allowed() throws Exception {
        stubSession("tok-mc", user(1L, 1, 1));
        mvc.perform(post("/api/changePassword").header("Authorization", "Bearer tok-mc"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/logout").header("Authorization", "Bearer tok-mc"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer tok-mc"))
                .andExpect(status().isOk());
    }

    /* ---------- 账号状态（TC-LOGIN-022） ---------- */

    @Test
    void disabledUser_oldSession_rejected401() throws Exception {
        stubSession("tok-disabled", user(2L, 0, 0));
        mvc.perform(get("/api/protected").header("Authorization", "Bearer tok-disabled"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSession_rejected401() throws Exception {
        stubSession("tok-expired", null);
        mvc.perform(get("/api/protected").header("Authorization", "Bearer tok-expired"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestApi {

        @GetMapping("/api/captcha")
        public String captcha() {
            return "captcha";
        }

        @GetMapping("/api/publicKey")
        public String publicKey() {
            return "PUBKEY";
        }

        @PostMapping("/api/login")
        public String login() {
            return "ok";
        }

        @GetMapping("/api/protected")
        public String protectedApi() {
            return "ok";
        }

        @PostMapping("/api/changePassword")
        public String changePassword() {
            return "ok";
        }

        @PostMapping("/api/logout")
        public String logout() {
            return "ok";
        }

        @GetMapping("/api/auth/me")
        public String me() {
            return "ok";
        }
    }
}
