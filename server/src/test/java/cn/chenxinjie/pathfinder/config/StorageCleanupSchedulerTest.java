package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.entity.FileRecycleBin;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.FileRecycleBinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储清理调度（TSDD 8.2 / G2 / G7，对应 TC-FILE-011/014）：
 * 1) 回收站到期（30 天）物理清除；2) UPLOADING 孤儿记录（>24h）清理。
 * 采用 mock Repository + 真实临时目录，验证物理文件删除与 DB 记录删除。
 */
class StorageCleanupSchedulerTest {

    @TempDir
    Path tempDir;

    private FileRecycleBinRepository recycleRepo;
    private FileInfoRepository fileInfoRepo;
    private StorageCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        recycleRepo = mock(FileRecycleBinRepository.class);
        fileInfoRepo = mock(FileInfoRepository.class);
        PathProperties props = new PathProperties();
        props.getStorage().setRoot(tempDir.toString());
        scheduler = new StorageCleanupScheduler(recycleRepo, fileInfoRepo, props);
    }

    @Test
    void expiredRecycleRecord_purgesPhysicalFileAndDb() throws Exception {
        FileInfo f = fileInfo(5L, "UPLOADING/READY 不参与回收站校验", "files/2026-01-01/a.txt");
        // 软删除时物理文件位于 del/ 下对应相对路径
        Path delFile = tempDir.resolve("del").resolve("files/2026-01-01/a.txt");
        Files.createDirectories(delFile.getParent());
        Files.writeString(delFile, "gone-content");

        FileRecycleBin expired = recycle(5L, LocalDateTime.now().minusDays(31));
        when(recycleRepo.findByExpireAtBefore(any())).thenReturn(List.of(expired));
        when(fileInfoRepo.findById(5L)).thenReturn(Optional.of(f));
        when(fileInfoRepo.findByDelFlagAndStatusAndCreatedAtBefore(any(), any(), any()))
                .thenReturn(List.of());

        scheduler.cleanup();

        assertFalse(Files.exists(delFile), "过期回收站物理文件应被清除");
        verify(fileInfoRepo).delete(f);
        verify(recycleRepo).delete(expired);
    }

    @Test
    void notExpiredRecycleRecord_kept() throws Exception {
        when(recycleRepo.findByExpireAtBefore(any())).thenReturn(List.of());
        when(fileInfoRepo.findByDelFlagAndStatusAndCreatedAtBefore(any(), any(), any()))
                .thenReturn(List.of());

        scheduler.cleanup();

        verify(fileInfoRepo, never()).delete(any(FileInfo.class));
        verify(recycleRepo, never()).delete(any(FileRecycleBin.class));
    }

    @Test
    void uploadingOrphanOlderThan24h_deleted() {
        FileInfo orphan = fileInfo(7L, "UPLOADING", "files/2026-01-02/orphan.txt");
        orphan.setCreatedAt(LocalDateTime.now().minusHours(25));
        FileInfo fresh = fileInfo(8L, "UPLOADING", "files/2026-01-02/fresh.txt");
        fresh.setCreatedAt(LocalDateTime.now().minusHours(1));

        when(recycleRepo.findByExpireAtBefore(any())).thenReturn(List.of());
        when(fileInfoRepo.findByDelFlagAndStatusAndCreatedAtBefore(any(), any(), any()))
                .thenReturn(List.of(orphan));

        scheduler.cleanup();

        verify(fileInfoRepo).delete(orphan);
        verify(fileInfoRepo, never()).delete(fresh);
    }

    private FileInfo fileInfo(long id, String status, String storagePath) {
        FileInfo f = new FileInfo();
        f.setId(id);
        f.setStatus(status);
        f.setStoragePath(storagePath);
        return f;
    }

    private FileRecycleBin recycle(long fileId, LocalDateTime expireAt) {
        FileRecycleBin rb = new FileRecycleBin();
        rb.setFileId(fileId);
        rb.setDeletedBy(1L);
        rb.setDeletedAt(LocalDateTime.now().minusDays(30));
        rb.setExpireAt(expireAt);
        return rb;
    }
}
