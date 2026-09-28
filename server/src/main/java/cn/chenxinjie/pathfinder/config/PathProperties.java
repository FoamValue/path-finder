package cn.chenxinjie.pathfinder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * PathFinder 自有配置：存储根目录、安全参数、目录同步扫描。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "pathfinder")
public class PathProperties {

    private Storage storage = new Storage();
    private Security security = new Security();
    private Sync sync = new Sync();

    @Data
    public static class Storage {
        private String root = "./data/storage";

        public Path rootPath() {
            return Path.of(root).toAbsolutePath().normalize();
        }

        public Path filesPath() {
            return rootPath().resolve("files");
        }

        public Path uploadPath() {
            return rootPath().resolve("upload");
        }

        public Path delPath() {
            return rootPath().resolve("del");
        }

        public Path tmpPath() {
            return rootPath().resolve("tmp");
        }

        public Path archivePath() {
            return rootPath().resolve("archive");
        }
    }

    @Data
    public static class Security {
        private String privateKeyPath = "";
        private long sessionTimeoutMinutes = 30;
        private int loginMaxFail = 5;
        private int loginLockMinutes = 10;
        /** 登录验证码开关（默认开启；测试环境可设 CAPTCHA_ENABLED=false 绕过，仅限非生产部署） */
        private boolean captchaEnabled = true;
        /** 空库 Seed 时若配置则用该密码创建首个 admin 且不强制改密，用于可重复的自动化测试种子账号 */
        private String bootstrapAdminPassword = "";
        /**
         * CORS 允许的可信前端来源（显式白名单，禁止通配；配合 allowCredentials=true 使用）。
         * 默认覆盖 vite 本地开发源；生产由 PATHFINDER_SECURITY_CORS_ALLOWED_ORIGINS 覆盖。
         */
        private List<String> corsAllowedOrigins = List.of("http://localhost:5173", "http://localhost:8000");
        /** 是否信任反向代理注入的 X-Forwarded-For（审计 IP 溯源）。仅当部署在受信反向代理后置 true，否则回退 RemoteAddr。 */
        private boolean trustProxyHeader = false;
    }

    @Data
    public static class Sync {
        private boolean enabled = true;
        private String watchDir = "./data/import";
        private Duration interval = Duration.ofMinutes(5);
        private long skipRecentSeconds = 30;
        private boolean dedupByMd5 = true;

        public Path watchDirPath() {
            return Path.of(watchDir).toAbsolutePath().normalize();
        }
    }
}
