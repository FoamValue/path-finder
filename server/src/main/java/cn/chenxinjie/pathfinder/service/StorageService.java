package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.repository.OperationLogRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 磁盘存储与监控（PRD F6 / TSDD 8.2）。
 */
@Service
public class StorageService {

    public static final double ALERT_RATIO = 0.85;

    private final PathProperties pathProperties;
    private final OperationLogRepository operationLogRepository;
    private final LogService logService;

    public StorageService(PathProperties pathProperties, OperationLogRepository operationLogRepository,
                          LogService logService) {
        this.pathProperties = pathProperties;
        this.operationLogRepository = operationLogRepository;
        this.logService = logService;
    }

    @Data
    public static class StorageInfo {
        private long total;
        private long used;
        private long available;
        private double ratio;
        private boolean alert;
    }

    public void initDirs() {
        try {
            for (Path dir : java.util.List.of(
                    pathProperties.getStorage().filesPath(),
                    pathProperties.getStorage().uploadPath(),
                    pathProperties.getStorage().delPath(),
                    pathProperties.getStorage().tmpPath(),
                    pathProperties.getStorage().archivePath())) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("存储目录初始化失败", e);
        }
    }

    public StorageInfo info() {
        StorageInfo info = new StorageInfo();
        try {
            FileStore store = Files.getFileStore(pathProperties.getStorage().rootPath());
            info.setTotal(store.getTotalSpace());
            info.setAvailable(store.getUsableSpace());
            info.setUsed(info.getTotal() - info.getAvailable());
            info.setRatio((double) info.getUsed() / Math.max(1, info.getTotal()));
            info.setAlert(info.getRatio() >= ALERT_RATIO);
        } catch (IOException e) {
            info.setRatio(0);
        }
        if (info.isAlert()) {
            logService.record(null, "STORAGE_ALERT", "STORAGE", null, null,
                    String.format("磁盘使用率 %.2f%% ≥ %d%%", info.getRatio() * 100, (int) (ALERT_RATIO * 100)), true);
        }
        return info;
    }
}
