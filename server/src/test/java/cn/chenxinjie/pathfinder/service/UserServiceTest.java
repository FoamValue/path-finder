package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.entity.Dept;
import cn.chenxinjie.pathfinder.entity.Role;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理服务（PRD F2 / TSDD 4.2 / G12）。
 * 覆盖：仅 ADMIN 可写、初始密码 + 强制改密、角色/部门/状态编辑、重置密码、
 * 停用、删除前个人文件强制移交校验、分页（ADMIN 全量 / DEPT_ADMIN 本部门范围）。
 */
class UserServiceTest {

    private static final String INIT = "Init@123";

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private UserRoleRepository userRoleRepository;
    private DeptService deptService;
    private FileInfoRepository fileInfoRepository;
    private BCryptPasswordEncoder encoder;
    private LogService logService;
    private UserService userService;

    private final AuthUser admin = new AuthUser(1L, "admin", "系统管理员", "ADMIN", 1L, 0);
    private final AuthUser userRole = new AuthUser(2L, "zhangsan", "张三", "USER", 1L, 0);
    private final AuthUser deptAdmin = new AuthUser(3L, "d1", "部门管理员", "DEPT_ADMIN", 2L, 0);

    private Dept devDept;
    private Role userRoleEntity;

    private long nextUserId = 100;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        deptService = mock(DeptService.class);
        fileInfoRepository = mock(FileInfoRepository.class);
        encoder = new BCryptPasswordEncoder(4);
        logService = mock(LogService.class);

        userService = new UserService(userRepository, roleRepository, userRoleRepository,
                deptService, fileInfoRepository, encoder, logService);

