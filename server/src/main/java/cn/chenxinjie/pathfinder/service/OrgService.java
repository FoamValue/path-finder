package cn.chenxinjie.pathfinder.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import cn.chenxinjie.pathfinder.entity.Org;
import cn.chenxinjie.pathfinder.repository.OrgRepository;
import cn.chenxinjie.pathfinder.repository.FileInfoRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.util.RedisTtlPolicy;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织管理 + 数据权限「可见组织集合 V」推导（PRD 2.2 / TSDD 5.3）。
 */
@Service
public class OrgService {

    private final OrgRepository orgRepository;
    private final FileInfoRepository fileInfoRepository;
    private final RedisTtlPolicy ttl;
    private final ObjectMapper objectMapper;

    public OrgService(OrgRepository orgRepository, FileInfoRepository fileInfoRepository,
                       RedisTtlPolicy ttl, ObjectMapper objectMapper) {
        this.orgRepository = orgRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.ttl = ttl;
        this.objectMapper = objectMapper;
    }

    @Data
    public static class OrgNode {
        private Long id;
        private Long parentId;
        private String name;
        private Integer sortOrder;
        private Integer status;
        private List<OrgNode> children = new ArrayList<>();
    }

    @Data
    public static class OrgForm {
        private String name;
        private Long parentId = 0L;
        private Integer sortOrder = 0;
        private Integer status = 1;
    }

    public List<OrgNode> tree() {
        List<Org> orgs = cachedOrgs();
        Map<Long, OrgNode> map = new LinkedHashMap<>();
        for (Org d : orgs) {
            OrgNode node = new OrgNode();
            node.setId(d.getId());
            node.setParentId(d.getParentId());
            node.setName(d.getName());
            node.setSortOrder(d.getSortOrder());
            node.setStatus(d.getStatus());
            map.put(d.getId(), node);
        }
        List<OrgNode> roots = new ArrayList<>();
        for (OrgNode node : map.values()) {
            OrgNode parent = map.get(node.getParentId());
            if (parent == null || node.getParentId() == null || node.getParentId() == 0L) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    @SuppressWarnings("unchecked")
    private List<Org> cachedOrgs() {
        String cacheKey = "cache:org:tree";
        try {
            String cached = ttl.get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<Org>>() {
                });
            }
        } catch (Exception ignore) {
            // 缓存反序列化失败则回源
        }
        List<Org> orgs = orgRepository.findByDelFlagOrderBySortOrderAsc(0);
        try {
            ttl.setWithExplicitTtl(cacheKey, objectMapper.writeValueAsString(orgs), 3600, true);
        } catch (Exception ignore) {
            // 缓存写入失败不影响返回
        }
        return orgs;
    }

    public void invalidateCache() {
        ttl.delete("cache:org:tree");
    }

    @Transactional
    public Org create(OrgForm form) {
        Org org = new Org();
        org.setParentId(form.getParentId() == null ? 0L : form.getParentId());
        org.setName(form.getName());
        org.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        org.setStatus(form.getStatus() == null ? 1 : form.getStatus());
        Org saved = orgRepository.save(org);
        invalidateCache();
        return saved;
    }

    @Transactional
    public Org update(Long id, OrgForm form) {
        Org org = orgRepository.findById(id).orElseThrow(() -> BizException.notFound("组织不存在"));
        if (form.getName() != null) {
            org.setName(form.getName());
        }
        if (form.getSortOrder() != null) {
            org.setSortOrder(form.getSortOrder());
        }
        if (form.getStatus() != null) {
            org.setStatus(form.getStatus());
        }
        Org saved = orgRepository.save(org);
        invalidateCache();
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Org org = orgRepository.findById(id).orElseThrow(() -> BizException.notFound("组织不存在"));
        if (orgRepository.existsByParentIdAndDelFlag(id, 0)) {
            throw BizException.badRequest("该组织存在子组织，请先删除子组织");
        }
        if (fileInfoRepository.countByDelFlagAndOrgId(0, id) > 0) {
            throw BizException.badRequest("该组织空间下存在文件，请先转移文件归属");
        }
        org.setDelFlag(1);
        orgRepository.save(org);
        invalidateCache();
    }

    public Org get(Long id) {
        // 软删除组织视为不存在（G7/X3：目标组织已删除时不回退到残留实体）
        return orgRepository.findById(id)
                .filter(d -> d.getDelFlag() == null || d.getDelFlag() == 0)
                .orElseThrow(() -> BizException.notFound("组织不存在"));
    }

    /**
     * 可见组织集合 V：
     * USER/VIEWER → 本组织 + 全部上级；
     * ORG_ADMIN → 所辖子树（本组织及全部下级）+ 该子树的上级链；
     * ADMIN → 返回 null 表示不限。
     */
    public Set<Long> visibleOrgIds(AuthUser user) {
        if (user.isAdmin()) {
            return null;
        }
        List<Org> all = orgRepository.findByDelFlagOrderBySortOrderAsc(0);
        Map<Long, Org> byId = new LinkedHashMap<>();
        for (Org d : all) {
            byId.put(d.getId(), d);
        }
        Set<Long> result = new HashSet<>();
        if (user.isOrgAdmin()) {
            result.addAll(subtreeIds(user.getOrgId(), byId));
        }
        Long cur = user.getOrgId();
        while (cur != null && byId.containsKey(cur)) {
            result.add(cur);
            Long parent = byId.get(cur).getParentId();
            cur = (parent == null || parent == 0L) ? null : parent;
        }
        return result;
    }

    public Set<Long> subtreeIds(Long orgId) {
        List<Org> all = orgRepository.findByDelFlagOrderBySortOrderAsc(0);
        Map<Long, Org> byId = new LinkedHashMap<>();
        for (Org d : all) {
            byId.put(d.getId(), d);
        }
        return subtreeIds(orgId, byId);
    }

    private Set<Long> subtreeIds(Long rootId, Map<Long, Org> byId) {
        Set<Long> result = new HashSet<>();
        if (rootId == null || !byId.containsKey(rootId)) {
            return result;
        }
        result.add(rootId);
        for (Org d : byId.values()) {
            if (d.getParentId() != null && rootId.equals(d.getParentId())) {
                result.addAll(subtreeIds(d.getId(), byId));
            }
        }
        return result;
    }
}
