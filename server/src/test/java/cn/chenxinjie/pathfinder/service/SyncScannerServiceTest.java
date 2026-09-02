package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.util.PathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录同步扫描测试：导入、去重、缺失标记、touch 不误报、内容替换标记、复活复位、下载拦截与刷新。
 */
@SpringBootTest
@ActiveProfiles("test")
class SyncScannerServiceTest {

    @Autowired
    private SyncScannerService syncScannerService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileInfoRepository fileInfoRepository;

    @Autowired
    private PathProperties pathProperties;

    @Autowired
    private UserRepository userRepository;

    private Long adminId;
    private Path root;
    private Path watch;

    @BeforeEach
    void setUp() throws Exception {
        adminId = userRepository.findByUsernameAndDelFlag("admin", 0).orElseThrow().getId();
        root = pathProperties.getStorage().rootPath();
        watch = pathProperties.getSync().watchDirPath();
        fileInfoRepository.deleteAll();
        deleteRecursively(root.resolve("files"));
        deleteRecursively(watch);
        Files.createDirectories(watch);
    }

    @AfterEach
    void tearDown() throws Exception {
        fileInfoRepository.deleteAll();
        deleteRecursively(root.resolve("files"));
        deleteRecursively(watch);
    }

    @Test
    void importNewFile_createsRecord_adminPublicReady() throws Exception {
        Files.writeString(watch.resolve("hello.txt"), "hello world");

        syncScannerService.scan();

        FileInfo f = findByName("hello.txt");
        assertNotNull(f, "导入后应生成记录");
        assertEquals("PUBLIC", f.getSpaceType());
        assertEquals(adminId, f.getOwnerId());
        assertEquals(adminId, f.getCreatorId());
        assertEquals("READY", f.getStatus());
        assertEquals("READY", f.getDiskStatus());
        assertNotNull(f.getDiskModifiedAt());
        assertFalse(Files.exists(watch.resolve("hello.txt")), "源文件应被移出导入目录");
        assertTrue(Files.exists(root.resolve(f.getStoragePath())), "物理文件应存在于 files/");
    }

    @Test
    void import_skipsDuplicateByMd5() throws Exception {
        Files.writeString(watch.resolve("dup.txt"), "same content");

        syncScannerService.scan();
        assertEquals(1, countByName("dup.txt"));

        Files.writeString(watch.resolve("dup2.txt"), "same content");
        syncScannerService.scan();

        assertEquals(1, countByName("dup.txt"), "重复 MD5 不应再次导入");
        assertTrue(Files.exists(watch.resolve("dup2.txt")), "重复文件应保留在导入目录");
    }

    @Test
    void missingFile_marksMissing() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/x.txt", "x.txt", "AAA");
        Files.delete(root.resolve(f.getStoragePath()));

        syncScannerService.scan();

