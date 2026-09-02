package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.entity.Dept;
import cn.chenxinjie.pathfinder.entity.FileInfo;
import cn.chenxinjie.pathfinder.entity.OperationLog;
import cn.chenxinjie.pathfinder.repository.DeptRepository;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.repository.FileRecycleBinRepository;
import cn.chenxinjie.pathfinder.repository.OperationLogRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.util.PathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据权限矩阵自动化（PRD 2.2 / TESTCASES §10 TC-PERM-001~008，F5/F9 关闭门禁）。
 * 数据集：组织/研发部(前端组)/财务部；A(USER·研发部)、B(USER·财务部)、D1(DEPT_ADMIN·研发部)、AD(ADMIN)。
 * 校验：列表可见性矩阵 + 越权重命名/删除/归属/下载 403 + 成功操作审计留痕。
 */
@SpringBootTest
@ActiveProfiles("test")
class DataPermissionMatrixTest {

    @Autowired
    private FileService fileService;
    @Autowired
    private DeptRepository deptRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileRecycleBinRepository recycleBinRepository;
    @Autowired
    private OperationLogRepository operationLogRepository;

    private AuthUser A;
    private AuthUser B;
    private AuthUser D1;
    private AuthUser AD;

    private Long devId;
    private Long frontId;
    private Long financeId;

    private Long personalAId;
    private Long devDocId;
    private Long frontDocId;
    private Long financeDocId;
    private Long publicDocId;

    @BeforeEach
    void setUp() {
        // 清理历史文件数据，保证计数准确（与 SyncScannerServiceTest 一致）
        fileInfoRepository.deleteAll();
        recycleBinRepository.deleteAll();
        cleanupMatrixDepts();

        Dept root = newDept("MTR-组织", 0L);
        deptRepository.save(root);
        Dept dev = newDept("MTR-研发部", root.getId());
        deptRepository.save(dev);
        Dept front = newDept("MTR-前端组", dev.getId());
        deptRepository.save(front);
        Dept finance = newDept("MTR-财务部", root.getId());
        deptRepository.save(finance);
        devId = dev.getId();
        frontId = front.getId();
        financeId = finance.getId();

        A = new AuthUser(9001L, "A", "员工A", "USER", devId, 0);
        B = new AuthUser(9002L, "B", "员工B", "USER", financeId, 0);
        D1 = new AuthUser(9003L, "D1", "部门管理员D1", "DEPT_ADMIN", devId, 0);
        AD = new AuthUser(9000L, "admin", "系统管理员", "ADMIN", root.getId(), 0);

        personalAId = seedFile("A-个人.txt", "PERSONAL", null, 9001L);
        devDocId = seedFile("研发部-合同.txt", "DEPT", devId, 9001L);
        frontDocId = seedFile("前端组-设计稿.txt", "DEPT", frontId, 9001L);
        financeDocId = seedFile("财务部-报表.txt", "DEPT", financeId, 9002L);
        publicDocId = seedFile("公共-公告.txt", "PUBLIC", null, 9000L);
    }

    @AfterEach
    void tearDown() {
        fileInfoRepository.deleteAll();
        recycleBinRepository.deleteAll();
        cleanupMatrixDepts();
    }

    private void cleanupMatrixDepts() {
        List<Dept> all = deptRepository.findAll();
        List<Dept> mine = all.stream().filter(d -> d.getName() != null && d.getName().startsWith("MTR-")).toList();
        if (!mine.isEmpty()) {
            deptRepository.deleteAll(mine);
        }
    }

    private Dept newDept(String name, long parentId) {
        Dept d = new Dept();
        d.setName(name);
        d.setParentId(parentId);
        return d;
    }

    private Long seedFile(String name, String spaceType, Long deptId, Long owner) {
        FileInfo f = new FileInfo();
        f.setOriginalName(name);
        String rel = PathUtil.relativeStorePath(name);
        f.setFileName(java.nio.file.Path.of(rel).getFileName().toString());
        f.setFileSize(1024L);
        f.setFileMd5(PathUtil.uuid());
        f.setFileType(PathUtil.extension(name));
        f.setStoragePath(rel);
        f.setSpaceType(spaceType);
        f.setDeptId(deptId);
        f.setOwnerId(owner);
        f.setCreatorId(owner);
        f.setStatus("READY");
        f.setDiskStatus("READY");
        return fileInfoRepository.save(f).getId();
    }

    private Set<Long> visibleIds(AuthUser user) {
        PageResult<FileService.FileVo> page = fileService.page(user, null, null, null, 1, 100);
        Set<Long> ids = new HashSet<>();
        page.getList().forEach(v -> ids.add(v.getId()));
        return ids;
    }

    /* ============ TC-PERM 可见性矩阵 ============ */

    @Test
    void perm001_aPersonal_visibleOnlyToAAndAdmin() {
        Set<Long> a = visibleIds(A);
        assertTrue(a.contains(personalAId), "A 个人空间文件本人可见");

        assertFalse(visibleIds(B).contains(personalAId), "他人不可见 A 个人文件");
        assertFalse(visibleIds(D1).contains(personalAId), "DEPT_ADMIN 亦不可见他人个人文件");
        assertTrue(visibleIds(AD).contains(personalAId), "ADMIN 全部可见");
    }

    @Test
    void perm002_devSpace_visibleToDevMemberAndDeptAdmin() {
        assertTrue(visibleIds(A).contains(devDocId));
        assertFalse(visibleIds(B).contains(devDocId), "财务部员工不可见研发部空间");
        assertTrue(visibleIds(D1).contains(devDocId));
        assertTrue(visibleIds(AD).contains(devDocId));
    }

