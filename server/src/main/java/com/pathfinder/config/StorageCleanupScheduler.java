package com.pathfinder.config;

import com.pathfinder.config.PathProperties;
import com.pathfinder.entity.FileInfo;
import com.pathfinder.entity.FileRecycleBin;
import com.pathfinder.repository.FileInfoRepository;
import com.pathfinder.repository.FileRecycleBinRepository;
import com.pathfinder.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 存储清理调度（每日 2:00）：
 * 1) 回收站到期（30 天）物理清除（G7）；
 * 2) UPLOADING 孤儿记录（>24h）清理（G2）。
 */
@Component
public class StorageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupScheduler.class);

    private final FileRecycleBinRepository recycleBinRepository;
    private final FileInfoRepository fileInfoRepository;
    private final PathProperties pathProperties;

    public StorageCleanupScheduler(FileRecycleBinRepository recycleBinRepository,
                                   FileInfoRepository fileInfoRepository,
                                   PathProperties pathProperties) {
        this.recycleBinRepository = recycleBinRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.pathProperties = pathProperties;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        // 1) 回收站到期清理
        List<FileRecycleBin> expired = recycleBinRepository.findByExpireAtBefore(now);
        for (FileRecycleBin rb : expired) {
            fileInfoRepository.findById(rb.getFileId()).ifPresent(f -> {
                Path del = pathProperties.getStorage().delPath().resolve(f.getStoragePath());
                try {
                    Files.deleteIfExists(del);
                } catch (IOException ignore) {
                }
                fileInfoRepository.delete(f);
            });
            recycleBinRepository.delete(rb);
        }
        if (!expired.isEmpty()) {
            log.info("回收站清理：已清除 {} 条过期记录", expired.size());
        }
        // 2) UPLOADING 孤儿记录清理
        List<FileInfo> orphans = fileInfoRepository
                .findByDelFlagAndStatusAndCreatedAtBefore(0, "UPLOADING", now.minusHours(24));
        for (FileInfo f : orphans) {
            fileInfoRepository.delete(f);
        }
        if (!orphans.isEmpty()) {
            log.info("UPLOADING 孤儿清理：已清除 {} 条", orphans.size());
        }
    }
}
