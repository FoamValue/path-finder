package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException;
import cn.chenxinjie.uploadfile.core.exception.UploadTaskNotFoundException;
import cn.chenxinjie.uploadfile.core.exception.UploadValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * upload-file rc.4 类型化异常 → 稳定 HTTP 状态码映射（全局异常处理）。
 */
class UploadFileErrorMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private void assertMapped(ResponseEntity<ApiResponse<Void>> resp, int expectedStatus,
                              String expectedMessage, String rawDetail) {
        assertEquals(expectedStatus, resp.getStatusCode().value(), "状态码");
        ApiResponse<Void> body = resp.getBody();
        assertEquals(expectedStatus, body.getCode());
        assertEquals(expectedMessage, body.getMessage(), "对外文案");
        assertFalse(expectedMessage.contains(rawDetail),
                "对外文案不得透传内部原始 message");
    }

    @Test
    void checksumMismatch_maps400WithFriendlyText() {
        String raw = "chunk 3 MD5 mismatch, expected abc, actual xyz";
        assertMapped(handler.handleChecksum(new ChecksumMismatchException(raw)),
                400, "分片校验失败，请重传该分片", raw);
    }

    @Test
    void validation_maps400WithSafeText() {
        String raw = "chunkTotal must be greater than 0";
        assertMapped(handler.handleUploadValidation(new UploadValidationException(raw)),
                400, "上传参数不合法，请检查后重试", raw);
    }

    @Test
    void taskNotFound_maps404() {
        String raw = "Upload task not found: abc";
        assertMapped(handler.handleUploadTaskNotFound(new UploadTaskNotFoundException(raw)),
                404, "上传任务不存在或已过期，请重新上传", raw);
    }

    @Test
    void mergeConflict_maps409() {
        String raw = "Async merge is RUNNING, cannot cancel: abc";
        assertMapped(handler.handleUploadMergeConflict(new UploadMergeConflictException(raw)),
                409, "文件正在上传/合并中，请稍后再试", raw);
    }

    @Test
    void quotaExceeded_maps507() {
        String raw = "Storage quota exceeded: used 1 bytes, limit 2 bytes";
        assertMapped(handler.handleQuota(new QuotaExceededException(raw)),
                507, "存储配额不足，无法继续上传", raw);
    }

    @Test
    void accessDenied_maps403() {
        String raw = "no permission for upload action: merge, identifier: abc";
        assertMapped(handler.handleUploadAccessDenied(new cn.chenxinjie.uploadfile.core.exception.AccessDeniedException(raw)),
                403, "无权操作该上传任务", raw);
    }
}
