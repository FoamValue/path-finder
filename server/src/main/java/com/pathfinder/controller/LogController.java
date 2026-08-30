package com.pathfinder.controller;

import com.pathfinder.dto.ApiResponse;
import com.pathfinder.dto.PageResult;
import com.pathfinder.entity.OperationLog;
import com.pathfinder.security.AuthUser;
import com.pathfinder.security.SecurityUtil;
import com.pathfinder.service.LogService;
import com.pathfinder.service.BizException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 审计日志查询（仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/log")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<OperationLog>> page(
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) Integer success,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireAdmin();
        return ApiResponse.ok(logService.page(operatorName, operationType, success, start, end, pageNum, pageSize));
    }

    private void requireAdmin() {
        AuthUser user = SecurityUtil.current();
        if (!user.isAdmin()) {
            throw new BizException(403, "仅系统管理员可查看审计日志");
        }
    }
}
