package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.entity.Dept;
import cn.chenxinjie.pathfinder.repository.DeptRepository;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.util.RedisTtlPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部门服务 + 数据权限「可见部门集合 V」推导（PRD 2.2 / TSDD 5.3）。
 * 覆盖：USER/DEPT_ADMIN/ADMIN 的 visibleDeptIds、subtreeIds、部门树构建、
 * 删除约束（有子部门/有文件被拒）、缓存失效。
 */
class DeptServiceTest {

    private static final String CACHE_KEY = "cache:dept:tree";

    private DeptRepository deptRepository;
    private FileInfoRepository fileInfoRepository;
    private RedisTtlPolicy ttl;
    private DeptService deptService;

    @BeforeEach
    void setUp() {
        deptRepository = mock(DeptRepository.class);
        fileInfoRepository = mock(FileInfoRepository.class);
        ttl = mock(RedisTtlPolicy.class);
        deptService = new DeptService(deptRepository, fileInfoRepository, ttl, new ObjectMapper());
    }

    /** 树：组织(1) → 研发部(2) → 前端组(3)、测试组(4)；财务部(5) 挂在组织下。 */
    private void seedDepts() {
        when(deptRepository.findByDelFlagOrderBySortOrderAsc(0))
                .thenReturn(List.of(dept(1, 0, "组织"),
                        dept(2, 1, "研发部"),
                        dept(3, 2, "前端组"),
                        dept(4, 2, "测试组"),
                        dept(5, 1, "财务部")));
    }

    private Dept dept(long id, long parentId, String name) {
        Dept d = new Dept();
        d.setId(id);
        d.setParentId(parentId);
        d.setName(name);
        return d;
    }

    private AuthUser user(long id, long deptId, String role) {
        return new AuthUser(id, "u" + id, "用户" + id, role, deptId, 0);
    }

    @Test
    void visibleDeptIds_user_returnsSelfAndAllAncestors() {
        seedDepts();
        AuthUser a = user(9001, 3, "USER"); // 前端组
        assertEquals(Set.of(1L, 2L, 3L), deptService.visibleDeptIds(a),
                "USER 应可见：本部门 + 全部上级部门");
    }

    @Test
    void visibleDeptIds_deptAdmin_returnsSubtreeAndAncestors() {
        seedDepts();
        AuthUser d1 = user(9003, 2, "DEPT_ADMIN"); // 研发部管理员
        assertEquals(Set.of(1L, 2L, 3L, 4L), deptService.visibleDeptIds(d1),
                "DEPT_ADMIN 应可见：本部门及全部下级子树 + 上级链");
    }

    @Test
    void visibleDeptIds_deptAdmin_multiLevelSubtree() {
        seedDepts();
        AuthUser leaf = user(9004, 3, "DEPT_ADMIN"); // 前端组管理员
        assertEquals(Set.of(1L, 2L, 3L), deptService.visibleDeptIds(leaf),
                "叶子部门管理员：自身 + 上级链，无下级");
    }

    @Test
    void visibleDeptIds_admin_returnsNullMeansUnlimited() {
        seedDepts();
        assertNull(deptService.visibleDeptIds(user(9000, 1, "ADMIN")),
                "ADMIN 返回 null 表示不限部门（列表不过滤）");
    }

    @Test
    void visibleDeptIds_user_parentDeleted_keepsSelfOnly() {
        when(deptRepository.findByDelFlagOrderBySortOrderAsc(0))
                .thenReturn(List.of(dept(6, 99, "孤儿部门"))); // 父部门已删除/不存在
        assertEquals(Set.of(6L), deptService.visibleDeptIds(user(9005, 6, "USER")));
    }

    @Test
    void subtreeIds_returnsWholeBranch() {
        seedDepts();
        assertEquals(Set.of(2L, 3L, 4L), deptService.subtreeIds(2L), "研发部子树含全部下级");
        assertEquals(Set.of(3L), deptService.subtreeIds(3L), "叶子部门子树为自身");
    }

