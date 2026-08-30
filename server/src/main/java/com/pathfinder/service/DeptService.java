package com.pathfinder.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.pathfinder.entity.Dept;
import com.pathfinder.repository.DeptRepository;
import com.pathfinder.repository.FileInfoRepository;
import com.pathfinder.security.AuthUser;
import com.pathfinder.util.RedisTtlPolicy;
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
 * 部门管理 + 数据权限「可见部门集合 V」推导（PRD 2.2 / TSDD 5.3）。
 */
@Service
public class DeptService {

    private final DeptRepository deptRepository;
    private final FileInfoRepository fileInfoRepository;
    private final RedisTtlPolicy ttl;
    private final ObjectMapper objectMapper;

    public DeptService(DeptRepository deptRepository, FileInfoRepository fileInfoRepository,
                       RedisTtlPolicy ttl, ObjectMapper objectMapper) {
        this.deptRepository = deptRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.ttl = ttl;
        this.objectMapper = objectMapper;
    }

    @Data
    public static class DeptNode {
        private Long id;
        private Long parentId;
        private String name;
        private Integer sortOrder;
        private Integer status;
        private List<DeptNode> children = new ArrayList<>();
    }

    @Data
    public static class DeptForm {
        private String name;
        private Long parentId = 0L;
        private Integer sortOrder = 0;
        private Integer status = 1;
    }

    public List<DeptNode> tree() {
        List<Dept> depts = cachedDepts();
        Map<Long, DeptNode> map = new LinkedHashMap<>();
        for (Dept d : depts) {
            DeptNode node = new DeptNode();
            node.setId(d.getId());
            node.setParentId(d.getParentId());
            node.setName(d.getName());
            node.setSortOrder(d.getSortOrder());
            node.setStatus(d.getStatus());
            map.put(d.getId(), node);
        }
        List<DeptNode> roots = new ArrayList<>();
        for (DeptNode node : map.values()) {
            DeptNode parent = map.get(node.getParentId());
            if (parent == null || node.getParentId() == null || node.getParentId() == 0L) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    @SuppressWarnings("unchecked")
    private List<Dept> cachedDepts() {
        String cacheKey = "cache:dept:tree";
        try {
            String cached = ttl.get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<Dept>>() {
                });
            }
        } catch (Exception ignore) {
            // 缓存反序列化失败则回源
        }
        List<Dept> depts = deptRepository.findByDelFlagOrderBySortOrderAsc(0);
        try {
            ttl.setWithExplicitTtl(cacheKey, objectMapper.writeValueAsString(depts), 3600, true);
        } catch (Exception ignore) {
            // 缓存写入失败不影响返回
        }
        return depts;
    }

    public void invalidateCache() {
        ttl.delete("cache:dept:tree");
    }

    @Transactional
    public Dept create(DeptForm form) {
        Dept dept = new Dept();
        dept.setParentId(form.getParentId() == null ? 0L : form.getParentId());
        dept.setName(form.getName());
        dept.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        dept.setStatus(form.getStatus() == null ? 1 : form.getStatus());
        Dept saved = deptRepository.save(dept);
        invalidateCache();
        return saved;
    }

    @Transactional
    public Dept update(Long id, DeptForm form) {
        Dept dept = deptRepository.findById(id).orElseThrow(() -> BizException.notFound("部门不存在"));
        if (form.getName() != null) {
            dept.setName(form.getName());
        }
        if (form.getSortOrder() != null) {
            dept.setSortOrder(form.getSortOrder());
        }
        if (form.getStatus() != null) {
            dept.setStatus(form.getStatus());
        }
        Dept saved = deptRepository.save(dept);
        invalidateCache();
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Dept dept = deptRepository.findById(id).orElseThrow(() -> BizException.notFound("部门不存在"));
        if (deptRepository.existsByParentIdAndDelFlag(id, 0)) {
            throw BizException.badRequest("该部门存在子部门，请先删除子部门");
        }
        if (fileInfoRepository.countByDelFlagAndDeptId(0, id) > 0) {
            throw BizException.badRequest("该部门空间下存在文件，请先转移文件归属");
        }
        dept.setDelFlag(1);
        deptRepository.save(dept);
        invalidateCache();
    }

    public Dept get(Long id) {
        return deptRepository.findById(id).orElseThrow(() -> BizException.notFound("部门不存在"));
    }

    /**
     * 可见部门集合 V：
     * USER/VIEWER → 本部门 + 全部上级；
     * DEPT_ADMIN → 所辖子树（本部门及全部下级）+ 该子树的上级链；
     * ADMIN → 返回 null 表示不限。
     */
    public Set<Long> visibleDeptIds(AuthUser user) {
        if (user.isAdmin()) {
            return null;
        }
        List<Dept> all = deptRepository.findByDelFlagOrderBySortOrderAsc(0);
        Map<Long, Dept> byId = new LinkedHashMap<>();
        for (Dept d : all) {
            byId.put(d.getId(), d);
        }
        Set<Long> result = new HashSet<>();
        if (user.isDeptAdmin()) {
            result.addAll(subtreeIds(user.getDeptId(), byId));
        }
        Long cur = user.getDeptId();
        while (cur != null && byId.containsKey(cur)) {
            result.add(cur);
            Long parent = byId.get(cur).getParentId();
            cur = (parent == null || parent == 0L) ? null : parent;
        }
        return result;
    }

    public Set<Long> subtreeIds(Long deptId) {
        List<Dept> all = deptRepository.findByDelFlagOrderBySortOrderAsc(0);
        Map<Long, Dept> byId = new LinkedHashMap<>();
        for (Dept d : all) {
            byId.put(d.getId(), d);
        }
        return subtreeIds(deptId, byId);
    }

    private Set<Long> subtreeIds(Long rootId, Map<Long, Dept> byId) {
        Set<Long> result = new HashSet<>();
        if (rootId == null || !byId.containsKey(rootId)) {
            return result;
        }
        result.add(rootId);
        for (Dept d : byId.values()) {
            if (d.getParentId() != null && rootId.equals(d.getParentId())) {
                result.addAll(subtreeIds(d.getId(), byId));
            }
        }
        return result;
    }
}
