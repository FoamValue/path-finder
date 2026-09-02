package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 磁盘存储与监控（PRD F6 / TSDD 8.2，对应 TC-ST-002/003/004/007）：
 * 启动目录初始化、存储用量统计、根目录不可达时降级为 ratio=0 且不告警。
 */
class StorageServiceTest {

    @TempDir
    Path tempDir;

    private PathProperties props(String root) {
        PathProperties p = new PathProperties();
        p.getStorage().setRoot(root);
        return p;
    }

    private StorageService service(PathProperties props) {
        return new StorageService(props, mock(OperationLogRepository.class), mock(LogService.class));
    }

    @Test
    void initDirs_createsAllStorageSubdirectories() {
        Path root = tempDir.resolve("storage-root");
        StorageService svc = service(props(root.toString()));

        svc.initDirs();

        for (String sub : List.of("files", "upload", "del", "tmp", "archive")) {
            assertTrue(Files.isDirectory(root.resolve(sub)), "应自动创建目录: " + sub);
        }
    }

    @Test
    void info_returnsSaneTotalsForRealRoot() {
        Path root = tempDir.resolve("storage-real");
        StorageService svc = service(props(root.toString()));
        svc.initDirs();

        StorageService.StorageInfo info = svc.info();

        assertTrue(info.getTotal() > 0, "总容量应大于 0");
        assertTrue(info.getRatio() >= 0 && info.getRatio() <= 1, "使用率应在 [0,1]");
        assertTrue(info.getUsed() >= 0 && info.getAvailable() >= 0);
    }

    @Test
    void info_rootUnreachable_degradesToRatioZeroWithoutAlert() {
        Path missing = tempDir.resolve("not-exist-storage");
        LogService logService = mock(LogService.class);
        StorageService svc = new StorageService(props(missing.toString()), mock(OperationLogRepository.class), logService);

        StorageService.StorageInfo info = svc.info();

        assertEquals(0.0, info.getRatio());
        assertFalse(info.isAlert(), "根目录不可达不应触发告警");
        verify(logService, never()).record(any(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
