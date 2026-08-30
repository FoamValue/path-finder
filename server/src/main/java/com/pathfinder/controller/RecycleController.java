package com.pathfinder.controller;

import com.pathfinder.dto.ApiResponse;
import com.pathfinder.dto.PageResult;
import com.pathfinder.entity.FileRecycleBin;
import com.pathfinder.security.SecurityUtil;
import com.pathfinder.service.FileService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回收站接口（保留 30 天）。
 */
@RestController
@RequestMapping("/api/recycle")
public class RecycleController {

    private final FileService fileService;

    public RecycleController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<FileRecycleBin>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(fileService.recyclePage(SecurityUtil.current(), pageNum, pageSize));
    }

    @PostMapping("/{fileId}/restore")
    public ApiResponse<Void> restore(@PathVariable Long fileId) {
        fileService.restore(fileId, SecurityUtil.current());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{fileId}/purge")
    public ApiResponse<Void> purge(@PathVariable Long fileId) {
        fileService.purge(fileId, SecurityUtil.current());
        return ApiResponse.ok();
    }
}
