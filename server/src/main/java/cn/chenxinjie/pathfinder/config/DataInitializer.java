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
    private final PathProperties pathProperties;

    public DataInitializer(StorageService storageService, RoleRepository roleRepository,
                           DeptRepository deptRepository, UserRepository userRepository,
                           UserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder,
                           PathProperties pathProperties) {
        this.storageService = storageService;
        this.roleRepository = roleRepository;
        this.deptRepository = deptRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.pathProperties = pathProperties;
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
        String bootstrapPwd = pathProperties.getSecurity().getBootstrapAdminPassword();
        boolean bootstrap = bootstrapPwd != null && !bootstrapPwd.isBlank();
        User admin = new User();
        admin.setUsername("admin");
        admin.setRealName("系统管理员");
        admin.setDeptId(root.getId());
        // 自动化测试可经 ADMIN_BOOTSTRAP_PASSWORD 注入固定密码并跳过强制改密；默认 Init@123 + 首登强制改密
        admin.setPassword(passwordEncoder.encode(bootstrap ? bootstrapPwd : "Init@123"));
        admin.setMustChangePassword(bootstrap ? 0 : 1);
        admin.setStatus(1);
        userRepository.save(admin);
        roleRepository.findByRoleCode("ADMIN").ifPresent(role -> {
            UserRole ur = new UserRole();
            ur.setUserId(admin.getId());
            ur.setRoleId(role.getId());
            userRoleRepository.save(ur);
        });
        if (bootstrap) {
            log.info("已初始化系统管理员 admin（测试种子账号，密码由 ADMIN_BOOTSTRAP_PASSWORD 提供，不强制改密）");
        } else {
            log.info("已初始化系统管理员 admin（初始密码 Init@123，首次登录强制改密）");
        }
    }
}
