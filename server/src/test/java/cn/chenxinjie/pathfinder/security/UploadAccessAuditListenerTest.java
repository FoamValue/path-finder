package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.service.LogService;
import cn.chenxinjie.uploadfile.core.security.AccessContext;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * X1 越权审计迁移验证（rc.6 AccessControlListener；rc.8 审计上下文）：deny 落 FORBIDDEN 失败审计
 * （success=0，含 method/URI/IP/UA），allow 不落审计，审计异常不抛出影响请求。
 */
class UploadAccessAuditListenerTest {

    private LogService logService;
    private UploadAccessAuditListener listener;

    @BeforeEach
    void setUp() {
        logService = mock(LogService.class);
        listener = new UploadAccessAuditListener(logService);
        AuthUser user = new AuthUser(9L, "u9", "用户9", "USER", 1L, 0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deny_withoutContext_writesForbiddenAudit() {
        listener.onDecision("id-1", AccessControl.ACTION_MERGE,
                AccessDecision.deny(403, "owner mismatch"), 1000L);

        verify(logService).record(any(AuthUser.class), eq("FORBIDDEN"), eq("API"),
                isNull(), eq("/upload?action=merge"), eq("owner mismatch"), eq(false), isNull(), isNull());
    }

    @Test
    void deny_withContext_recordsRequestMetadata() {
        AccessContext context = new AccessContext("POST", "/upload", "10.0.0.8", "curl/8.0");
        listener.onDecision(context, "id-1", AccessControl.ACTION_UPLOAD,
                AccessDecision.deny(403, "no permission"), 1000L);

        verify(logService).record(any(AuthUser.class), eq("FORBIDDEN"), eq("API"),
                eq("POST"), eq("/upload"), eq("no permission"), eq(false), eq("10.0.0.8"), eq("curl/8.0"));
    }

    @Test
    void allow_doesNotAudit() {
        listener.onDecision("id-1", AccessControl.ACTION_PROGRESS,
                AccessDecision.allow(), 1000L);

        verify(logService, never()).record(any(), any(), any(), any(), any(), any(), eq(false), any(), any());
    }

    @Test
    void auditFailure_isSwallowed() {
        doThrow(new RuntimeException("db down")).when(logService)
                .record(any(), any(), any(), any(), any(), any(), eq(false), any(), any());

        listener.onDecision("id-1", AccessControl.ACTION_CANCEL,
                AccessDecision.deny(403, "denied"), 1000L);
    }
}
