package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.service.BizException;
import cn.chenxinjie.pathfinder.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * X1 修复验证：403（越权）在全局异常出口统一写失败审计（success=0，type=FORBIDDEN），
 * 且审计写入失败不掩盖原始响应。
 */
class GlobalExceptionAuditTest {

    private LogService logService;
    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        logService = mock(LogService.class);
        handler = new GlobalExceptionHandler();
        handler.setLogService(logService);
        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/api/file/2/owner");
    }

    @Test
    void bizForbidden_writesFailureAudit() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBiz(
                BizException.forbidden("无权操作该文件"), request);
        assertEquals(403, resp.getStatusCode().value());
        verify(logService).record(isNull(), eq("FORBIDDEN"), eq("API"),
                eq("PUT"), eq("/api/file/2/owner"), eq("无权操作该文件"), eq(false));
    }

    @Test
    void non403Biz_notAudited() {
        handler.handleBiz(BizException.badRequest("参数不合法"), request);
        handler.handleBiz(BizException.notFound("文件不存在"), request);
        verify(logService, never()).record(org.mockito.ArgumentMatchers.any(),
                eq("FORBIDDEN"), eq("API"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), eq(false));
    }

    @Test
    void springAccessDenied_writesFailureAudit() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleDenied(
                new AccessDeniedException("denied"), request);
        assertEquals(403, resp.getStatusCode().value());
        verify(logService).record(isNull(), eq("FORBIDDEN"), eq("API"),
                eq("PUT"), eq("/api/file/2/owner"), eq("无权限执行该操作"), eq(false));
    }

    @Test
    void auditFailure_doesNotMaskOriginal403() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(logService)
                .record(org.mockito.ArgumentMatchers.any(), eq("FORBIDDEN"), eq("API"),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), eq(false));
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBiz(
                BizException.forbidden("无权操作该文件"), request);
        assertNotNull(resp.getBody());
        assertEquals(403, resp.getStatusCode().value());
        assertEquals("无权操作该文件", resp.getBody().getMessage());
    }

    @Test
    void withoutLogService_noThrow() {
        GlobalExceptionHandler bare = new GlobalExceptionHandler();
        ResponseEntity<ApiResponse<Void>> resp = bare.handleBiz(BizException.forbidden("x"), request);
        assertEquals(403, resp.getStatusCode().value());
    }
}
