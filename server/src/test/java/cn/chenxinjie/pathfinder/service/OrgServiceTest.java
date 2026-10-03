package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.entity.Org;
import cn.chenxinjie.pathfinder.repository.OrgRepository;
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
 * 组织服务 + 数据权限「可见组织集合 V」推导（PRD 2.2 / TSDD 5.3）。
 * 覆盖：USER/ORG_ADMIN/ADMIN 的 visibleOrgIds、subtreeIds、组织树构建、
 * 删除约束（有子组织/有文件被拒）、缓存失效。
 */
class OrgServiceTest {

    private static final String CACHE_KEY = "cache:org:tree";

    private OrgRepository orgRepository;
    private FileInfoRepository fileInfoRepository;
    private RedisTtlPolicy ttl;
    private OrgService orgService;

    @BeforeEach
    void setUp() {
        orgRepository = mock(OrgRepository.class);
        fileInfoRepository = mock(FileInfoRepository.class);
        ttl = mock(RedisTtlPolicy.class);
        orgService = new OrgService(orgRepository, fileInfoRepository, ttl, new ObjectMapper());
    }

    /** 树：组织(1) → 研发部(2) → 前端组(3)、测试组(4)；财务部(5) 挂在组织下。 */
    private void seedOrgs() {
        when(orgRepository.findByDelFlagOrderBySortOrderAsc(0))
                .thenReturn(List.of(org(1, 0, "组织"),
                        org(2, 1, "研发部"),
                        org(3, 2, "前端组"),
                        org(4, 2, "测试组"),
                        org(5, 1, "财务部")));
    }

    private Org org(long id, long parentId, String name) {
        Org d = new Org();
        d.setId(id);
        d.setParentId(parentId);
        d.setName(name);
        return d;
    }

    private AuthUser user(long id, long orgId, String role) {
        return new AuthUser(id, "u" + id, "用户" + id, role, orgId, 0);
    }

    @Test
    void visibleOrgIds_user_returnsSelfAndAllAncestors() {
        seedOrgs();
        AuthUser a = user(9001, 3, "USER"); // 前端组
        assertEquals(Set.of(1L, 2L, 3L), orgService.visibleOrgIds(a),
                "USER 应可见：本组织 + 全部上级组织");
    }

    @Test
    void visibleOrgIds_orgAdmin_returnsSubtreeAndAncestors() {
        seedOrgs();
        AuthUser d1 = user(9003, 2, "ORG_ADMIN"); // 研发部管理员
        assertEquals(Set.of(1L, 2L, 3L, 4L), orgService.visibleOrgIds(d1),
                "ORG_ADMIN 应可见：本组织及全部下级子树 + 上级链");
    }

    @Test
    void visibleOrgIds_orgAdmin_multiLevelSubtree() {
        seedOrgs();
        AuthUser leaf = user(9004, 3, "ORG_ADMIN"); // 前端组管理员
        assertEquals(Set.of(1L, 2L, 3L), orgService.visibleOrgIds(leaf),
                "叶子组织管理员：自身 + 上级链，无下级");
    }

    @Test
    void visibleOrgIds_admin_returnsNullMeansUnlimited() {
        seedOrgs();
        assertNull(orgService.visibleOrgIds(user(9000, 1, "ADMIN")),
                "ADMIN 返回 null 表示不限组织（列表不过滤）");
    }

    @Test
    void visibleOrgIds_user_parentDeleted_keepsSelfOnly() {
        when(orgRepository.findByDelFlagOrderBySortOrderAsc(0))
                .thenReturn(List.of(org(6, 99, "孤儿组织"))); // 父组织已删除/不存在
        assertEquals(Set.of(6L), orgService.visibleOrgIds(user(9005, 6, "USER")));
    }

    @Test
    void subtreeIds_returnsWholeBranch() {
        seedOrgs();
        assertEquals(Set.of(2L, 3L, 4L), orgService.subtreeIds(2L), "研发部子树含全部下级");
        assertEquals(Set.of(3L), orgService.subtreeIds(3L), "叶子组织子树为自身");
    }

