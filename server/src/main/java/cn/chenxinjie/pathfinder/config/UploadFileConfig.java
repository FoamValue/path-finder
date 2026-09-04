package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.security.UploadOwnerAccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * upload-file 组件手动装配（starter 因 javax.servlet 与 Spring Boot 4 不兼容被降级）。
 * 接口契约与组件 README 一致：POST /upload（multipart file + 7 参数）、progress/merge/mergeAsync/mergeStatus/cancel。
 *
 * <p>组件侧能力按「尽量复用 cn.chenxinjie 组件」的原则接入：</p>
 * <ul>
 *   <li>AccessControl SPI（rc.3）：任务级归属授权 {@link UploadOwnerAccessControl}，替代 PermitAll；</li>
 *   <li>StorageCleanupService（rc.3）：过期任务 + 孤儿分片/合并目录回收，与上传共用 IdentifierLock；</li>
 *   <li>setMaxTotalBytes（rc.3）：全局容量配额（quota.max-bytes），超限抛 QuotaExceededException(507)；</li>
 *   <li>getTask/cancelUpload/UploadErrorCode（rc.4）：confirm 产物定位与显式回收、稳定错误语义。</li>
 * </ul>
 */
@Configuration
public class UploadFileConfig {

    private static final Logger log = LoggerFactory.getLogger(UploadFileConfig.class);

    @Bean
    public IdentifierLock uploadFileIdentifierLock() {
        return new IdentifierLock();
    }

    @Bean
    public TaskStore uploadFileTaskStore(
            @Value("${upload-file.metadata-store:redis}") String metadataStore,
            @Value("${upload-file.metadata-dir:}") String metadataDir,
            @Value("${upload-file.redis.host:localhost}") String host,
            @Value("${upload-file.redis.port:6379}") int port,
            @Value("${upload-file.redis.password:}") String password,
            @Value("${upload-file.redis.key-prefix:upload:task:}") String keyPrefix,
            @Value("${upload-file.redis.ttl-seconds:86400}") int ttlSeconds) {
        return switch (metadataStore.trim().toLowerCase()) {
            case "memory" -> new MemoryTaskStore();
            case "file" -> new FileTaskStore(Paths.get(metadataDir));
            default -> cn.chenxinjie.uploadfile.store.redis.RedisTaskStore
                    .create(host, port, password, keyPrefix, ttlSeconds);
        };
    }

    @Bean
    public ChunkStorage uploadFileChunkStorage(@Value("${upload-file.storage-dir}") String storageDir) {
        return new LocalFileChunkStorage(Paths.get(storageDir, "chunks"));
    }

    @Bean
    public ResumableUploadService resumableUploadService(
            TaskStore uploadFileTaskStore,
            ChunkStorage uploadFileChunkStorage,
            ExecutorService uploadFileAsyncMergeExecutor,
            IdentifierLock uploadFileIdentifierLock,
            AccessControl uploadOwnerAccessControl,
            @Value("${upload-file.storage-dir}") String storageDir,
            @Value("${upload-file.verify-checksum:true}") boolean verifyChecksum,
            @Value("${upload-file.merge.fsync:true}") boolean fsync,
            @Value("${upload-file.merge.atomic:true}") boolean atomic,
            @Value("${upload-file.max-chunk-size:0}") long maxChunkSize,
            @Value("${upload-file.max-file-size:0}") long maxFileSize,
            @Value("${upload-file.quota.max-bytes:0}") long quotaMaxBytes) {
        java.io.File mergedDir = Paths.get(storageDir, "files").toFile();
        ResumableUploadService service = new ResumableUploadService(
                uploadFileTaskStore, uploadFileChunkStorage, mergedDir,
                verifyChecksum, fsync, atomic, uploadFileIdentifierLock, uploadOwnerAccessControl);
        if (maxChunkSize > 0) {
            service.setMaxChunkBytes(maxChunkSize);
        }
        if (maxFileSize > 0) {
            service.setMaxFileBytes(maxFileSize);
        }
        if (quotaMaxBytes > 0) {
            service.setMaxTotalBytes(quotaMaxBytes);
        }
        service.setAsyncExecutor(uploadFileAsyncMergeExecutor);
        return service;
    }

    /**
     * 组件 StorageCleanupService（rc.3）：定期回收过期未完成任务与其分片、无任务记录的孤儿分片/合并目录。
     * 与上传服务共享 IdentifierLock，保证清理与上传/合并互斥；单实例部署不启用分布式 RedisCleanupLock。
     */
    @Bean(destroyMethod = "stop")
    public StorageCleanupService uploadFileStorageCleanupService(
            TaskStore uploadFileTaskStore,
            ChunkStorage uploadFileChunkStorage,
            IdentifierLock uploadFileIdentifierLock,
            @Value("${upload-file.storage-dir}") String storageDir,
            @Value("${upload-file.cleanup.enabled:true}") boolean cleanupEnabled,
            @Value("${upload-file.cleanup.run-on-startup:true}") boolean runOnStartup,
            @Value("${upload-file.cleanup.interval:1h}") Duration cleanupInterval,
            @Value("${upload-file.cleanup.task-ttl:24h}") Duration taskTtl,
            @Value("${upload-file.cleanup.orphan-enabled:true}") boolean orphanEnabled) {
        StorageCleanupService service = new StorageCleanupService(
                uploadFileTaskStore, uploadFileChunkStorage,
                Paths.get(storageDir, "files").toFile(),
                taskTtl.toMillis(), orphanEnabled, uploadFileIdentifierLock);
        service.setErrorListener(t -> log.error("upload-file 组件清理失败", t));
        service.setStatsListener(s -> {
            if (s != null) {
                log.info("upload-file cleanup: cleanedTasks={} cleanedOrphans={} elapsedMs={} error={}",
                        s.getCleanedTasks(), s.getCleanedOrphans(), s.getElapsedMillis(),
                        s.getError() == null ? "-" : s.getError());
            }
        });
        if (runOnStartup) {
            service.cleanup();
        }
        if (cleanupEnabled && cleanupInterval != null
                && !cleanupInterval.isZero() && !cleanupInterval.isNegative()) {
            service.start(cleanupInterval.toMillis());
        }
        return service;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService uploadFileAsyncMergeExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "upload-file-async-merge-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
