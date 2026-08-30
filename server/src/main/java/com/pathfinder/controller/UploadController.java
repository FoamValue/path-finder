package com.pathfinder.controller;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 大文件分片上传端点（自定义实现组件契约，前端协议与组件 README 一致）。
 * POST /upload（multipart file + 7 参数；或 action=merge/mergeAsync）、GET /upload（progress/mergeStatus）。
 */
@RestController
public class UploadController {

    private final ResumableUploadService uploadService;

    public UploadController(ResumableUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload")
    public Object upload(@RequestParam(value = "file", required = false) MultipartFile file,
                         @RequestParam(value = "action", required = false) String action,
                         @RequestParam(value = "identifier", required = false) String identifier,
                         @RequestParam(value = "fileName", required = false) String fileName,
                         @RequestParam(value = "fileSize", required = false, defaultValue = "0") long fileSize,
                         @RequestParam(value = "chunkSize", required = false, defaultValue = "0") long chunkSize,
                         @RequestParam(value = "chunkTotal", required = false, defaultValue = "0") int chunkTotal,
                         @RequestParam(value = "chunkIndex", required = false, defaultValue = "0") int chunkIndex,
                         @RequestParam(value = "chunkMd5", required = false) String chunkMd5,
                         HttpServletRequest request) throws IOException {
        if ("merge".equals(action)) {
            return uploadService.merge(identifier);
        }
        if ("mergeAsync".equals(action)) {
            return uploadService.submitMerge(identifier);
        }
        if (file == null) {
            throw new IllegalArgumentException("缺少文件分片");
        }
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier(identifier);
        req.setFileName(fileName);
        req.setFileSize(fileSize);
        req.setChunkSize(chunkSize);
        req.setChunkTotal(chunkTotal);
        req.setChunkIndex(chunkIndex);
        req.setChunkMd5(chunkMd5);
        try (var in = file.getInputStream()) {
            return uploadService.uploadChunk(req, in);
        }
    }

    @GetMapping("/upload")
    public Object query(@RequestParam String action,
                        @RequestParam(required = false) String identifier) {
        return switch (action) {
            case "progress" -> uploadService.getProgress(identifier);
            case "mergeStatus" -> uploadService.getMergeStatus(identifier);
            default -> throw new IllegalArgumentException("不支持的 action: " + action);
        };
    }
}
