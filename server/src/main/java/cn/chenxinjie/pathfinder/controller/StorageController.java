package cn.chenxinjie.pathfinder.controller;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.service.BizException;
import cn.chenxinjie.pathfinder.service.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储监控（仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/info")
    public ApiResponse<StorageService.StorageInfo> info() {
        AuthUser user = SecurityUtil.current();
        if (!user.isAdmin()) {
            throw new BizException(403, "仅系统管理员可查看存储监控");
        }
        return ApiResponse.ok(storageService.info());
    }
}
