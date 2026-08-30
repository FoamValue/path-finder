package cn.chenxinjie.pathfinder.controller;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.service.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（仅 ADMIN；DEPT_ADMIN 只读本部门）。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<UserService.UserVo>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(userService.page(SecurityUtil.current(), keyword, deptId, pageNum, pageSize));
    }

    @PostMapping
    public ApiResponse<UserService.UserVo> create(@RequestBody UserService.UserForm form) {
        return ApiResponse.ok(userService.create(form, SecurityUtil.current()));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserService.UserVo> update(@PathVariable Long id, @RequestBody UserService.UserForm form) {
        return ApiResponse.ok(userService.update(id, form, SecurityUtil.current()));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> status(@PathVariable Long id, @RequestParam Integer status) {
        userService.updateStatus(id, status, SecurityUtil.current());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/resetPassword")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id, SecurityUtil.current());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id, SecurityUtil.current());
        return ApiResponse.ok();
    }
}
