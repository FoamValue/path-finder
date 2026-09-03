package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.entity.Dept;
import cn.chenxinjie.pathfinder.entity.Role;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.DeptRepository;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import cn.chenxinjie.pathfinder.service.StorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动初始化 Seed（TSDD 3.4 / G1，对应 TC-ORG-014）：
 * 空库时初始化四角色 + 根部门 + 首个 admin（初始密码 Init@123，首次登录强制改密），已初始化时幂等跳过。
 */
class DataInitializerTest {

    private static final String INIT_PASSWORD = "Init@123";

    private final StorageService storageService = mock(StorageService.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final DeptRepository deptRepository = mock(DeptRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void emptyDatabase_seedsRolesRootDeptAndAdmin() {
        when(roleRepository.count()).thenReturn(0L);
        when(deptRepository.count()).thenReturn(0L);
        when(userRepository.existsByUsername("admin")).thenReturn(false);

        List<Role> savedRoles = new ArrayList<>();
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            r.setId((long) savedRoles.size() + 1);
            savedRoles.add(r);
            return r;
        });
        java.util.concurrent.atomic.AtomicReference<Dept> savedRoot = new java.util.concurrent.atomic.AtomicReference<>();
        when(deptRepository.save(any(Dept.class))).thenAnswer(inv -> {
            Dept d = inv.getArgument(0);
            d.setId(1L);
            savedRoot.set(d);
            return d;
        });
        when(deptRepository.findAll()).thenAnswer(inv -> List.of(savedRoot.get()));
        Role adminRole = new Role();
        adminRole.setId(4L);
        adminRole.setRoleCode("ADMIN");
        when(roleRepository.findByRoleCode("ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(1L);
            }
            return u;
        });

        DataInitializer initializer = new DataInitializer(storageService, roleRepository, deptRepository,
                userRepository, userRoleRepository, encoder, new PathProperties());
        initializer.run(null);

        // 四角色
        verify(roleRepository, times(4)).save(any(Role.class));
        assertTrue(savedRoles.stream().anyMatch(r -> "ADMIN".equals(r.getRoleCode())));
        assertTrue(savedRoles.stream().anyMatch(r -> "DEPT_ADMIN".equals(r.getRoleCode())));
        assertTrue(savedRoles.stream().anyMatch(r -> "VIEWER".equals(r.getRoleCode())));
        // 根部门
        verify(deptRepository, times(1)).save(any(Dept.class));
        verify(storageService).initDirs();

        // admin：初始密码 + 强制改密 + ADMIN 角色
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User admin = userCaptor.getValue();
        assertEquals("admin", admin.getUsername());
        assertTrue(encoder.matches(INIT_PASSWORD, admin.getPassword()), "admin 初始密码必须为 Init@123 的 BCrypt");
        assertEquals(1, admin.getMustChangePassword(), "首登强制改密");
        assertEquals(1, admin.getStatus());

        ArgumentCaptor<UserRole> urCaptor = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleRepository).save(urCaptor.capture());
        assertEquals(adminRole.getId(), urCaptor.getValue().getRoleId());
        assertEquals(admin.getId(), urCaptor.getValue().getUserId());
    }

    @Test
    void emptyDatabase_withBootstrapAdminPassword_seedsDeterministicAccount() {
        when(roleRepository.count()).thenReturn(0L);
        when(deptRepository.count()).thenReturn(0L);
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        List<Role> saved = new ArrayList<>();
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            r.setId((long) saved.size() + 1);
            saved.add(r);
            return r;
        });
        java.util.concurrent.atomic.AtomicReference<Dept> rootRef = new java.util.concurrent.atomic.AtomicReference<>();
        when(deptRepository.save(any(Dept.class))).thenAnswer(inv -> {
            Dept d = inv.getArgument(0);
            d.setId(10L);
            rootRef.set(d);
            return d;
        });
        when(deptRepository.findAll()).thenAnswer(inv -> List.of(rootRef.get()));
        Role adminRole = new Role();
        adminRole.setId(4L);
        adminRole.setRoleCode("ADMIN");
        when(roleRepository.findByRoleCode("ADMIN")).thenReturn(Optional.of(adminRole));

        PathProperties props = new PathProperties();
        props.getSecurity().setBootstrapAdminPassword("E2e@12345");

        DataInitializer initializer = new DataInitializer(storageService, roleRepository, deptRepository,
                userRepository, userRoleRepository, encoder, props);
        initializer.run(null);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User admin = userCaptor.getValue();
        assertEquals("admin", admin.getUsername());
        assertTrue(encoder.matches("E2e@12345", admin.getPassword()), "种子账号密码来自 bootstrap 配置");
        assertEquals(0, admin.getMustChangePassword(), "bootstrap 账号不强制改密，保证 E2E 可重复登录");
    }

    @Test
    void alreadySeeded_skipsIdempotently() {
        when(roleRepository.count()).thenReturn(4L);
        when(deptRepository.count()).thenReturn(1L);
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        DataInitializer initializer = new DataInitializer(storageService, roleRepository, deptRepository,
                userRepository, userRoleRepository, encoder, new PathProperties());
        initializer.run(null);

        verify(roleRepository, never()).save(any());
        verify(deptRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        verify(userRoleRepository, never()).save(any());
        verify(roleRepository, never()).findByRoleCode(anyString());
    }
}
