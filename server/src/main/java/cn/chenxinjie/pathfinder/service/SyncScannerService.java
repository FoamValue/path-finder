package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.util.PathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 目录同步扫描：定时扫描导入目录入库，并校验磁盘文件与库记录的一致性（设计稿 SYNC-directory-scan-design）。
 * 单线程执行，校验阶段只读，绝不修改/删除磁盘文件。
 */
@Service
public class SyncScannerService {

    private static final Logger log = LoggerFactory.getLogger(SyncScannerService.class);

    private static final int PAGE_SIZE = 500;
    private static final long MTIME_TOLERANCE_SECONDS = 1;

    private final PathProperties pathProperties;
    private final FileInfoRepository fileInfoRepository;
    private final UserRepository userRepository;
    private final LogService logService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AuthUser admin;

    public SyncScannerService(PathProperties pathProperties,
                              FileInfoRepository fileInfoRepository,
                              UserRepository userRepository,
                              LogService logService) {
        this.pathProperties = pathProperties;
        this.fileInfoRepository = fileInfoRepository;
        this.userRepository = userRepository;
        this.logService = logService;
    }

    /**
     * 定时入口（间隔可配置），防重入：上一轮未结束则跳过本次触发。
     */
    @Scheduled(fixedDelayString = "${pathfinder.sync.interval:5m}")
    @Transactional
    public void scheduledScan() {
        if (!pathProperties.getSync().isEnabled()) {
            return;
        }
        scan();
    }

    /**
     * 手动/测试入口：单线程执行一轮完整扫描。
     */
    public void scan() {
        if (!running.compareAndSet(false, true)) {
            log.info("目录同步扫描已在进行中，本次触发跳过");
            return;
        }
        try {
            int imported = importNewFiles();
            int changed = verifyAll();
            if (imported > 0 || changed > 0) {
                log.info("目录同步扫描完成：导入 {} 个，状态变更 {} 个", imported, changed);
            }
        } finally {
            running.set(false);
        }
    }

    /* ============ 阶段一：导入 ============ */