    @Test
    void tree_buildsHierarchyWithRootsAndChildren() {
        seedDepts();
        List<DeptService.DeptNode> roots = deptService.tree();
        assertEquals(1, roots.size(), "仅组织为根");
        DeptService.DeptNode root = roots.get(0);
        assertEquals("组织", root.getName());
        assertEquals(2, root.getChildren().size(), "组织下两个一级部门");
        DeptService.DeptNode dev = root.getChildren().get(0);
        assertEquals("研发部", dev.getName());
        assertEquals(2, dev.getChildren().size(), "研发部下两个子部门");
    }

    @Test
    void delete_withChildren_rejectedAndNotDeleted() {
        seedDepts();
        when(deptRepository.findById(2L)).thenReturn(java.util.Optional.of(dept(2, 1, "研发部")));
        when(deptRepository.existsByParentIdAndDelFlag(2L, 0)).thenReturn(true);

        BizException e = assertThrows(BizException.class, () -> deptService.delete(2L));
        assertEquals(400, e.getStatus());
        verify(deptRepository, never()).save(any());
    }

    @Test
    void delete_deptWithFiles_rejected() {
        seedDepts();
        when(deptRepository.findById(5L)).thenReturn(java.util.Optional.of(dept(5, 1, "财务部")));
        when(deptRepository.existsByParentIdAndDelFlag(5L, 0)).thenReturn(false);
        when(fileInfoRepository.countByDelFlagAndDeptId(0, 5L)).thenReturn(3L);

        BizException e = assertThrows(BizException.class, () -> deptService.delete(5L));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("文件"));
        verify(deptRepository, never()).save(any());
    }

    @Test
    void delete_emptyDept_softDeletesAndInvalidatesCache() {
        seedDepts();
        Dept finance = dept(5, 1, "财务部");
        when(deptRepository.findById(5L)).thenReturn(java.util.Optional.of(finance));
        when(deptRepository.existsByParentIdAndDelFlag(5L, 0)).thenReturn(false);
        when(fileInfoRepository.countByDelFlagAndDeptId(0, 5L)).thenReturn(0L);
        when(deptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deptService.delete(5L);

        assertEquals(1, finance.getDelFlag(), "空部门删除为软删除");
        verify(deptRepository).save(finance);
        verify(ttl).delete(CACHE_KEY);
    }

    @Test
    void createAndUpdate_invalidateDeptTreeCache() {
        when(deptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeptService.DeptForm form = new DeptService.DeptForm();
        form.setName("新部门");
        deptService.create(form);

        when(deptRepository.findById(9L)).thenReturn(java.util.Optional.of(dept(9, 0, "旧名")));
        DeptService.DeptForm update = new DeptService.DeptForm();
        update.setName("新名");
        deptService.update(9L, update);

        verify(ttl, org.mockito.Mockito.times(2)).delete(CACHE_KEY);
    }

    @Test
    void get_missingDept_notFound() {
        when(deptRepository.findById(123L)).thenReturn(java.util.Optional.empty());
        BizException e = assertThrows(BizException.class, () -> deptService.get(123L));
        assertEquals(404, e.getStatus());
    }

    @Test
    void visibleDeptIds_deptNull_returnsEmpty() {
        seedDepts();
        AuthUser orphan = new AuthUser(9009L, "u", "u", "USER", null, 0);
        assertTrue(deptService.visibleDeptIds(orphan).isEmpty(), "无部门用户可见集合应为空");
    }

    @Test
    void cachedDepts_serializationFailure_fallsBackToDb() {
        // 缓存反序列化失败应回源查询（TSDD 7.3 缓存一致性兜底）
        when(ttl.get(CACHE_KEY)).thenReturn("not-json{{");
        when(deptRepository.findByDelFlagOrderBySortOrderAsc(0))
                .thenReturn(List.of(dept(1, 0, "组织")));
        List<DeptService.DeptNode> roots = deptService.tree();
        assertEquals(1, roots.size());
        verify(deptRepository).findByDelFlagOrderBySortOrderAsc(eq(0));
    }
}
