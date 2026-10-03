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
import cn.chenxinjie.pathfinder.util.SqlLike;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.TrustedUploadService;
import jakarta.persistence.criteria.Predicate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final OrgService orgService;
    private final PathProperties pathProperties;
    private final RedisTtlPolicy ttl;
    private final LogService logService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ResumableUploadService uploadService;
    private final TrustedUploadService trustedUploadService;

    public FileService(FileInfoRepository fileInfoRepository,
                       FileRecycleBinRepository recycleBinRepository,
                       OrgService orgService,
                       PathProperties pathProperties,
                       RedisTtlPolicy ttl,
                       LogService logService,
                       ObjectMapper objectMapper,
                       UserRepository userRepository,
                       ResumableUploadService uploadService,
                       TrustedUploadService trustedUploadService) {
        this.fileInfoRepository = fileInfoRepository;
        this.recycleBinRepository = recycleBinRepository;
        this.orgService = orgService;
        this.pathProperties = pathProperties;
        this.ttl = ttl;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.uploadService = uploadService;
        this.trustedUploadService = trustedUploadService;
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
        private Long orgId;
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
        private Long orgId;
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
            case "ORG" -> {
                Set<Long> v = orgService.visibleOrgIds(user);
                yield v != null && v.contains(f.getOrgId());
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
        if (user.isOrgAdmin()) {
            Set<Long> v = orgService.visibleOrgIds(user);
            return "ORG".equals(f.getSpaceType()) && v != null && v.contains(f.getOrgId());
        }
        return false;
    }

    private void assertCanOperate(FileInfo f, AuthUser user) {
        if (!canOperate(f, user)) {
            throw BizException.forbidden("无权操作该文件");
        }
    }

    /* ============ 列表（真分页 + 数据权限） ============ */

    public PageResult<FileVo> page(AuthUser user, String spaceType, Long orgId, String keyword,
                                   int pageNum, int pageSize) {
        Specification<FileInfo> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("delFlag"), 0));
            ps.add(cb.equal(root.get("status"), "READY"));
            if (spaceType != null && !spaceType.isBlank()) {
                ps.add(cb.equal(root.get("spaceType"), spaceType));
            }
            if (orgId != null) {
                ps.add(cb.equal(root.get("orgId"), orgId));
            }
            if (keyword != null && !keyword.isBlank()) {
                ps.add(cb.like(root.get("originalName"),
                        SqlLike.containsPattern(keyword), '\\'));
            }
            Set<Long> v = orgService.visibleOrgIds(user);
            if (v != null) {
                ps.add(cb.or(
                        cb.equal(root.get("spaceType"), "PUBLIC"),
                        cb.and(cb.equal(root.get("spaceType"), "ORG"),
                                root.get("orgId").in(v)),
                        cb.and(cb.equal(root.get("spaceType"), "PERSONAL"),
                                cb.equal(root.get("ownerId"), user.getId()))));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<FileInfo> page = fileInfoRepository.findAll(spec,
                PageRequest.of(pageNum - 1, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<Long, String> userNames = userNames(page.getContent());
        return PageResult.of(page.map(f -> toVo(f, userNames)), pageNum, pageSize);
    }

    private FileVo toVo(FileInfo f, Map<Long, String> userNames) {
        FileVo vo = new FileVo();
        vo.setId(f.getId());
        vo.setOriginalName(f.getOriginalName());
        vo.setFileSize(f.getFileSize());
        vo.setFileMd5(f.getFileMd5());
        vo.setFileType(f.getFileType());
        vo.setSpaceType(f.getSpaceType());
        vo.setOrgId(f.getOrgId());
        vo.setOwnerId(f.getOwnerId());
        vo.setCreatorId(f.getCreatorId());
        vo.setStatus(f.getStatus());
        vo.setDiskStatus(f.getDiskStatus());
        vo.setCreatedAt(f.getCreatedAt());
        vo.setOwnerName(userNames.get(f.getOwnerId()));
        vo.setCreatorName(userNames.get(f.getCreatorId()));
        return vo;
    }

    /**
     * 批量取本页文件涉及的归属人/创建人姓名（一次 IN 查询），替代逐行 {@code findById} 的 N+1。
     */
    private Map<Long, String> userNames(List<FileInfo> files) {
        Set<Long> ids = new HashSet<>();
        for (FileInfo f : files) {
            if (f.getOwnerId() != null) {
                ids.add(f.getOwnerId());
            }
            if (f.getCreatorId() != null) {
                ids.add(f.getCreatorId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            names.put(u.getId(), u.getRealName());
        }
        return names;
    }

    public FileVo meta(Long id, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanView(f, user);
        return toVo(f, userNames(List.of(f)));
    }

    /* ============ 上传 ============ */

    @Transactional
    public UploadTicket uploadTicket(String fileName, Long fileSize, String spaceType, Long orgId, AuthUser user) {
        // 文件名校验：非空 + 长度上限，避免 PathUtil.extension(null) NPE 与 original_name(n=255) 约束 500
        if (fileName == null || fileName.isBlank() || fileName.length() > 255) {
            throw BizException.badRequest("文件名不合法");
        }
        if (spaceType == null || !Set.of("PERSONAL", "ORG", "PUBLIC").contains(spaceType)) {
            throw BizException.badRequest("非法的空间类型");
        }
        if ("ORG".equals(spaceType)) {
            if (orgId == null) {
                throw BizException.badRequest("组织空间必须指定组织");
            }
            orgService.get(orgId);
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
        f.setOrgId("ORG".equals(spaceType) ? orgId : null);
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
     * 合并确认：按组件 rc.4 稳定读接口 getTask().finalPath 定位合并产物 → 迁移至统一存储 →
     * 回填 MD5 → READY；入库后调用 cancelUpload 显式回收组件侧任务/残留（TSDD 6.3 / G3 冻结方案）。
     *
     * <p>A2 优化：文件迁移与整文件 MD5（可能数百 MB）在事务外执行，避免长事务占用 DB 连接；
     * DB 更新由单条 {@code save} 自身事务提交，<b>提交成功后</b>才回收组件任务，杜绝「任务已删、
     * DB 回滚」导致的产物与记录不一致。</p>
     */
    public void confirm(Long fileId, AuthUser user) {
        FileInfo f = getFile(fileId);
        if (!user.isAdmin() && !f.getCreatorId().equals(user.getId())) {
            throw BizException.forbidden("仅上传者可确认文件");
        }
        if ("READY".equals(f.getStatus())) {
            return;
        }
        Path merged = resolveMergedProduct(f);
        if (merged == null || !Files.exists(merged) || Files.isDirectory(merged)) {
            throw BizException.badRequest("合并产物不存在，请确认已合并完成（mergeStatus=SUCCEEDED）后重试");
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
        // 事务外读盘：MD5 与磁盘基线（大文件不占用 DB 连接）
        String fileMd5 = md5(target);
        long fileSize = f.getFileSize() == 0 ? target.toFile().length() : f.getFileSize();
        LocalDateTime diskModifiedAt = diskModifiedAt(target);
        String identifier = f.getUploadIdentifier();

        f.setStoragePath(rel);
        f.setFileName(target.getFileName().toString());
        f.setFileSize(fileSize);
        f.setFileMd5(fileMd5);
        f.setStatus("READY");
        f.setDiskStatus("READY");
        f.setDiskModifiedAt(diskModifiedAt);
        fileInfoRepository.save(f);
        // 提交后再回收组件任务/残留（避免 DB 回滚而任务已删）
        cleanupUploadTask(identifier);
        logService.record(user, "UPLOAD", "FILE", String.valueOf(fileId), f.getOriginalName(), "上传完成", true);
    }

    /**
     * 合并产物定位（rc.4；rc.7 改用受信读门面）：优先使用组件任务元数据的 finalPath（稳定读接口
     * {@link TrustedUploadService#getTask(String)}，显式声明无访问门控的可信服务端读），
     * 元数据不可达时回退到旧版目录约定，兼容历史未清理任务。
     */
    private Path resolveMergedProduct(FileInfo f) {
        String identifier = f.getUploadIdentifier();
        if (identifier != null) {
            Optional<UploadTask> task = trustedUploadService.getTask(identifier);
            if (task.isPresent() && task.get().getFinalPath() != null && !task.get().getFinalPath().isBlank()) {
                Path p = Path.of(task.get().getFinalPath());
                if (Files.exists(p)) {
                    return p;
                }
            }
        }
        return legacyMergedPath(identifier, f.getOriginalName());
    }

    private Path legacyMergedPath(String identifier, String originalName) {
        if (identifier == null) {
            return null;
        }
        Path p = pathProperties.getStorage().uploadPath().resolve("files")
                .resolve(identifier).resolve(originalName);
        return Files.exists(p) ? p : null;
    }

    /**
     * confirm 入库后显式回收组件任务：删除任务元数据与 identifier 目录残留（分片已由合并清理）。
     */
    private void cleanupUploadTask(String identifier) {
        if (identifier == null) {
            return;
        }
        try {
            uploadService.cancelUpload(identifier);
        } catch (Exception e) {
            log.warn("confirm 后清理上传任务失败 identifier={}（交由 TTL/调度兜底）", identifier, e);
        }
    }

    private LocalDateTime diskModifiedAt(Path p) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), java.time.ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.now();
        }
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
        if (form.getSpaceType() == null || !Set.of("PERSONAL", "ORG", "PUBLIC").contains(form.getSpaceType())) {
            throw BizException.badRequest("非法的目标空间类型");
        }
        // M1 保守收紧：目标归属人必须存在
        if (form.getOwnerId() != null) {
            userRepository.findById(form.getOwnerId())
                    .orElseThrow(() -> BizException.notFound("目标归属用户不存在"));
        }
        if ("ORG".equals(form.getSpaceType())) {
            if (form.getOrgId() == null) {
                throw BizException.badRequest("组织空间必须指定目标组织");
            }
            orgService.get(form.getOrgId());
        }
        if (!user.isAdmin()) {
            // M1：非系统管理员不得把文件公开化，也不得把个人空间文件提权为组织空间
            if ("PUBLIC".equals(form.getSpaceType())
                    || ("ORG".equals(form.getSpaceType()) && "PERSONAL".equals(f.getSpaceType()))) {
                throw BizException.forbidden("无权将文件公开化或提升为组织空间");
            }
            // M1：目标组织必须在操作者可见组织范围内，防止注入不可见组织
            Set<Long> v = orgService.visibleOrgIds(user);
            if ("ORG".equals(form.getSpaceType())
                    && (v == null || !v.contains(form.getOrgId()))) {
                throw BizException.forbidden("目标组织不在你的可见范围内");
            }
            // M1：目标归属用户需在操作者可见组织范围内（跨不可见组织移交被拒绝）
            if (form.getOwnerId() != null && !form.getOwnerId().equals(f.getOwnerId())) {
                User target = userRepository.findById(form.getOwnerId()).orElse(null);
                Long tOrg = target == null ? null : target.getOrgId();
                if (tOrg == null || v == null || !v.contains(tOrg)) {
                    throw BizException.forbidden("不能将文件移交给可见范围外的用户");
                }
            }
        }
        String oldDetail = "space=" + f.getSpaceType() + ",org=" + f.getOrgId() + ",owner=" + f.getOwnerId();
        f.setSpaceType(form.getSpaceType());
        f.setOrgId("ORG".equals(form.getSpaceType()) ? form.getOrgId() : null);
        if ("ORG".equals(form.getSpaceType())) {
            if (form.getOrgId() == null) {
                throw BizException.badRequest("组织空间必须指定目标组织");
            }
            orgService.get(form.getOrgId());
        }
        if (form.getOwnerId() != null) {
            // 移交归属人（仅所有者/组织管理员/系统管理员，已在 assertCanOperate 校验）
            f.setOwnerId(form.getOwnerId());
        }
        fileInfoRepository.save(f);
        String newDetail = "space=" + f.getSpaceType() + ",org=" + f.getOrgId() + ",owner=" + f.getOwnerId();
        logService.record(user, "OWNER_CHANGE", "FILE", String.valueOf(id), f.getOriginalName(),
                "归属变更：" + oldDetail + " → " + newDetail, true);
    }

    /* ============ 文件批量操作（逐条鉴权，跳过无权限项） ============ */

    @Transactional
    public BatchResult batchOwnerChange(List<Long> ids, OwnerChangeForm form, AuthUser user) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("请选择文件");
        }
        if (form.getSpaceType() == null || !Set.of("PERSONAL", "ORG", "PUBLIC").contains(form.getSpaceType())) {
            throw BizException.badRequest("非法的目标空间类型");
        }
        if ("ORG".equals(form.getSpaceType())) {
            if (form.getOrgId() == null) {
                throw BizException.badRequest("组织空间必须指定目标组织");
            }
            orgService.get(form.getOrgId());
        }
        int ok = 0;
        int fail = 0;
        for (Long id : ids) {
            try {
                ownerChange(id, form, user);
                ok++;
            } catch (Exception ignore) {
                fail++;
            }
        }
        return new BatchResult(ok, fail, "归属变更成功 " + ok + " 个，失败 " + fail + " 个");
    }

    @Transactional
    public BatchResult batchDelete(List<Long> ids, AuthUser user) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("请选择文件");
        }
        int ok = 0;
        int fail = 0;
        for (Long id : ids) {
            try {
                softDelete(id, user);
                ok++;
            } catch (Exception ignore) {
                fail++;
            }
        }
        return new BatchResult(ok, fail, "删除成功 " + ok + " 个，失败 " + fail + " 个");
    }

    @Transactional
    public void softDelete(Long id, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanOperate(f, user);
        Path src = physicalPath(f);
        Path del = pathProperties.getStorage().delPath().resolve(f.getStoragePath());
        boolean exists = Files.exists(src);
        f.setDelFlag(1);
        f.setDelAt(LocalDateTime.now());
        fileInfoRepository.save(f);
        FileRecycleBin rb = new FileRecycleBin();
        rb.setFileId(id);
        rb.setDeletedBy(user.getId());
        rb.setDeletedAt(LocalDateTime.now());
        rb.setExpireAt(LocalDateTime.now().plusDays(RECYCLE_DAYS));
        recycleBinRepository.save(rb);
        // 物理迁移放到 DB 提交后，避免回滚时文件已被移走造成记录与产物不一致
        if (exists) {
            afterCommit(() -> moveQuietly(src, del));
        }
        logService.record(user, "DELETE", "FILE", String.valueOf(id), f.getOriginalName(), "软删除，进入回收站", true);
    }

    private Path physicalPath(FileInfo f) {
        return PathUtil.resolve(pathProperties.getStorage().rootPath(), f.getStoragePath());
    }

    /**
     * 在事务提交后执行动作；若无活动事务（如单元测试直调）则立即执行，保证行为一致。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 提交后物理迁移；失败仅记录日志，不把文件系统的偶发错误回抛给请求方。 */
    private void moveQuietly(Path src, Path dst) {
        try {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("post-commit file move failed src={} dst={}", src, dst, e);
        }
    }

    /* ============ 回收站 ============ */

    /**
     * 回收站分页（数据权限）：仅返回当前用户可见范围内的文件回收记录。
     *
     * <p>B1 优化：由数据库层分页（JOIN file_info + 可见性谓词）替代「全表加载 + 逐条 findById」，
     * 并按页批量取文件信息，消除 N+1 与无界内存占用。</p>
     */
    public PageResult<RecycleVo> recyclePage(AuthUser user, int pageNum, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(0, pageNum - 1),
                Math.min(Math.max(1, pageSize), 100), Sort.by(Sort.Direction.DESC, "deletedAt"));
        Page<FileRecycleBin> page;
        if (user.isAdmin()) {
            page = recycleBinRepository.pageWithFile(pageable);
        } else {
            Set<Long> orgIds = orgService.visibleOrgIds(user);
            page = recycleBinRepository.pageVisibleTo(user.getId(),
                    (orgIds == null || orgIds.isEmpty()) ? Set.of(-1L) : orgIds, pageable);
        }
        List<FileRecycleBin> records = page.getContent();
        Map<Long, FileInfo> fileMap = new HashMap<>();
        if (!records.isEmpty()) {
            List<Long> fileIds = records.stream().map(FileRecycleBin::getFileId).toList();
            for (FileInfo f : fileInfoRepository.findAllById(fileIds)) {
                fileMap.put(f.getId(), f);
            }
        }
        List<RecycleVo> list = records.stream()
                .map(rb -> toRecycleVo(rb, fileMap.get(rb.getFileId())))
                .filter(Objects::nonNull)
                .toList();
        return PageResult.of(list, page.getTotalElements(), pageNum, pageSize);
    }

    private RecycleVo toRecycleVo(FileRecycleBin rb, FileInfo f) {
        if (f == null) {
            return null;
        }
        RecycleVo vo = new RecycleVo();
        vo.setId(rb.getId());
        vo.setFileId(rb.getFileId());
        vo.setDeletedBy(rb.getDeletedBy());
        vo.setDeletedAt(rb.getDeletedAt());
        vo.setExpireAt(rb.getExpireAt());
        vo.setOriginalName(f.getOriginalName());
        vo.setFileType(f.getFileType());
        vo.setFileSize(f.getFileSize());
        vo.setSpaceType(f.getSpaceType());
        return vo;
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
        if (!canOperate(f, user)) {
            throw BizException.forbidden("无权恢复该文件");
        }
        // X3（TSDD 8.2 / G7）：恢复前置校验——原归属组织/空间仍有效
        if ("ORG".equals(f.getSpaceType()) && f.getOrgId() != null) {
            try {
                orgService.get(f.getOrgId());
            } catch (BizException ex) {
                throw BizException.badRequest("原归属组织已删除，无法恢复该文件");
            }
        }
        // del/ → files/ 迁回（物理迁移提交后执行，避免回滚时文件已被迁走）
        Path del = pathProperties.getStorage().delPath().resolve(f.getStoragePath());
        Path target = PathUtil.resolve(pathProperties.getStorage().rootPath(), f.getStoragePath());
        if (!Files.exists(del)) {
            throw BizException.badRequest("回收站物理文件已被清理，无法恢复该文件");
        }
        f.setDelFlag(0);
        f.setDelAt(null);
        fileInfoRepository.save(f);
        recycleBinRepository.delete(rb);
        afterCommit(() -> moveQuietly(del, target));
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

    /* ============ 回收站批量操作（逐条鉴权，跳过无权限项） ============ */

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchResult {
        private int success;
        private int failed;
        private String message;
    }

    @Transactional
    public BatchResult batchRestore(List<Long> fileIds, AuthUser user) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw BizException.badRequest("请选择文件");
        }
        int ok = 0;
        int fail = 0;
        for (Long id : fileIds) {
            try {
                restore(id, user);
                ok++;
            } catch (Exception ignore) {
                fail++;
            }
        }
        return new BatchResult(ok, fail, "恢复成功 " + ok + " 个，失败 " + fail + " 个");
    }

    @Transactional
    public BatchResult batchPurge(List<Long> fileIds, AuthUser user) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw BizException.badRequest("请选择文件");
        }
        if (!user.isAdmin()) {
            throw BizException.forbidden("仅系统管理员可物理清除");
        }
        int ok = 0;
        int fail = 0;
        for (Long id : fileIds) {
            try {
                purge(id, user);
                ok++;
            } catch (Exception ignore) {
                fail++;
            }
        }
        return new BatchResult(ok, fail, "物理清除成功 " + ok + " 个，失败 " + fail + " 个");
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

    /**
     * 预览目标：与下载同权限（assertCanView），返回可复用于 resolveDownloadPath 的目标。
     * 预览为 inline 直读，不生成一次性 token，避免 PDF/视频内嵌重请求失效。
     */
    public DownloadTarget previewTarget(Long id, AuthUser user) {
        FileInfo f = getFile(id);
        assertCanView(f, user);
        return new DownloadTarget("single", id, null, f.getOriginalName());
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
            // H2 修复：zip 的 relPath 是绝对 tmp 路径，仅取文件名后再经 PathUtil.resolve 约束回 tmp 目录，
            // 杜绝利用伪造 relPath 读取服务器任意文件
            String rel = target.getRelPath();
            if (rel == null || rel.isBlank()) {
                throw BizException.badRequest("下载令牌不合法");
            }
            String name = Path.of(rel).getFileName().toString();
            return PathUtil.resolve(pathProperties.getStorage().tmpPath(), name);
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
