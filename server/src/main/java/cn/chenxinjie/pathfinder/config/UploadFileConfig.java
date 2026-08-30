package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.uploadfile.core.security.PermitAllAccessControl;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * upload-file 组件手动装配（starter 因 javax.servlet 与 Spring Boot 4 不兼容被降级）。
 * 接口契约与组件 README 一致：POST /upload（multipart file + 7 参数）、progress/merge/mergeAsync/mergeStatus。
 */
@Configuration
public class UploadFileConfig {

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
            @Value("${upload-file.storage-dir}") String storageDir,
            @Value("${upload-file.verify-checksum:true}") boolean verifyChecksum,
            @Value("${upload-file.merge.fsync:true}") boolean fsync,
            @Value("${upload-file.merge.atomic:true}") boolean atomic,
            @Value("${upload-file.max-chunk-size:0}") long maxChunkSize,
            @Value("${upload-file.max-file-size:0}") long maxFileSize) {
        java.io.File mergedDir = Paths.get(storageDir, "files").toFile();
        ResumableUploadService service = new ResumableUploadService(
                uploadFileTaskStore, uploadFileChunkStorage, mergedDir,
                verifyChecksum, fsync, atomic, new IdentifierLock(), PermitAllAccessControl.INSTANCE);
        if (maxChunkSize > 0) {
            service.setMaxChunkBytes(maxChunkSize);
        }
        if (maxFileSize > 0) {
            service.setMaxFileBytes(maxFileSize);
        }
        service.setAsyncExecutor(uploadFileAsyncMergeExecutor);
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
