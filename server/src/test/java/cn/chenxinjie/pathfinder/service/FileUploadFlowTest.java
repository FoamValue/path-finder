package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.entity.UserRole;
import cn.chenxinjie.pathfinder.repository.RoleRepository;
import cn.chenxinjie.pathfinder.repository.UserRepository;
import cn.chenxinjie.pathfinder.repository.UserRoleRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 核心链路集成测试：uploadTicket → 分片上传 → 异步合并 → confirm → 列表可查。
 * 覆盖「上传成功之后能否查询」这一关键闭环。
 */
@SpringBootTest
@ActiveProfiles("test")
class FileUploadFlowTest {

    private static final int CHUNK = 5 * 1024 * 1024;

    @Autowired
    private FileService fileService;

    @Autowired
    private ResumableUploadService uploadService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AuthUser admin;

    @BeforeEach
    void setUp() {
        admin = new AuthUser(1L, "admin", "系统管理员", "ADMIN", 1L, 0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void singleChunk_upload_merge_confirm_queryable() {
        String name = "repro.txt";
        byte[] data = "repro test data 1234567890".getBytes(StandardCharsets.UTF_8);
        FileService.UploadTicket ticket = fileService.uploadTicket(name, (long) data.length, "PUBLIC", null, admin);

        uploadChunk(name, ticket, data, 0, 1);
        mergeAndWaitSucceeded(ticket.getIdentifier());

        fileService.confirm(ticket.getFileId(), admin);

        PageResult<FileService.FileVo> page = fileService.page(admin, "PUBLIC", null, null, 1, 20);
        assertTrue(page.getList().stream().anyMatch(v -> v.getId().equals(ticket.getFileId())
                        && "READY".equals(v.getStatus())),
                "confirm 后文件应出现在列表中且状态为 READY");
    }

    @Test
    void multiChunk_upload_merge_confirm_queryable() {
        String name = "big.bin";
        byte[] data = new byte[CHUNK * 2 + 123]; // 3 分片
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        FileService.UploadTicket ticket = fileService.uploadTicket(name, (long) data.length, "PUBLIC", null, admin);

        int chunkTotal = (data.length + CHUNK - 1) / CHUNK;
        for (int i = 0; i < chunkTotal; i++) {
            int off = i * CHUNK;
            int len = Math.min(CHUNK, data.length - off);
            byte[] chunk = java.util.Arrays.copyOfRange(data, off, off + len);
            uploadChunk(name, ticket, chunk, i, chunkTotal);
        }
        mergeAndWaitSucceeded(ticket.getIdentifier());

        fileService.confirm(ticket.getFileId(), admin);

        PageResult<FileService.FileVo> page = fileService.page(admin, null, null, null, 1, 20);
        assertTrue(page.getList().stream().anyMatch(v -> v.getId().equals(ticket.getFileId())),
                "多分片 confirm 后文件应出现在列表中");
    }

    @Test
    void uploadingNotConfirmed_shouldNotBeQueryable() {
        byte[] data = "pending".getBytes(StandardCharsets.UTF_8);
        FileService.UploadTicket ticket = fileService.uploadTicket("pending.txt", (long) data.length, "PUBLIC", null, admin);

        PageResult<FileService.FileVo> page = fileService.page(admin, null, null, null, 1, 20);
        assertFalse(page.getList().stream().anyMatch(v -> v.getId().equals(ticket.getFileId())),
                "未完成（UPLOADING）的文件不应出现在列表中");
    }

    @Test
    void missingChunks_mergeShouldFail() {
        String name = "two.txt";
        byte[] data = "needs-two-chunks-data-here".getBytes(StandardCharsets.UTF_8);
        FileService.UploadTicket ticket = fileService.uploadTicket(name, (long) data.length, "PUBLIC", null, admin);

        uploadChunk(name, ticket, data, 0, 2); // 缺分片 1
        uploadService.submitMerge(ticket.getIdentifier());
        waitUntilMerged(ticket.getIdentifier());
        MergeStatus st = uploadService.getMergeStatus(ticket.getIdentifier());
        assertTrue("FAILED".equals(st.getState()), "缺少分片时 merge 应失败，实际: " + st.getState());
    }

    @Test
    void recycle_visibility_followsFilePermission() {
        // admin 在个人空间上传+确认+删除一个文件
        String name = "rc.txt";
        byte[] data = "recycle-visibility".getBytes(StandardCharsets.UTF_8);
        FileService.UploadTicket ticket = fileService.uploadTicket(name, (long) data.length, "PERSONAL", null, admin);
        uploadChunk(name, ticket, data, 0, 1);
        mergeAndWaitSucceeded(ticket.getIdentifier());
        fileService.confirm(ticket.getFileId(), admin);
        fileService.softDelete(ticket.getFileId(), admin);

        // ADMIN 可见回收站记录
        PageResult<FileService.RecycleVo> adminPage = fileService.recyclePage(admin, 1, 20);
        assertTrue(adminPage.getList().stream().anyMatch(rb -> rb.getFileId().equals(ticket.getFileId())
                        && "rc.txt".equals(rb.getOriginalName())),
                "ADMIN 应能看到回收站记录（含文件名）");

        // 普通用户（无该个人文件权限）不可见
        AuthUser viewer = createUser("viewer", "VIEWER");
        setAuth(viewer);
        PageResult<FileService.RecycleVo> viewerPage = fileService.recyclePage(viewer, 1, 20);
        assertTrue(viewerPage.getList().stream().noneMatch(rb -> rb.getFileId().equals(ticket.getFileId())),
                "无权限用户不应看到该回收站记录");
    }

    @Test
    void batchRestore_restoresAllSelected() {
        Long f1 = uploadAndDelete("b1.txt", "batch-restore-1");
        Long f2 = uploadAndDelete("b2.txt", "batch-restore-2");

        FileService.BatchResult r = fileService.batchRestore(java.util.List.of(f1, f2), admin);
        assertEquals(2, r.getSuccess());
        assertEquals(0, r.getFailed());

        PageResult<FileService.RecycleVo> page = fileService.recyclePage(admin, 1, 20);
        assertTrue(page.getList().stream().noneMatch(rb -> java.util.List.of(f1, f2).contains(rb.getFileId())),
                "批量恢复后回收站不应再包含这些记录");
        assertTrue(fileService.page(admin, "PERSONAL", null, null, 1, 20)
                        .getList().stream().filter(v -> java.util.List.of(f1, f2).contains(v.getId())).count() == 2,
                "批量恢复后文件应回到列表");
    }

    @Test
    void batchPurge_requiresAdmin() {
        Long f1 = uploadAndDelete("p1.txt", "batch-purge-1");
        Long f2 = uploadAndDelete("p2.txt", "batch-purge-2");

        AuthUser viewer = createUser("viewer2", "VIEWER");
        assertThrows(BizException.class, () -> fileService.batchPurge(java.util.List.of(f1, f2), viewer),
                "非管理员批量物理清除应被拒绝");

        FileService.BatchResult r = fileService.batchPurge(java.util.List.of(f1, f2), admin);
        assertEquals(2, r.getSuccess());
        assertTrue(fileService.recyclePage(admin, 1, 20).getList()
                        .stream().noneMatch(rb -> java.util.List.of(f1, f2).contains(rb.getFileId())),
                "批量清除后回收站记录应消失");
    }

    @Test
    void restore_requiresOperatePermission() {
        Long fid = uploadAndDelete("perm.txt", "permission-test", "DEPT");
        AuthUser viewer = createUser("viewer3", "VIEWER");
        assertThrows(BizException.class, () -> fileService.restore(fid, viewer),
                "非归属人/非管理员/非部门管理员不得恢复部门空间文件");
    }

    @Test
    void batchDelete_movesToRecycle() {
        Long f1 = uploadAndConfirm("bd1.txt", "batch-del-1");
        Long f2 = uploadAndConfirm("bd2.txt", "batch-del-2");

        FileService.BatchResult r = fileService.batchDelete(java.util.List.of(f1, f2), admin);
        assertEquals(2, r.getSuccess());

        assertTrue(fileService.recyclePage(admin, 1, 20).getList()
                        .stream().filter(rb -> java.util.List.of(f1, f2).contains(rb.getFileId())).count() == 2,
                "批量删除后文件应进入回收站");
        assertTrue(fileService.page(admin, "PERSONAL", null, null, 1, 20)
                        .getList().stream().noneMatch(v -> java.util.List.of(f1, f2).contains(v.getId())),
                "批量删除后文件不应出现在列表");
    }

    @Test
    void batchOwnerChange_appliesToAll() {
        Long f1 = uploadAndConfirm("bo1.txt", "batch-owner-1");
        Long f2 = uploadAndConfirm("bo2.txt", "batch-owner-2");

        FileService.OwnerChangeForm form = new FileService.OwnerChangeForm("PUBLIC", null, null);
        FileService.BatchResult r = fileService.batchOwnerChange(java.util.List.of(f1, f2), form, admin);
        assertEquals(2, r.getSuccess());

        assertTrue(fileService.page(admin, "PUBLIC", null, null, 1, 20)
                        .getList().stream().filter(v -> java.util.List.of(f1, f2).contains(v.getId())).count() == 2,
                "批量归属变更后文件应变为公共空间");
    }

    private Long uploadAndConfirm(String name, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        FileService.UploadTicket ticket = fileService.uploadTicket(name, (long) data.length, "PERSONAL", null, admin);
        uploadChunk(name, ticket, data, 0, 1);
        mergeAndWaitSucceeded(ticket.getIdentifier());
        fileService.confirm(ticket.getFileId(), admin);
        return ticket.getFileId();
    }

    private Long uploadAndDelete(String name, String content) {
        return uploadAndDelete(name, content, "PERSONAL");
    }

    private Long uploadAndDelete(String name, String content, String spaceType) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        Long deptId = "DEPT".equals(spaceType) ? 1L : null;
        FileService.UploadTicket ticket = fileService.uploadTicket(name, (long) data.length, spaceType, deptId, admin);
        uploadChunk(name, ticket, data, 0, 1);
        mergeAndWaitSucceeded(ticket.getIdentifier());
        fileService.confirm(ticket.getFileId(), admin);
        fileService.softDelete(ticket.getFileId(), admin);
        return ticket.getFileId();
    }

    private AuthUser createUser(String username, String roleCode) {        User u = new User();
        u.setUsername(username);
        u.setRealName(username);
        u.setDeptId(1L);
        u.setPassword(passwordEncoder.encode("Init@123"));
        u.setMustChangePassword(0);
        u.setStatus(1);
        userRepository.save(u);
        roleRepository.findByRoleCode(roleCode).ifPresent(role -> {
            UserRole ur = new UserRole();
            ur.setUserId(u.getId());
            ur.setRoleId(role.getId());
            userRoleRepository.save(ur);
        });
        return new AuthUser(u.getId(), username, username, roleCode, 1L, 0);
    }

    private void setAuth(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private void uploadChunk(String fileName, FileService.UploadTicket ticket, byte[] chunk,
                             int index, int chunkTotal) {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier(ticket.getIdentifier());
        req.setFileName(fileName);
        req.setChunkTotal(chunkTotal);
        req.setChunkIndex(index);
        try {
            uploadService.uploadChunk(req, new ByteArrayInputStream(chunk));
        } catch (Exception e) {
            throw new RuntimeException("分片上传失败", e);
        }
    }

    private void mergeAndWaitSucceeded(String identifier) {
        uploadService.submitMerge(identifier);
        waitUntilMerged(identifier);
        MergeStatus st = uploadService.getMergeStatus(identifier);
        if (!"SUCCEEDED".equals(st.getState())) {
            fail("合并未成功: " + st.getState());
        }
    }

    private void waitUntilMerged(String identifier) {
        for (int i = 0; i < 60; i++) {
            MergeStatus st = uploadService.getMergeStatus(identifier);
            if ("SUCCEEDED".equals(st.getState()) || "FAILED".equals(st.getState())) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
