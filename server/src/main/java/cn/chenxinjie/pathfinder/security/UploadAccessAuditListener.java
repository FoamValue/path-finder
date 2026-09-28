package cn.chenxinjie.pathfinder.security;

import cn.chenxinjie.pathfinder.service.LogService;
import cn.chenxinjie.uploadfile.core.security.AccessContext;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * X1：上传/下载越权审计（组件 {@link AccessControlListener} SPI）。
 *
 * <p>迁移到组件官方 Servlet 后，越权由组件在决策点直接返回 403（{@link cn.chenxinjie.uploadfile.core.exception.AccessDeniedException}），
 * 不再经过 {@code @RestControllerAdvice}，因此把 FORBIDDEN 失败审计（success=0）从
 * {@code GlobalExceptionHandler} 迁到本监听器——上传与下载两条路径都能留痕，且不会重复记录。
 * 组件在抛出拒绝异常前通知监听器，故 deny 事件必然早于响应写出。</p>
 *
 * <p>rc.8：覆写 6 参 {@link #onDecision(AccessContext, String, String, AccessDecision, long)}，
 * 把组件携带的请求上下文（method/URI/客户端 IP/User-Agent）写入审计行；5 参重载（组件兼容桥接）
 * 以 {@link AccessContext#EMPTY} 委托到同一实现。</p>
 */
@Component
public class UploadAccessAuditListener implements AccessControlListener {

    private static final Logger log = LoggerFactory.getLogger(UploadAccessAuditListener.class);

    private final LogService logService;

    public UploadAccessAuditListener(LogService logService) {
        this.logService = logService;
    }

    @Override
    public void onDecision(String identifier, String action, AccessDecision decision, long elapsedNanos) {
        onDecision(AccessContext.EMPTY, identifier, action, decision, elapsedNanos);
    }

    @Override
    public void onDecision(AccessContext context, String identifier, String action,
                           AccessDecision decision, long elapsedNanos) {
        if (decision.allowed()) {
            return;
        }
        AccessContext ctx = context == null ? AccessContext.EMPTY : context;
        try {
            String path = ctx.getUri() != null
                    ? ctx.getUri()
                    : (AccessControl.ACTION_DOWNLOAD.equals(action) ? "/download" : "/upload?action=" + action);
            logService.record(SecurityUtil.currentOrNull(), "FORBIDDEN", "API",
                    ctx.getMethod(), path,
                    decision.reason() == null ? "无权操作该上传任务" : decision.reason(), false,
                    ctx.getRemoteAddr(), ctx.getUserAgent());
        } catch (Exception e) {
            log.warn("上传越权失败审计写入失败 action={} identifier={}", action, identifier, e);
        }
    }
}
