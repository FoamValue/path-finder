package cn.chenxinjie.pathfinder.controller;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.service.FileService;
import cn.chenxinjie.pathfinder.service.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件接口：列表（真分页+数据权限）、上传预注册、确认、重命名、归属变更、软删除、下载（Range）。
 */
@RestController
@RequestMapping("/api/file")
public class FileController {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadTicketForm {
        private String fileName;
        private Long fileSize;
        private String spaceType;
        private Long deptId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchDownloadForm {
        private List<Long> ids;
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<FileService.FileVo>> page(
            @RequestParam(required = false) String spaceType,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(fileService.page(SecurityUtil.current(), spaceType, deptId, keyword, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<FileService.FileVo> meta(@PathVariable Long id) {
        return ApiResponse.ok(fileService.meta(id, SecurityUtil.current()));
    }

    @PostMapping("/uploadTicket")
    public ApiResponse<FileService.UploadTicket> uploadTicket(@RequestBody UploadTicketForm form) {
        return ApiResponse.ok(fileService.uploadTicket(form.getFileName(), form.getFileSize(),
                form.getSpaceType(), form.getDeptId(), SecurityUtil.current()));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable Long id) {
        fileService.confirm(id, SecurityUtil.current());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/rename")
    public ApiResponse<Void> rename(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        fileService.rename(id, body.get("newName"), SecurityUtil.current());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/owner")
    public ApiResponse<Void> ownerChange(@PathVariable Long id,
                                         @RequestBody FileService.OwnerChangeForm form) {
        fileService.ownerChange(id, form, SecurityUtil.current());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        fileService.softDelete(id, SecurityUtil.current());
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/downloadToken")
    public ApiResponse<FileService.DownloadTicket> downloadToken(@PathVariable Long id) {
        return ApiResponse.ok(fileService.singleDownloadToken(id, SecurityUtil.current()));
    }

    @PostMapping("/batchDownload")
    public ApiResponse<FileService.DownloadTicket> batchDownload(@RequestBody BatchDownloadForm form)
            throws IOException {
        return ApiResponse.ok(fileService.batchDownload(form.getIds(), SecurityUtil.current()));
    }

    /**
     * 下载（支持 Range 断点续传：200 / 206 / 416）。
     */
    @GetMapping("/download/{token}")
    public void download(@PathVariable String token,
                         @RequestHeader(value = "Range", required = false) String range,
                         HttpServletResponse response) throws IOException {
        FileService.DownloadTarget target = fileService.consumeToken(token);
        Path path = fileService.resolveDownloadPath(target);
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw BizException.notFound("文件不存在");
        }
        String fileName = target.getFileName();
        long size = Files.size(path);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20"));
        response.setContentType(guessContentType(fileName));
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");

        long start = 0;
        long end = size - 1;
        if (range != null && range.startsWith("bytes=")) {
            Matcher m = RANGE.matcher(range);
            if (m.matches()) {
                String s = m.group(1);
                String e = m.group(2);
                if (!s.isEmpty()) {
                    start = Long.parseLong(s);
                    if (!e.isEmpty()) {
                        end = Long.parseLong(e);
                    }
                } else if (!e.isEmpty()) {
                    // 后缀范围
                    start = Math.max(0, size - Long.parseLong(e));
                }
                if (start >= size) {
                    response.setStatus(416);
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                    return;
                }
                end = Math.min(end, size - 1);
                response.setStatus(206);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
                response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(end - start + 1));
            }
        } else {
            response.setStatus(200);
            response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
        }
        try (InputStream in = Files.newInputStream(path); OutputStream out = response.getOutputStream()) {
            in.skipNBytes(start);
            long remain = end - start + 1;
            byte[] buf = new byte[64 * 1024];
            int n;
            while (remain > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remain))) > 0) {
                out.write(buf, 0, n);
                remain -= n;
            }
            out.flush();
        }
        if (target.getFileId() != null) {
            fileService.refreshAfterDownload(target.getFileId());
        }
    }

    private String guessContentType(String fileName) {
        String ext = cn.chenxinjie.pathfinder.util.PathUtil.extension(fileName);
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "zip" -> "application/zip";
            case "txt", "log", "md" -> "text/plain; charset=UTF-8";
            case "csv" -> "text/csv; charset=UTF-8";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}
