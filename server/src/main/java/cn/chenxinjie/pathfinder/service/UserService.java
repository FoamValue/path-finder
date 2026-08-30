package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.entity.Role;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import lombok.Data;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理（PRD F2 / TSDD 4.2）。
 */
@Service
public class UserService {

    public static final String INIT_PASSWORD = "Init@123";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final DeptService deptService;
    private final FileInfoRepository fileInfoRepository;
    private final PasswordEncoder passwordEncoder;
    private final LogService logService;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       UserRoleRepository userRoleRepository, DeptService deptService,
                       FileInfoRepository fileInfoRepository, PasswordEncoder passwordEncoder,
                       LogService logService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.deptService = deptService;
        this.fileInfoRepository = fileInfoRepository;
        this.passwordEncoder = passwordEncoder;
        this.logService = logService;
    }

    @Data
    public static class UserForm {
        private String username;
        private String realName;
        private Long deptId;
        private String roleCode = "USER";
        private Integer status = 1;
    }

    @Data
    public static class UserVo {
        private Long id;
        private String username;
        private String realName;
        private Long deptId;
        private String deptName;
        private String roleCode;
        private Integer status;
        private Integer mustChangePassword;
        private java.time.LocalDateTime createdAt;
    }

    public PageResult<UserVo> page(AuthUser operator, String keyword, Long deptId, int pageNum, int pageSize) {
        Pageable pageable = Pageable.ofSize(Math.min(pageSize, 100)).withPage(pageNum - 1);
        var all = userRepository.findAll();
        java.util.List<User> filtered = all.stream()
                .filter(u -> u.getDelFlag() == 0)
                .filter(u -> operator.isAdmin() || (deptService.visibleDeptIds(operator) != null
                        && deptService.visibleDeptIds(operator).contains(u.getDeptId())))
                .filter(u -> keyword == null || keyword.isBlank() || u.getUsername().contains(keyword)
                        || u.getRealName().contains(keyword))
                .filter(u -> deptId == null || u.getDeptId().equals(deptId))
                .sorted(java.util.Comparator.comparing(User::getId))
                .toList();
        int from = Math.min((int) pageable.getOffset(), filtered.size());
        int to = Math.min(from + pageable.getPageSize(), filtered.size());
        java.util.List<UserVo> list = filtered.subList(from, to).stream().map(u -> toVo(u)).toList();
        return PageResult.of(list, filtered.size(), pageNum, pageSize);
    }

    private UserVo toVo(User u) {
        UserVo vo = new UserVo();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setRealName(u.getRealName());
        vo.setDeptId(u.getDeptId());
        vo.setRoleCode(resolveRoleCode(u.getId()));
        vo.setStatus(u.getStatus());
        vo.setMustChangePassword(u.getMustChangePassword());
        vo.setCreatedAt(u.getCreatedAt());
        try {
            vo.setDeptName(deptService.get(u.getDeptId()).getName());
        } catch (Exception ignore) {
        }
        return vo;
    }

    @Transactional
    public UserVo create(UserForm form, AuthUser operator) {
        if (!operator.isAdmin()) {
            throw BizException.forbidden("仅系统管理员可新增用户");
        }
        if (userRepository.existsByUsername(form.getUsername())) {
            throw BizException.badRequest("用户名已存在");
        }
        deptService.get(form.getDeptId());
        Role role = roleRepository.findByRoleCode(form.getRoleCode())
                .orElseThrow(() -> BizException.badRequest("角色不存在"));
        User user = new User();
        user.setUsername(form.getUsername());
        user.setRealName(form.getRealName());
        user.setDeptId(form.getDeptId());
        user.setStatus(form.getStatus() == null ? 1 : form.getStatus());
        user.setPassword(passwordEncoder.encode(INIT_PASSWORD));
        user.setMustChangePassword(1);
        userRepository.save(user);
        UserRole ur = new UserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(role.getId());
        userRoleRepository.save(ur);
        logService.record(operator, "USER_CREATE", "USER", String.valueOf(user.getId()), user.getUsername(), "新增用户", true);
        return toVo(user);
    }

    @Transactional
    public UserVo update(Long id, UserForm form, AuthUser operator) {
        if (!operator.isAdmin()) {
            throw BizException.forbidden("仅系统管理员可编辑用户");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        if (form.getRealName() != null) {
            user.setRealName(form.getRealName());
        }
        if (form.getDeptId() != null) {
            deptService.get(form.getDeptId());
            user.setDeptId(form.getDeptId());
        }
        if (form.getStatus() != null) {
            user.setStatus(form.getStatus());
        }
        if (form.getRoleCode() != null && !form.getRoleCode().isBlank()) {
            Role role = roleRepository.findByRoleCode(form.getRoleCode())
                    .orElseThrow(() -> BizException.badRequest("角色不存在"));
            userRoleRepository.deleteByUserId(id);
            UserRole ur = new UserRole();
            ur.setUserId(id);
            ur.setRoleId(role.getId());
            userRoleRepository.save(ur);
        }
        userRepository.save(user);
        logService.record(operator, "USER_UPDATE", "USER", String.valueOf(id), user.getUsername(), "编辑用户", true);
        return toVo(user);
    }

    @Transactional
    public void updateStatus(Long id, Integer status, AuthUser operator) {
        if (!operator.isAdmin()) {
            throw BizException.forbidden("仅系统管理员可操作");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        user.setStatus(status);
        userRepository.save(user);
        logService.record(operator, "USER_STATUS", "USER", String.valueOf(id), user.getUsername(),
                "启用/停用=" + status, true);
    }

    @Transactional
    public void resetPassword(Long id, AuthUser operator) {
        if (!operator.isAdmin()) {
            throw BizException.forbidden("仅系统管理员可重置密码");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        user.setPassword(passwordEncoder.encode(INIT_PASSWORD));
        user.setMustChangePassword(1);
        userRepository.save(user);
        logService.record(operator, "USER_RESET_PWD", "USER", String.valueOf(id), user.getUsername(), "重置密码", true);
    }

    /**
     * 删除用户：强制校验个人空间文件已移交（G12）。
     */
    @Transactional
    public void delete(Long id, AuthUser operator) {
        if (!operator.isAdmin()) {
            throw BizException.forbidden("仅系统管理员可删除用户");
        }
        User user = userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
        long personal = fileInfoRepository.countByDelFlagAndOwnerId(0, id);
        if (personal > 0) {
            throw BizException.badRequest("该用户仍有 " + personal + " 个个人空间文件，请先移交归属后再删除");
        }
        user.setDelFlag(1);
        userRepository.save(user);
        userRoleRepository.deleteByUserId(id);
        logService.record(operator, "USER_DELETE", "USER", String.valueOf(id), user.getUsername(), "删除用户", true);
    }

    public String resolveRoleCode(Long userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId)
                .flatMap(rid -> roleRepository.findById(rid).stream())
                .map(Role::getRoleCode)
                .findFirst()
                .orElse("USER");
    }

    public User get(Long id) {
        return userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在"));
    }
}
