package com.pathfinder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * PathFinder 自有配置：存储根目录、安全参数。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "pathfinder")
public class PathProperties {

    private Storage storage = new Storage();
    private Security security = new Security();

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
}
