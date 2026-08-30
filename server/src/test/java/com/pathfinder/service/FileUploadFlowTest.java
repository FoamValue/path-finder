package com.pathfinder.service;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import com.pathfinder.dto.PageResult;
import com.pathfinder.entity.FileRecycleBin;
import com.pathfinder.entity.User;
import com.pathfinder.entity.UserRole;
import com.pathfinder.repository.RoleRepository;
import com.pathfinder.repository.UserRepository;
import com.pathfinder.repository.UserRoleRepository;
import com.pathfinder.security.AuthUser;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        PageResult<FileRecycleBin> adminPage = fileService.recyclePage(admin, 1, 20);
        assertTrue(adminPage.getList().stream().anyMatch(rb -> rb.getFileId().equals(ticket.getFileId())),
                "ADMIN 应能看到回收站记录");

        // 普通用户（无该个人文件权限）不可见
        AuthUser viewer = createUser("viewer", "VIEWER");
        setAuth(viewer);
        PageResult<FileRecycleBin> viewerPage = fileService.recyclePage(viewer, 1, 20);
        assertTrue(viewerPage.getList().stream().noneMatch(rb -> rb.getFileId().equals(ticket.getFileId())),
                "无权限用户不应看到该回收站记录");
    }

    private AuthUser createUser(String username, String roleCode) {
        User u = new User();
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