    private int importNewFiles() {
        Path watch = pathProperties.getSync().watchDirPath();
        if (!Files.isDirectory(watch)) {
            return 0;
        }
        int imported = 0;
        try (Stream<Path> paths = Files.walk(watch)) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            for (Path src : files) {
                try {
                    if (importOne(src)) {
                        imported++;
                    }
                } catch (Exception e) {
                    log.error("导入文件失败: {}", src, e);
                }
            }
        } catch (IOException e) {
            log.error("遍历导入目录失败: {}", watch, e);
        }
        return imported;
    }

    private boolean importOne(Path src) throws IOException {
        FileTime mtime = Files.getLastModifiedTime(src);
        long age = ChronoUnit.SECONDS.between(
                mtime.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(), LocalDateTime.now());
        if (age < pathProperties.getSync().getSkipRecentSeconds()) {
            return false;
        }
        String name = src.getFileName().toString();
        String md5 = md5(src);
        if (md5 == null) {
            return false;
        }
        if (pathProperties.getSync().isDedupByMd5()
                && !fileInfoRepository.findByDelFlagAndStatusAndFileMd5(0, "READY", md5).isEmpty()) {
            log.info("导入跳过（MD5 已存在）: {}", src);
            return false;
        }
        long size = src.toFile().length();
        String rel = PathUtil.relativeStorePath(name);
        Path target = PathUtil.resolve(pathProperties.getStorage().rootPath(), rel);
        Files.createDirectories(target.getParent());
        Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);

        FileInfo f = new FileInfo();
        f.setOriginalName(name);
        f.setFileName(target.getFileName().toString());
        f.setFileSize(size);
        f.setFileMd5(md5);
        f.setFileType(PathUtil.extension(name));
        f.setStoragePath(rel);
        f.setSpaceType("PUBLIC");
        f.setOwnerId(adminAuth().getId());
        f.setCreatorId(adminAuth().getId());
        f.setStatus("READY");
        f.setDiskStatus("READY");
        f.setDiskModifiedAt(toLocalDateTime(mtime));
        fileInfoRepository.save(f);
        logService.record(adminAuth(), "IMPORT", "FILE", String.valueOf(f.getId()), name, "目录导入", true);
        return true;
    }

    /* ============ 阶段二：校验（只读） ============ */

    private int verifyAll() {
        int changed = 0;
        int pageNo = 0;
        Page<FileInfo> page;
        do {
            page = fileInfoRepository.findByDelFlagAndStatus(0, "READY",
                    PageRequest.of(pageNo++, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            for (FileInfo f : page.getContent()) {
                try {
                    if (verifyOne(f)) {
                        changed++;
                    }
                } catch (Exception e) {
                    log.error("校验文件状态失败 fileId={}", f.getId(), e);
                }
            }
        } while (page.hasNext());
        return changed;
    }

    private boolean verifyOne(FileInfo f) throws IOException {
        Path physical = PathUtil.resolve(pathProperties.getStorage().rootPath(), f.getStoragePath());
        if (!Files.exists(physical)) {
            return markMissing(f);
        }
        FileTime mtime = Files.getLastModifiedTime(physical);
        long size = Files.size(physical);

        // MISSING 复活：文件重新出现
        if ("MISSING".equals(f.getDiskStatus())) {
            String md5 = md5(physical);
            if (md5 != null && md5.equals(f.getFileMd5())) {
                f.setDiskStatus("READY");
                f.setDiskModifiedAt(toLocalDateTime(mtime));
                fileInfoRepository.save(f);
                logService.record(adminAuth(), "SYNC_RESTORE", "FILE", String.valueOf(f.getId()),
                        f.getOriginalName(), "目录文件重新出现，状态复位", true);
                return true;
            }
            return markUpdated(f);
        }

        // 历史数据（功能上线前入库，无 mtime 基线）：建立基线，仅刷新不标记
        if (f.getDiskModifiedAt() == null) {
            f.setFileSize(size);
            String md5 = md5(physical);
            if (md5 != null) {
                f.setFileMd5(md5);
            }
            f.setDiskModifiedAt(toLocalDateTime(mtime));
            f.setDiskStatus("READY");
            fileInfoRepository.save(f);
            return false;
        }

        boolean sizeSame = f.getFileSize() != null && f.getFileSize() == size;
        boolean mtimeSame = Math.abs(ChronoUnit.SECONDS.between(f.getDiskModifiedAt(), toLocalDateTime(mtime)))
                <= MTIME_TOLERANCE_SECONDS;
        if (sizeSame && mtimeSame) {
            return false;
        }
        String md5 = md5(physical);
        if (md5 == null) {
            return false;
        }
        if (md5.equals(f.getFileMd5())) {
            // 内容未变（touch/属性变化）：刷新 mtime 基线，保持 READY
            f.setDiskModifiedAt(toLocalDateTime(mtime));
            f.setDiskStatus("READY");
            fileInfoRepository.save(f);
            return false;
        }
        return markUpdated(f);
    }

    private boolean markMissing(FileInfo f) {
        if ("MISSING".equals(f.getDiskStatus())) {
            return false;
        }
        f.setDiskStatus("MISSING");
        fileInfoRepository.save(f);
        logService.record(adminAuth(), "SYNC_MISSING", "FILE", String.valueOf(f.getId()),
                f.getOriginalName(), "目录文件已被删除", true);
        return true;
    }

    private boolean markUpdated(FileInfo f) {
        if ("UPDATED".equals(f.getDiskStatus())) {
            return false;
        }
        f.setDiskStatus("UPDATED");
        fileInfoRepository.save(f);
        logService.record(adminAuth(), "SYNC_UPDATED", "FILE", String.valueOf(f.getId()),
                f.getOriginalName(), "源文件已被更新", true);
        return true;
    }

    /* ============ 工具 ============ */

    private AuthUser adminAuth() {
        AuthUser cached = admin;
        if (cached == null) {
            User u = userRepository.findByUsernameAndDelFlag("admin", 0)
                    .orElseThrow(() -> new IllegalStateException("admin 用户不存在，无法执行目录导入"));
            cached = new AuthUser(u.getId(), u.getUsername(), u.getRealName(), "ADMIN", u.getDeptId(),
                    u.getMustChangePassword());
            admin = cached;
        }
        return cached;
    }

    private String md5(Path p) {
        try {
            return cn.chenxinjie.uploadfile.core.util.ChecksumUtil.md5(p.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(FileTime ft) {
        return LocalDateTime.ofInstant(ft.toInstant(), ZoneId.systemDefault());
    }
}
