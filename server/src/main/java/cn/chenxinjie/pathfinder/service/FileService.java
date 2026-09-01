package cn.chenxinjie.pathfinder.service;

import tools.jackson.databind.ObjectMapper;
import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.entity.FileRecycleBin;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.FileRecycleBinRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.util.PathUtil;
import cn.chenxinjie.pathfinder.util.RedisTtlPolicy;
import jakarta.persistence.criteria.Predicate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文件核心服务：数据权限过滤、上传确认、重命名、软删除、归属变更、回收站、批量下载（PRD F3/F4/F5/F9）。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    public static final int RECYCLE_DAYS = 30;
    public static final int MAX_ZIP_COUNT = 100;

    private final FileInfoRepository fileInfoRepository;
    private final FileRecycleBinRepository recycleBinRepository;
    private final DeptService deptService;
    private final PathProperties pathProperties;
    private final RedisTtlPolicy ttl;
    private final LogService logService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    public FileService(FileInfoRepository fileInfoRepository,
                       FileRecycleBinRepository recycleBinRepository,
                       DeptService deptService,
                       PathProperties pathProperties,
                       RedisTtlPolicy ttl,
                       LogService logService,
                       ObjectMapper objectMapper,
                       UserRepository userRepository) {
        this.fileInfoRepository = fileInfoRepository;
        this.recycleBinRepository = recycleBinRepository;
        this.deptService = deptService;
        this.pathProperties = pathProperties;
        this.ttl = ttl;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    @Data
    public static class UploadTicket {
        private String identifier;
        private Long fileId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerChangeForm {
        private String spaceType;
        private Long deptId;
        private Long ownerId;
    }

    @Data
    public static class FileVo {
        private Long id;
        private String originalName;
        private Long fileSize;
        private String fileMd5;
        private String fileType;
        private String spaceType;
        private Long deptId;
        private Long ownerId;
        private String ownerName;
        private Long creatorId;
        private String creatorName;
        private String status;
        private String diskStatus;
        private LocalDateTime createdAt;
    }

    public FileInfo getFile(Long id) {
        return fileInfoRepository.findById(id)
                .filter(f -> f.getDelFlag() == 0)
                .orElseThrow(() -> BizException.notFound("文件不存在"));
    }

    /* ============ 数据权限 ============ */

    private boolean canView(FileInfo f, AuthUser user) {
        if (user.isAdmin()) {
            return true;
        }
        return switch (f.getSpaceType()) {
            case "PUBLIC" -> true;
            case "PERSONAL" -> f.getOwnerId().equals(user.getId());
            case "DEPT" -> {
                Set<Long> v = deptService.visibleDeptIds(user);
                yield v != null && v.contains(f.getDeptId());
            }
            default -> false;
        };
    }

    private void assertCanView(FileInfo f, AuthUser user) {
        if (!canView(f, user)) {
            throw BizException.forbidden("无权访问该文件");
        }
    }

    private boolean canOperate(FileInfo f, AuthUser user) {
        if (user.isAdmin() || f.getOwnerId().equals(user.getId())) {
            return true;
        }
        if (user.isDeptAdmin()) {
            Set<Long> v = deptService.visibleDeptIds(user);
            return "DEPT".equals(f.getSpaceType()) && v != null && v.contains(f.getDeptId());
        }
        return false;
    }

    private void assertCanOperate(FileInfo f, AuthUser user) {
        if (!canOperate(f, user)) {
            throw BizException.forbidden("无权操作该文件");
        }
    }

    /* ============ 列表（真分页 + 数据权限） ============ */

    public PageResult<FileVo> page(AuthUser user, String spaceType, Long deptId, String keyword,
                                   int pageNum, int pageSize) {
        Specification<FileInfo> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("delFlag"), 0));
            ps.add(cb.equal(root.get("status"), "READY"));
            if (spaceType != null && !spaceType.isBlank()) {
                ps.add(cb.equal(root.get("spaceType"), spaceType));
            }
            if (deptId != null) {
                ps.add(cb.equal(root.get("deptId"), deptId));
            }
            if (keyword != null && !keyword.isBlank()) {
                ps.add(cb.like(root.get("originalName"), "%" + keyword + "%"));
            }
            Set<Long> v = deptService.visibleDeptIds(user);
            if (v != null) {
                ps.add(cb.or(
                        cb.equal(root.get("spaceType"), "PUBLIC"),
                        cb.and(cb.equal(root.get("spaceType"), "DEPT"),
                                root.get("deptId").in(v)),
                        cb.and(cb.equal(root.get("spaceType"), "PERSONAL"),
                                cb.equal(root.get("ownerId"), user.getId()))));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<FileInfo> page = fileInfoRepository.findAll(spec,
                PageRequest.of(pageNum - 1, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResult.of(page.map(this::toVo), pageNum, pageSize);
    }

    private FileVo toVo(FileInfo f) {
        FileVo vo = new FileVo();
        vo.setId(f.getId());
        vo.setOriginalName(f.getOriginalName());
        vo.setFileSize(f.getFileSize());
        vo.setFileMd5(f.getFileMd5());
        vo.setFileType(f.getFileType());
        vo.setSpaceType(f.getSpaceType());
        vo.setDeptId(f.getDeptId());
        vo.setOwnerId(f.getOwnerId());
        vo.setCreatorId(f.getCreatorId());
        vo.setStatus(f.getStatus());
        vo.setDiskStatus(f.getDiskStatus());
        vo.setCreatedAt(f.getCreatedAt());
        try {
            vo.setOwnerName(userName(f.getOwnerId()));
        } catch (Exception ignore) {
        }
        try {
            vo.setCreatorName(userName(f.getCreatorId()));
        } catch (Exception ignore) {
        }
        return vo;
    }

    private String userName(Long id) {
        return userRepository.findById(id).map(User::getRealName).orElse(null);
    }

    public FileVo meta(Long id, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanView(f, user);
        return toVo(f);
    }

    /* ============ 上传 ============ */

    @Transactional
    public UploadTicket uploadTicket(String fileName, Long fileSize, String spaceType, Long deptId, AuthUser user) {
        if (spaceType == null || !Set.of("PERSONAL", "DEPT", "PUBLIC").contains(spaceType)) {
            throw BizException.badRequest("非法的空间类型");
        }
        if ("DEPT".equals(spaceType)) {
            if (deptId == null) {
                throw BizException.badRequest("部门空间必须指定部门");
            }
            deptService.get(deptId);
        }
        String identifier = PathUtil.uuid();
        String rel = PathUtil.relativeStorePath(fileName);
        FileInfo f = new FileInfo();
        f.setOriginalName(fileName);
        f.setFileName(Path.of(rel).getFileName().toString());
        f.setFileSize(fileSize == null ? 0 : fileSize);
        f.setFileType(PathUtil.extension(fileName));
        f.setStoragePath(rel);
        f.setSpaceType(spaceType);
        f.setDeptId("DEPT".equals(spaceType) ? deptId : null);
        f.setOwnerId(user.getId());
        f.setCreatorId(user.getId());
        f.setStatus("UPLOADING");
        f.setUploadIdentifier(identifier);
        FileInfo saved = fileInfoRepository.save(f);
        UploadTicket t = new UploadTicket();
        t.setIdentifier(identifier);
        t.setFileId(saved.getId());
        return t;
    }

    /**
     * 合并确认：按组件产物路径规则定位合并文件 → 迁移至统一存储 → 回填 MD5 → READY（TSDD 6.3）。
     */
    @Transactional
    public void confirm(Long fileId, AuthUser user) {
        FileInfo f = getFile(fileId);
        if (!user.isAdmin() && !f.getCreatorId().equals(user.getId())) {
            throw BizException.forbidden("仅上传者可确认文件");
        }
        if ("READY".equals(f.getStatus())) {
            return;
        }
        Path merged = resolveMergedPath(f.getUploadIdentifier(), f.getOriginalName());
        if (!Files.exists(merged)) {
            throw BizException.badRequest("合并产物不存在，请确认已合并完成（mergeStatus=SUCCEEDED）");
        }
        String rel = PathUtil.relativeStorePath(f.getOriginalName());
        Path target = PathUtil.resolve(pathProperties.getStorage().rootPath(), rel);
        try {
            Files.createDirectories(target.getParent());
            Files.move(merged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("confirm move failed fileId={}", fileId, e);
            throw BizException.badRequest("文件迁移失败：" + e.getMessage());
        }
        f.setStoragePath(rel);
        f.setFileName(target.getFileName().toString());
        f.setFileSize(f.getFileSize() == 0 ? target.toFile().length() : f.getFileSize());
        f.setFileMd5(md5(target));
        f.setStatus("READY");
        f.setDiskStatus("READY");
        f.setDiskModifiedAt(diskModifiedAt(target));
        fileInfoRepository.save(f);
        logService.record(user, "UPLOAD", "FILE", String.valueOf(fileId), f.getOriginalName(), "上传完成", true);
    }

    private LocalDateTime diskModifiedAt(Path p) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), java.time.ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.now();
        }
    }

    private Path resolveMergedPath(String identifier, String originalName) {
        return pathProperties.getStorage().uploadPath().resolve("files")
                .resolve(identifier).resolve(originalName);
    }

    private String md5(Path p) {
        try {
            return cn.chenxinjie.uploadfile.core.util.ChecksumUtil.md5(p.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    /* ============ 重命名 / 归属变更 / 软删除 ============ */

    @Transactional
    public void rename(Long id, String newName, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanOperate(f, user);
        if (newName == null || newName.isBlank() || newName.length() > 255) {
            throw BizException.badRequest("文件名不合法");
        }
        String old = f.getOriginalName();
        f.setOriginalName(newName);
        f.setFileType(PathUtil.extension(newName));
        fileInfoRepository.save(f);
        logService.record(user, "RENAME", "FILE", String.valueOf(id), newName, "重命名：" + old + " → " + newName, true);
    }

    @Transactional
    public void ownerChange(Long id, OwnerChangeForm form, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanOperate(f, user);
        if (f.getStatus().equals("UPLOADING")) {
            throw BizException.badRequest("文件上传/合并中，请稍后再试");
        }
        if (form.getSpaceType() == null || !Set.of("PERSONAL", "DEPT", "PUBLIC").contains(form.getSpaceType())) {
            throw BizException.badRequest("非法的目标空间类型");
        }
        String oldDetail = "space=" + f.getSpaceType() + ",dept=" + f.getDeptId() + ",owner=" + f.getOwnerId();
        f.setSpaceType(form.getSpaceType());
        f.setDeptId("DEPT".equals(form.getSpaceType()) ? form.getDeptId() : null);
        if ("DEPT".equals(form.getSpaceType())) {
            if (form.getDeptId() == null) {
                throw BizException.badRequest("部门空间必须指定目标部门");
            }
            deptService.get(form.getDeptId());
        }
        if (form.getOwnerId() != null) {
            // 移交归属人（仅所有者/部门管理员/系统管理员，已在 assertCanOperate 校验）
            f.setOwnerId(form.getOwnerId());
        }
        fileInfoRepository.save(f);
        String newDetail = "space=" + f.getSpaceType() + ",dept=" + f.getDeptId() + ",owner=" + f.getOwnerId();
        logService.record(user, "OWNER_CHANGE", "FILE", String.valueOf(id), f.getOriginalName(),
                "归属变更：" + oldDetail + " → " + newDetail, true);
    }

    @Transactional
    public void softDelete(Long id, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanOperate(f, user);
        // 物理文件移入 del/
        Path src = physicalPath(f);
        Path del = pathProperties.getStorage().delPath().resolve(f.getStoragePath());
        if (Files.exists(src)) {
            try {
                Files.createDirectories(del.getParent());
                Files.move(src, del, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("soft delete move failed fileId={}", id, e);
                throw BizException.badRequest("文件移入回收站失败");
            }
        }
        f.setDelFlag(1);
        f.setDelAt(LocalDateTime.now());
        fileInfoRepository.save(f);
        FileRecycleBin rb = new FileRecycleBin();
        rb.setFileId(id);
        rb.setDeletedBy(user.getId());
        rb.setDeletedAt(LocalDateTime.now());
        rb.setExpireAt(LocalDateTime.now().plusDays(RECYCLE_DAYS));
        recycleBinRepository.save(rb);
        logService.record(user, "DELETE", "FILE", String.valueOf(id), f.getOriginalName(), "软删除，进入回收站", true);
    }

    private Path physicalPath(FileInfo f) {
        return PathUtil.resolve(pathProperties.getStorage().rootPath(), f.getStoragePath());
    }

    /* ============ 回收站 ============ */

    /**
     * 回收站分页（数据权限）：仅返回当前用户可见范围内的文件回收记录。
     */
    public PageResult<RecycleVo> recyclePage(AuthUser user, int pageNum, int pageSize) {
        List<FileRecycleBin> all = recycleBinRepository.findAll(
                Sort.by(Sort.Direction.DESC, "deletedAt"));
        List<RecycleVo> visible = all.stream()
                .filter(rb -> fileInfoRepository.findById(rb.getFileId())
                        .map(f -> canView(f, user))
                        .orElse(false))
                .map(rb -> {
                    RecycleVo vo = new RecycleVo();
                    vo.setId(rb.getId());
                    vo.setFileId(rb.getFileId());
                    vo.setDeletedBy(rb.getDeletedBy());
                    vo.setDeletedAt(rb.getDeletedAt());
                    vo.setExpireAt(rb.getExpireAt());
                    fileInfoRepository.findById(rb.getFileId()).ifPresent(f -> {
                        vo.setOriginalName(f.getOriginalName());
                        vo.setFileType(f.getFileType());
                        vo.setFileSize(f.getFileSize());
                        vo.setSpaceType(f.getSpaceType());
                    });
                    return vo;
                })
                .toList();
        int from = Math.min((pageNum - 1) * pageSize, visible.size());
        int to = Math.min(from + pageSize, visible.size());
        return PageResult.of(visible.subList(from, to), visible.size(), pageNum, pageSize);
    }

    @Data
    public static class RecycleVo {
        private Long id;
        private Long fileId;
        private Long deletedBy;
        private LocalDateTime deletedAt;
        private LocalDateTime expireAt;
        private String originalName;
        private String fileType;
        private Long fileSize;
        private String spaceType;
    }

    @Transactional
    public void restore(Long fileId, AuthUser user) {
        FileRecycleBin rb = recycleBinRepository.findByFileId(fileId)
                .orElseThrow(() -> BizException.notFound("回收站记录不存在"));
        FileInfo f = fileInfoRepository.findById(fileId).orElseThrow(() -> BizException.notFound("文件不存在"));
        if (!user.isAdmin() && !f.getOwnerId().equals(user.getId()) && !"DEPT".equals(f.getSpaceType())) {
            throw BizException.forbidden("无权恢复该文件");
        }
        // del/ → files/ 迁回
        Path del = pathProperties.getStorage().delPath().resolve(f.getStoragePath());
        Path target = PathUtil.resolve(pathProperties.getStorage().rootPath(), f.getStoragePath());
        if (Files.exists(del)) {
            try {
                Files.createDirectories(target.getParent());
                Files.move(del, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw BizException.badRequest("恢复文件失败");
            }
        }
        f.setDelFlag(0);
        f.setDelAt(null);
        fileInfoRepository.save(f);
        recycleBinRepository.delete(rb);
        logService.record(user, "RESTORE", "FILE", String.valueOf(fileId), f.getOriginalName(), "从回收站恢复", true);
    }

    @Transactional
    public void purge(Long fileId, AuthUser user) {
        if (!user.isAdmin()) {
            throw BizException.forbidden("仅系统管理员可物理清除");
        }
        FileRecycleBin rb = recycleBinRepository.findByFileId(fileId)
                .orElseThrow(() -> BizException.notFound("回收站记录不存在"));
        FileInfo f = fileInfoRepository.findById(fileId).orElse(null);
        if (f != null) {
            Path del = pathProperties.getStorage().delPath().resolve(f.getStoragePath());
            try {
                Files.deleteIfExists(del);
            } catch (IOException ignore) {
            }
            fileInfoRepository.delete(f);
        }
        recycleBinRepository.delete(rb);
        logService.record(user, "PURGE", "FILE", String.valueOf(fileId), f == null ? "" : f.getOriginalName(),
                "物理清除", true);
    }

    /* ============ 批量下载（ZIP） ============ */

    @Data
    public static class DownloadTicket {
        private String token;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DownloadTarget {
        private String mode;      // single | zip
        private Long fileId;
        private String relPath;   // zip 时相对 tmp 的路径
        private String fileName;
    }

    public DownloadTicket batchDownload(List<Long> ids, AuthUser user) throws IOException {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("请选择文件");
        }
        if (ids.size() > MAX_ZIP_COUNT) {
            throw BizException.badRequest("单次最多下载 " + MAX_ZIP_COUNT + " 个文件");
        }
        List<FileInfo> files = new ArrayList<>();
        for (Long id : ids) {
            FileInfo f = getFile(id);
            assertCanView(f, user);
            files.add(f);
        }
        String zipName = UUID.randomUUID().toString().replace("-", "") + ".zip";
        Path tmp = pathProperties.getStorage().tmpPath().resolve(zipName);
        Files.createDirectories(tmp.getParent());
        try (OutputStream os = Files.newOutputStream(tmp);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            Set<String> used = new java.util.HashSet<>();
            for (FileInfo f : files) {
                Path p = physicalPath(f);
                if (!Files.exists(p)) {
                    continue;
                }
                String entryName = f.getOriginalName();
                int i = 1;
                while (!used.add(entryName)) {
                    entryName = PathUtil.uniqueName(f.getOriginalName(), i++);
                }
                zos.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = Files.newInputStream(p)) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
        logService.record(user, "DOWNLOAD", "FILE", null, "ZIP 批量下载", "共 " + files.size() + " 个文件", true);
        DownloadTarget target = new DownloadTarget("zip", null, tmp.toString(), zipName);
        return createToken(target);
    }

    public DownloadTicket singleDownloadToken(Long id, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanView(f, user);
        DownloadTarget target = new DownloadTarget("single", id, null, f.getOriginalName());
        logService.record(user, "DOWNLOAD", "FILE", String.valueOf(id), f.getOriginalName(), "下载", true);
        return createToken(target);
    }

    private DownloadTicket createToken(DownloadTarget target) {
        try {
            String token = PathUtil.uuid();
            String key = "download:token:" + token;
            ttl.setWithExplicitTtl(key, objectMapper.writeValueAsString(target), 600, false);
            DownloadTicket t = new DownloadTicket();
            t.setToken(token);
            return t;
        } catch (Exception e) {
            throw new RuntimeException("生成下载令牌失败", e);
        }
    }

    public DownloadTarget consumeToken(String token) {
        String key = "download:token:" + token;
        String json = ttl.get(key);
        if (json == null) {
            throw BizException.notFound("下载令牌不存在或已过期");
        }
        ttl.delete(key);
        try {
            return objectMapper.readValue(json, DownloadTarget.class);
        } catch (Exception e) {
            throw BizException.badRequest("下载令牌解析失败");
        }
    }

    public Path resolveDownloadPath(DownloadTarget target) {
        if ("zip".equals(target.getMode())) {
            return Path.of(target.getRelPath());
        }
        FileInfo f = getFile(target.getFileId());
        if ("MISSING".equals(f.getDiskStatus())) {
            throw BizException.notFound("目录文件已经被删除");
        }
        return physicalPath(f);
    }

    /**
     * 下载完成后刷新：UPDATED 文件放行下载新版后，复位 READY 并刷新磁盘基线。
     */
    @Transactional
    public void refreshAfterDownload(Long fileId) {
        FileInfo f = getFile(fileId);
        if (!"UPDATED".equals(f.getDiskStatus())) {
            return;
        }
        Path p = physicalPath(f);
        if (!Files.exists(p)) {
            return;
        }
        f.setFileSize(p.toFile().length());
        f.setFileMd5(md5(p));
        f.setDiskModifiedAt(diskModifiedAt(p));
        f.setDiskStatus("READY");
        fileInfoRepository.save(f);
        logService.record(null, "SYNC_REFRESH", "FILE", String.valueOf(fileId), f.getOriginalName(),
                "下载新版，磁盘状态复位 READY", true);
    }
}
