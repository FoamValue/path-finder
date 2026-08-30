package cn.chenxinjie.pathfinder.controller;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.entity.Dept;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.service.DeptService;
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
 * 部门管理接口（仅 ADMIN 写；DEPT_ADMIN 只读本部门）。
 */
@RestController
@RequestMapping("/api/dept")
public class DeptController {

    private final DeptService deptService;

    public DeptController(DeptService deptService) {
        this.deptService = deptService;
    }

    @GetMapping("/tree")
    public ApiResponse<List<DeptService.DeptNode>> tree() {
        return ApiResponse.ok(deptService.tree());
    }

    @PostMapping
    public ApiResponse<Dept> create(@RequestBody DeptService.DeptForm form) {
        requireAdmin();
        return ApiResponse.ok(deptService.create(form));
    }

    @PutMapping("/{id}")
    public ApiResponse<Dept> update(@PathVariable Long id, @RequestBody DeptService.DeptForm form) {
        requireAdmin();
        return ApiResponse.ok(deptService.update(id, form));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireAdmin();
        deptService.delete(id);
        return ApiResponse.ok();
    }

    private void requireAdmin() {
        AuthUser user = SecurityUtil.current();
        if (!user.isAdmin()) {
            throw new cn.chenxinjie.pathfinder.service.BizException(403, "仅系统管理员可操作");
        }
    }
}