    @Test
    void perm003_childDeptSpace_notVisibleToParentDeptUser_visibleToDeptAdmin() {
        assertFalse(visibleIds(A).contains(frontDocId), "研发部成员不可见下级前端组空间文件");
        assertFalse(visibleIds(B).contains(frontDocId));
        assertTrue(visibleIds(D1).contains(frontDocId), "DEPT_ADMIN 可见本部门及全部下级");
        assertTrue(visibleIds(AD).contains(frontDocId));
    }

    @Test
    void perm004_financeSpace_onlyFinanceAndAdmin() {
        assertFalse(visibleIds(A).contains(financeDocId), "研发部不可见财务部空间");
        assertTrue(visibleIds(B).contains(financeDocId));
        assertFalse(visibleIds(D1).contains(financeDocId), "DEPT_ADMIN 越部门不可见");
        assertTrue(visibleIds(AD).contains(financeDocId));
    }

    @Test
    void perm005_publicSpace_visibleToAll() {
        for (AuthUser u : List.of(A, B, D1, AD)) {
            assertTrue(visibleIds(u).contains(publicDocId), u.getUsername() + " 应可见公共空间");
        }
    }

    /* ============ TC-PERM-006/007/008 越权操作 403 ============ */

    @Test
    void perm006_rename_nonOwnerOrOutOfScope_forbidden() {
        // 非所有者/非本部门管理员重命名
        BizException e1 = assertThrows(BizException.class, () -> fileService.rename(devDocId, "x.txt", B));
        assertEquals(403, e1.getStatus());
        BizException e2 = assertThrows(BizException.class,
                () -> fileService.rename(financeDocId, "x.txt", D1), "DEPT_ADMIN 越部门重命名应 403");
        assertEquals(403, e2.getStatus());
        // 所有者重命名成功
        fileService.rename(devDocId, "研发部-合同V2.txt", A);
        assertEquals("研发部-合同V2.txt", fileInfoRepository.findById(devDocId).orElseThrow().getOriginalName());
    }

    @Test
    void perm006_delete_nonOwner_forbidden_ownerOk() {
        BizException e1 = assertThrows(BizException.class, () -> fileService.softDelete(devDocId, B));
        assertEquals(403, e1.getStatus());
        BizException e2 = assertThrows(BizException.class, () -> fileService.softDelete(personalAId, D1));
        assertEquals(403, e2.getStatus());
        fileService.softDelete(personalAId, A); // 所有者删除成功
        assertFalse(visibleIds(A).contains(personalAId), "删除后不再可见");
    }

    @Test
    void perm007_ownerChange_withinScopeOk_outOfScope403() {
        // 越权：非所有者 A 改财务部文件归属 → 403
        BizException e = assertThrows(BizException.class,
                () -> fileService.ownerChange(financeDocId, new FileService.OwnerChangeForm("PUBLIC", null, null), A));
        assertEquals(403, e.getStatus());
        // 越权：D1 改财务部文件归属 → 403
        BizException e2 = assertThrows(BizException.class,
                () -> fileService.ownerChange(financeDocId, new FileService.OwnerChangeForm("PUBLIC", null, null), D1));
        assertEquals(403, e2.getStatus());
        // 归属人 B 成功移交至公共空间
        fileService.ownerChange(financeDocId, new FileService.OwnerChangeForm("PUBLIC", null, null), B);
        assertTrue(visibleIds(A).contains(financeDocId), "变为公共空间后 A 立即可见");
        // 部门管理员 D1 将研发部文件移交至本部门子空间（前端组）成功
        fileService.ownerChange(devDocId, new FileService.OwnerChangeForm("DEPT", frontId, null), D1);
        FileInfo updated = fileInfoRepository.findById(devDocId).orElseThrow();
        assertEquals(frontId, updated.getDeptId(), "dept_id 同步更新");
        assertFalse(visibleIds(A).contains(devDocId), "移交至前端组后研发部成员不再可见");
    }

    @Test
    void perm007_ownerChange_targetDeptMissing_404() {
        BizException e = assertThrows(BizException.class,
                () -> fileService.ownerChange(financeDocId,
                        new FileService.OwnerChangeForm("DEPT", 999999L, null), B));
        assertEquals(404, e.getStatus());
    }

    @Test
    void perm008_downloadOutOfScope_forbiddenBeforeTokenIssued() {
        // A 下载财务部文件：签发 token 前的 canView 即 403（不进入 Redis 写）。
        // 放行侧 token 下发链路见 FileControllerDownloadTest（service 层 mock）。
        BizException e = assertThrows(BizException.class,
                () -> fileService.singleDownloadToken(financeDocId, A));
        assertEquals(403, e.getStatus());
    }

    @Test
    void ownerChange_recordsAuditLogWithOriginAndTarget() {
        fileService.ownerChange(financeDocId, new FileService.OwnerChangeForm("PUBLIC", null, null), B);
        List<OperationLog> logs = operationLogRepository.findAll().stream()
                .filter(l -> "OWNER_CHANGE".equals(l.getOperationType())
                        && String.valueOf(financeDocId).equals(l.getTargetId()))
                .toList();
        assertFalse(logs.isEmpty(), "归属变更必须留痕");
        assertTrue(logs.get(0).getDetail().contains("space=DEPT") && logs.get(0).getDetail().contains("space=PUBLIC"),
                "detail 记录原归属与新归属");
    }

    @Test
    void uploadTicket_departmentSpaceRequiresDept() {
        BizException e = assertThrows(BizException.class,
                () -> fileService.uploadTicket("x.pdf", 1L, "DEPT", null, A));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("部门"));
    }
}
