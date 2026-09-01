package cn.chenxinjie.pathfinder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

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
