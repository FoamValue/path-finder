package cn.chenxinjie.pathfinder.controller;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.entity.Org;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.service.OrgService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织管理接口（仅 ADMIN 写；ORG_ADMIN 只读本组织）。
 */
@RestController
@RequestMapping("/api/org")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping("/tree")
    public ApiResponse<List<OrgService.OrgNode>> tree() {
        return ApiResponse.ok(orgService.tree());
    }

    @PostMapping
    public ApiResponse<Org> create(@RequestBody OrgService.OrgForm form) {
        requireAdmin();
        return ApiResponse.ok(orgService.create(form));
    }

    @PutMapping("/{id}")
    public ApiResponse<Org> update(@PathVariable Long id, @RequestBody OrgService.OrgForm form) {
        requireAdmin();
        return ApiResponse.ok(orgService.update(id, form));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireAdmin();
        orgService.delete(id);
        return ApiResponse.ok();
    }

    private void requireAdmin() {
        AuthUser user = SecurityUtil.current();
        if (!user.isAdmin()) {
            throw new cn.chenxinjie.pathfinder.service.BizException(403, "仅系统管理员可操作");
        }
    }
}
