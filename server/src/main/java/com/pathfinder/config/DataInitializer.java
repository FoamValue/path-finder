package com.pathfinder.config;

import com.pathfinder.entity.Dept;
import com.pathfinder.entity.Role;
import com.pathfinder.entity.User;
import com.pathfinder.entity.UserRole;
import com.pathfinder.repository.DeptRepository;
import com.pathfinder.repository.RoleRepository;
import com.pathfinder.repository.UserRepository;
import com.pathfinder.repository.UserRoleRepository;
import com.pathfinder.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动初始化：存储目录 + 初始数据 Seed（角色、根部门、首个 admin，TSDD 3.4）。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final StorageService storageService;
    private final RoleRepository roleRepository;
    private final DeptRepository deptRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(StorageService storageService, RoleRepository roleRepository,
                           DeptRepository deptRepository, UserRepository userRepository,
                           UserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder) {
        this.storageService = storageService;
        this.roleRepository = roleRepository;
        this.deptRepository = deptRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        storageService.initDirs();
        seedRoles();
        seedRootDept();
        seedAdmin();
        log.info("PathFinder 数据初始化完成");
    }

    private void seedRoles() {
        if (roleRepository.count() > 0) {
            return;
        }
        for (String[] r : new String[][]{
                {"ADMIN", "系统管理员"},
                {"DEPT_ADMIN", "部门管理员"},
                {"USER", "普通员工"},
                {"VIEWER", "访客"}}) {
            Role role = new Role();
            role.setRoleCode(r[0]);
            role.setRoleName(r[1]);
            role.setDescription(r[0] + " 角色");
            roleRepository.save(role);
        }
        log.info("已初始化角色数据");
    }

    private void seedRootDept() {
        if (deptRepository.count() > 0) {
            return;
        }
        Dept root = new Dept();
        root.setParentId(0L);
        root.setName("组织");
        root.setSortOrder(0);
        deptRepository.save(root);
        log.info("已初始化根部门");
    }

    private void seedAdmin() {
        if (userRepository.existsByUsername("admin")) {
            return;
        }
        Dept root = deptRepository.findAll().stream().findFirst().orElseThrow();
        User admin = new User();
        admin.setUsername("admin");
        admin.setRealName("系统管理员");
        admin.setDeptId(root.getId());
        admin.setPassword(passwordEncoder.encode("Init@123"));
        admin.setMustChangePassword(1);
        admin.setStatus(1);
        userRepository.save(admin);
        roleRepository.findByRoleCode("ADMIN").ifPresent(role -> {
            UserRole ur = new UserRole();
            ur.setUserId(admin.getId());
            ur.setRoleId(role.getId());
            userRoleRepository.save(ur);
        });
        log.info("已初始化系统管理员 admin（初始密码 Init@123，首次登录强制改密）");
    }
}