        assertEquals("MISSING", reload(f.getId()).getDiskStatus());
    }

    @Test
    void touchOnly_keepsReady() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/y.txt", "y.txt", "content");
        Path p = root.resolve(f.getStoragePath());
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

        syncScannerService.scan();

        assertEquals("READY", reload(f.getId()).getDiskStatus(), "仅 mtime 变化不应标记更新");
    }

    @Test
    void contentReplaced_marksUpdated() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/z.txt", "z.txt", "AAA");
        Files.writeString(root.resolve(f.getStoragePath()), "AAABBB");

        syncScannerService.scan();

        assertEquals("UPDATED", reload(f.getId()).getDiskStatus());
    }

    @Test
    void missingFileReappears_restoresReady() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/w.txt", "w.txt", "orig");
        Path p = root.resolve(f.getStoragePath());
        Files.delete(p);
        syncScannerService.scan();
        assertEquals("MISSING", reload(f.getId()).getDiskStatus());

        Files.writeString(p, "orig");
        syncScannerService.scan();

        assertEquals("READY", reload(f.getId()).getDiskStatus(), "内容一致应复位 READY");
    }

    @Test
    void download_missing_blocked() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/m.txt", "m.txt", "data");
        Files.delete(root.resolve(f.getStoragePath()));
        syncScannerService.scan();
        assertEquals("MISSING", reload(f.getId()).getDiskStatus());

        FileService.DownloadTarget target = new FileService.DownloadTarget("single", f.getId(), null, f.getOriginalName());
        BizException ex = assertThrows(BizException.class, () -> fileService.resolveDownloadPath(target));
        assertTrue(ex.getMessage().contains("删除"), "应提示目录文件已经被删除");
    }

    @Test
    void download_updated_refreshesToReady() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/u.txt", "u.txt", "old");
        Files.writeString(root.resolve(f.getStoragePath()), "new content");
        syncScannerService.scan();
        assertEquals("UPDATED", reload(f.getId()).getDiskStatus());

        fileService.refreshAfterDownload(f.getId());

        FileInfo after = reload(f.getId());
        assertEquals("READY", after.getDiskStatus());
        assertEquals("new content", new String(Files.readAllBytes(root.resolve(after.getStoragePath()))));
    }

    @Test
    void scan_skipsWhenAlreadyRunning() throws Exception {
        Files.writeString(watch.resolve("skip.txt"), "not imported");
        Object target = AopTestUtils.getTargetObject(syncScannerService);
        Field field = target.getClass().getDeclaredField("running");
        field.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) field.get(target);
        running.set(true);
        try {
            syncScannerService.scan();
            assertTrue(findByName("skip.txt") == null, "扫描进行中应跳过本次触发");
            assertTrue(Files.exists(watch.resolve("skip.txt")));
        } finally {
            running.set(false);
        }
    }

    @Test
    void legacyRecord_withoutDiskBaseline_establishesBaselineWithoutFlag() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/legacy.txt", "legacy.txt", "base-content");
        f.setDiskModifiedAt(null); // 功能上线前入库的历史数据
        fileInfoRepository.save(f);

        syncScannerService.scan();

        FileInfo after = reload(f.getId());
        assertEquals("READY", after.getDiskStatus(), "历史数据仅建立基线不应误报");
        assertNotNull(after.getDiskModifiedAt(), "应写入磁盘 mtime 基线");
        assertEquals("base-content",
                new String(Files.readAllBytes(root.resolve(after.getStoragePath()))));
    }

    @Test
    void missingFileReappears_withDifferentContent_marksUpdated() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/re-appear.txt", "re-appear.txt", "orig");
        Path p = root.resolve(f.getStoragePath());
        Files.delete(p);
        syncScannerService.scan();
        assertEquals("MISSING", reload(f.getId()).getDiskStatus());

        Files.writeString(p, "BRAND-NEW-CONTENT-DIFFERENT");
        syncScannerService.scan();

        assertEquals("UPDATED", reload(f.getId()).getDiskStatus(),
                "复活但内容不一致应标记 UPDATED 而非复位 READY");
    }

    @Test
    void updatedFile_restoredToOriginalContent_resetsReady() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/revert.txt", "revert.txt", "orig-content");
        Path p = root.resolve(f.getStoragePath());
        Files.delete(p);
        syncScannerService.scan();
        assertEquals("MISSING", reload(f.getId()).getDiskStatus());

        Files.writeString(p, "SOME-REPLACED-LONGER-CONTENT-AAAA");
        syncScannerService.scan();
        assertEquals("UPDATED", reload(f.getId()).getDiskStatus());

        Files.writeString(p, "orig-content"); // 内容还原为库内 md5
        syncScannerService.scan();

        assertEquals("READY", reload(f.getId()).getDiskStatus(),
                "内容还原为原 MD5 应复位 READY");
    }

    @Test
    void contentReplacedAgain_keepsUpdatedUntilRestored() throws Exception {
        FileInfo f = seedReady("files/2026-09-01/keep.txt", "keep.txt", "orig");
        Path p = root.resolve(f.getStoragePath());
        Files.writeString(p, "REPLACED-VERSION-1");
        syncScannerService.scan();
        assertEquals("UPDATED", reload(f.getId()).getDiskStatus());

        Files.writeString(p, "REPLACED-VERSION-2-DIFFERENT");
        syncScannerService.scan();

        assertEquals("UPDATED", reload(f.getId()).getDiskStatus(),
                "持续与库内 MD5 不一致应保持 UPDATED");
    }

    @Test
    void import_skipsFileWithinSkipRecentWindow() throws Exception {
        var sync = pathProperties.getSync();
        long original = sync.getSkipRecentSeconds();
        sync.setSkipRecentSeconds(60);
        try {
            Files.writeString(watch.resolve("half-written.txt"), "still-writing");
            syncScannerService.scan();
            assertNull(findByName("half-written.txt"), "skip-recent 窗口内文件不得导入（防半写）");
            assertTrue(Files.exists(watch.resolve("half-written.txt")), "应保留源文件待下次扫描");
        } finally {
            sync.setSkipRecentSeconds(original);
        }
    }

    @Test
    void import_recursiveNestedDirectories() throws Exception {
        Path nested = watch.resolve("sub").resolve("deep");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("nested.txt"), "nested-content");

        syncScannerService.scan();

        FileInfo f = findByName("nested.txt");
        assertNotNull(f, "嵌套子目录文件应被递归导入");
        assertFalse(Files.exists(nested.resolve("nested.txt")), "源文件应迁出导入目录");
        assertTrue(Files.exists(root.resolve(f.getStoragePath())), "应迁入 files/ 统一存储");
    }

    @Test
    void scheduledScan_whenDisabled_skips() throws Exception {
        var sync = pathProperties.getSync();
        boolean original = sync.isEnabled();
        sync.setEnabled(false);
        try {
            Files.writeString(watch.resolve("disabled.txt"), "not imported");
            syncScannerService.scheduledScan();
            assertNull(findByName("disabled.txt"), "sync.enabled=false 时不应扫描导入");
        } finally {
            sync.setEnabled(original);
        }
    }

    private FileInfo seedReady(String rel, String name, String content) throws IOException {
        Path p = PathUtil.resolve(root, rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        FileInfo f = new FileInfo();
        f.setOriginalName(name);
        f.setFileName(Path.of(rel).getFileName().toString());
        f.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        f.setFileMd5(cn.chenxinjie.uploadfile.core.util.ChecksumUtil.md5(p.toFile()));
        f.setFileType(PathUtil.extension(name));
        f.setStoragePath(rel);
        f.setSpaceType("PUBLIC");
        f.setOwnerId(adminId);
        f.setCreatorId(adminId);
        f.setStatus("READY");
        f.setDiskStatus("READY");
        f.setDiskModifiedAt(LocalDateTime.now().minusMinutes(5));
        return fileInfoRepository.save(f);
    }

    private FileInfo reload(Long id) {
        return fileInfoRepository.findById(id).orElseThrow();
    }

    private FileInfo findByName(String name) {
        List<FileInfo> all = fileInfoRepository.findAll();
        return all.stream().filter(f -> name.equals(f.getOriginalName())).findFirst().orElse(null);
    }

    private long countByName(String name) {
        return fileInfoRepository.findAll().stream()
                .filter(f -> name.equals(f.getOriginalName())).count();
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                }
            });
        }
    }
}