    @Test
    void tree_buildsHierarchyWithRootsAndChildren() {
        seedOrgs();
        List<OrgService.OrgNode> roots = orgService.tree();
        assertEquals(1, roots.size(), "仅组织为根");
        OrgService.OrgNode root = roots.get(0);
        assertEquals("组织", root.getName());
        assertEquals(2, root.getChildren().size(), "组织下两个一级组织");
        OrgService.OrgNode dev = root.getChildren().get(0);
        assertEquals("研发部", dev.getName());
        assertEquals(2, dev.getChildren().size(), "研发部下两个子组织");
    }

    @Test
    void delete_withChildren_rejectedAndNotDeleted() {
        seedOrgs();
        when(orgRepository.findById(2L)).thenReturn(java.util.Optional.of(org(2, 1, "研发部")));
        when(orgRepository.existsByParentIdAndDelFlag(2L, 0)).thenReturn(true);

        BizException e = assertThrows(BizException.class, () -> orgService.delete(2L));
        assertEquals(400, e.getStatus());
        verify(orgRepository, never()).save(any());
    }

    @Test
    void delete_orgWithFiles_rejected() {
        seedOrgs();
        when(orgRepository.findById(5L)).thenReturn(java.util.Optional.of(org(5, 1, "财务部")));
        when(orgRepository.existsByParentIdAndDelFlag(5L, 0)).thenReturn(false);
        when(fileInfoRepository.countByDelFlagAndOrgId(0, 5L)).thenReturn(3L);

        BizException e = assertThrows(BizException.class, () -> orgService.delete(5L));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("文件"));
        verify(orgRepository, never()).save(any());
    }

    @Test
    void delete_emptyOrg_softDeletesAndInvalidatesCache() {
        seedOrgs();
        Org finance = org(5, 1, "财务部");
        when(orgRepository.findById(5L)).thenReturn(java.util.Optional.of(finance));
        when(orgRepository.existsByParentIdAndDelFlag(5L, 0)).thenReturn(false);
        when(fileInfoRepository.countByDelFlagAndOrgId(0, 5L)).thenReturn(0L);
        when(orgRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orgService.delete(5L);

        assertEquals(1, finance.getDelFlag(), "空组织删除为软删除");
        verify(orgRepository).save(finance);
        verify(ttl).delete(CACHE_KEY);
    }

    @Test
    void createAndUpdate_invalidateOrgTreeCache() {
        when(orgRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrgService.OrgForm form = new OrgService.OrgForm();
        form.setName("新组织");
        orgService.create(form);

        when(orgRepository.findById(9L)).thenReturn(java.util.Optional.of(org(9, 0, "旧名")));
        OrgService.OrgForm update = new OrgService.OrgForm();
        update.setName("新名");
        orgService.update(9L, update);

        verify(ttl, org.mockito.Mockito.times(2)).delete(CACHE_KEY);
    }

    @Test
    void get_missingOrg_notFound() {
        when(orgRepository.findById(123L)).thenReturn(java.util.Optional.empty());
        BizException e = assertThrows(BizException.class, () -> orgService.get(123L));
        assertEquals(404, e.getStatus());
    }

    @Test
    void visibleOrgIds_orgNull_returnsEmpty() {
        seedOrgs();
        AuthUser orphan = new AuthUser(9009L, "u", "u", "USER", null, 0);
        assertTrue(orgService.visibleOrgIds(orphan).isEmpty(), "无组织用户可见集合应为空");
    }

    @Test
    void cachedOrgs_serializationFailure_fallsBackToDb() {
        // 缓存反序列化失败应回源查询（TSDD 7.3 缓存一致性兜底）
        when(ttl.get(CACHE_KEY)).thenReturn("not-json{{");
        when(orgRepository.findByDelFlagOrderBySortOrderAsc(0))
                .thenReturn(List.of(org(1, 0, "组织")));
        List<OrgService.OrgNode> roots = orgService.tree();
        assertEquals(1, roots.size());
        verify(orgRepository).findByDelFlagOrderBySortOrderAsc(eq(0));
    }
}