        devDept = dept(2, "研发部");
        userRoleEntity = role(10L, "USER", "普通员工");
        when(deptService.get(1L)).thenReturn(dept(1, "组织"));
        when(deptService.get(2L)).thenReturn(devDept);
        when(roleRepository.findByRoleCode("USER")).thenReturn(Optional.of(userRoleEntity));
        when(roleRepository.findByRoleCode("DEPT_ADMIN")).thenReturn(Optional.of(role(11L, "DEPT_ADMIN", "部门管理员")));
        when(roleRepository.findByRoleCode("ADMIN")).thenReturn(Optional.of(role(12L, "ADMIN", "系统管理员")));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(nextUserId++);
            }
            return u;
        });
        when(userRoleRepository.findByUserId(any(Long.class))).thenReturn(new ArrayList<>());
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Dept dept(long id, String name) {
        Dept d = new Dept();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private Role role(long id, String code, String name) {
        Role r = new Role();
        r.setId(id);
        r.setRoleCode(code);
        r.setRoleName(name);
        return r;
    }

    private User user(long id, String username, String realName, long deptId, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRealName(realName);
        u.setDeptId(deptId);
        u.setStatus(status);
        u.setDelFlag(0);
        u.setPassword(encoder.encode(INIT));
        return u;
    }

    private void stubUserRole(long userId, long roleId) {
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        when(userRoleRepository.findByUserId(userId)).thenReturn(List.of(ur));
        Role r = roleRepository.findByRoleCode("USER").orElseThrow();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(r));
    }

    @Test
    void create_nonAdmin_forbidden() {
        when(userRepository.existsByUsername("x")).thenReturn(false);
        UserService.UserForm form = form("zhangsan", "张三", 2L, "USER");
        BizException e = assertThrows(BizException.class,
                () -> userService.create(form, userRole));
        assertEquals(403, e.getStatus());
        verify(userRepository, never()).save(any());
    }

    @Test
    void create_duplicateUsername_rejected() {
        when(userRepository.existsByUsername("zhangsan")).thenReturn(true);
        BizException e = assertThrows(BizException.class,
                () -> userService.create(form("zhangsan", "张三", 2L, "USER"), admin));
        assertEquals(400, e.getStatus());
    }

    @Test
    void create_success_initialPwdMustChangeRoleAndAudit() {
        when(userRepository.existsByUsername("lisi")).thenReturn(false);
        when(deptService.get(2L)).thenReturn(devDept);

        UserService.UserVo vo = userService.create(form("lisi", "李四", 2L, "USER"), admin);

        assertEquals("lisi", vo.getUsername());
        assertEquals("研发部", vo.getDeptName());
        assertEquals("USER", vo.getRoleCode());
        assertEquals(1, vo.getMustChangePassword(), "新用户必须强制改密");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertTrue(encoder.matches(INIT, captor.getValue().getPassword()), "初始密码为 Init@123 的 BCrypt");
        ArgumentCaptor<UserRole> urCaptor = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleRepository).save(urCaptor.capture());
        assertEquals(userRoleEntity.getId(), urCaptor.getValue().getRoleId());
        verify(logService).record(eq(admin), eq("USER_CREATE"), eq("USER"), anyString(), eq("lisi"), anyString(), eq(true));
    }

    @Test
    void update_roleNameAndDept_changesAndReplacesRole() {
        User existing = user(200, "lisi", "李四", 2L, 1);
        when(userRepository.findById(200L)).thenReturn(Optional.of(existing));
        // 更新后角色查询返回新映射（真实库在 delete+save 后即为该状态）
        when(userRoleRepository.findByUserId(200L)).thenReturn(new ArrayList<>());
        UserRole newUr = new UserRole();
        newUr.setUserId(200L);
        newUr.setRoleId(11L);

        UserService.UserForm form = form("lisi", "李四四", 2L, "DEPT_ADMIN");
        UserService.UserVo vo = userService.update(200L, form, admin);

        assertEquals("李四四", vo.getRealName());
        verify(userRoleRepository).deleteByUserId(200L);
        ArgumentCaptor<UserRole> urCaptor = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleRepository).save(urCaptor.capture());
        assertEquals(11L, urCaptor.getValue().getRoleId());
    }

    @Test
    void update_notFound_throws404() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        BizException e = assertThrows(BizException.class,
                () -> userService.update(404L, form("x", "X", 1L, "USER"), admin));
        assertEquals(404, e.getStatus());
    }

    @Test
    void updateStatus_disablesAndAudits() {
        User existing = user(200, "lisi", "李四", 2L, 1);
        when(userRepository.findById(200L)).thenReturn(Optional.of(existing));

        userService.updateStatus(200L, 0, admin);

        assertEquals(0, existing.getStatus());
        verify(logService).record(eq(admin), eq("USER_STATUS"), eq("USER"), eq("200"), anyString(), anyString(), eq(true));
    }

    @Test
    void updateStatus_nonAdmin_forbidden() {
        BizException e = assertThrows(BizException.class,
                () -> userService.updateStatus(200L, 0, userRole));
        assertEquals(403, e.getStatus());
    }

    @Test
    void resetPassword_forcesRechange() {
        User existing = user(200, "lisi", "李四", 2L, 1);
        existing.setMustChangePassword(0);
        when(userRepository.findById(200L)).thenReturn(Optional.of(existing));

        userService.resetPassword(200L, admin);

        assertEquals(1, existing.getMustChangePassword(), "重置后需再次强制改密");
        assertTrue(encoder.matches(INIT, existing.getPassword()));
        verify(logService).record(eq(admin), eq("USER_RESET_PWD"), eq("USER"), eq("200"), anyString(), anyString(), eq(true));
    }

    @Test
    void delete_hasPersonalFiles_blockedByG12() {
        User existing = user(200, "lisi", "李四", 2L, 1);
        when(userRepository.findById(200L)).thenReturn(Optional.of(existing));
        when(fileInfoRepository.countByDelFlagAndOwnerId(0, 200L)).thenReturn(2L);

        BizException e = assertThrows(BizException.class, () -> userService.delete(200L, admin));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("移交"));
        assertEquals(0, existing.getDelFlag(), "有个人文件时不得删除用户");
        verify(userRoleRepository, never()).deleteByUserId(200L);
    }

    @Test
    void delete_noPersonalFiles_softDeletesAndCleansRoles() {
        User existing = user(200, "lisi", "李四", 2L, 1);
        when(userRepository.findById(200L)).thenReturn(Optional.of(existing));
        when(fileInfoRepository.countByDelFlagAndOwnerId(0, 200L)).thenReturn(0L);

        userService.delete(200L, admin);

        assertEquals(1, existing.getDelFlag(), "无个人文件用户删除为软删除");
        verify(userRoleRepository).deleteByUserId(200L);
        verify(logService).record(eq(admin), eq("USER_DELETE"), eq("USER"), eq("200"), anyString(), anyString(), eq(true));
    }

    @Test
    void page_admin_seesAll_andClampsPageSize() {
        List<User> all = new ArrayList<>();
        for (long i = 1; i <= 105; i++) {
            all.add(user(1000 + i, "user" + i, "用户" + i, 1L + (i % 3), 1));
        }
        User del = user(9000, "deleted", "已删", 1L, 1);
        del.setDelFlag(1);
        all.add(del);
        when(userRepository.findAll()).thenReturn(all);

        PageResult<UserService.UserVo> page = userService.page(admin, null, null, 1, 20);
        assertEquals(105, page.getTotal(), "软删除用户不计入");
        assertEquals(20, page.getList().size());
    }

    @Test
    void page_keywordAndDept_filter() {
        List<User> all = List.of(
                user(1, "zhangsan", "张三", 2L, 1),
                user(2, "lisi", "李四", 2L, 1),
                user(3, "wangwu", "王五", 1L, 1));
        when(userRepository.findAll()).thenReturn(all);

        PageResult<UserService.UserVo> byKeyword = userService.page(admin, "zhangsan", null, 1, 20);
        assertEquals(1, byKeyword.getTotal());
        assertEquals("zhangsan", byKeyword.getList().get(0).getUsername());

        PageResult<UserService.UserVo> byDept = userService.page(admin, null, 2L, 1, 20);
        assertEquals(2, byDept.getTotal(), "按部门过滤");
    }

    @Test
    void page_deptAdmin_scopedToVisibleDepts() {
        List<User> all = List.of(
                user(1, "a", "甲", 2L, 1),   // 研发部（可见）
                user(2, "b", "乙", 3L, 1),   // 研发部子部门（可见）
                user(3, "c", "丙", 5L, 1));  // 财务部（不可见）
        when(userRepository.findAll()).thenReturn(all);
        when(deptService.visibleDeptIds(deptAdmin)).thenReturn(Set.of(2L, 3L));

        PageResult<UserService.UserVo> page = userService.page(deptAdmin, null, null, 1, 20);
        assertEquals(2, page.getTotal(), "DEPT_ADMIN 仅见本部门范围用户");
        assertTrue(page.getList().stream().allMatch(v -> Set.of(2L, 3L).contains(v.getDeptId())));
    }

    @Test
    void resolveRoleCode_noRoles_defaultsToUser() {
        when(userRoleRepository.findByUserId(500L)).thenReturn(List.of());
        assertEquals("USER", userService.resolveRoleCode(500L));
    }

    private UserService.UserForm form(String username, String realName, Long deptId, String roleCode) {
        UserService.UserForm f = new UserService.UserForm();
        f.setUsername(username);
        f.setRealName(realName);
        f.setDeptId(deptId);
        f.setRoleCode(roleCode);
        f.setStatus(1);
        return f;
    }
}
