package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.dto.ApiResponse;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.security.SecurityUtil;
import cn.chenxinjie.pathfinder.service.BizException;
import cn.chenxinjie.pathfinder.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一错误码与业务提示（TSDD 9.3）。
 *
 * <p>X1：403（越权）统一写失败审计（success=0），type=FORBIDDEN，targetType=API；
 * 审计写入失败不掩盖原始错误，仅记日志。LogService 为可选注入，便于纯单测直接 new。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private LogService logService;

    @Autowired(required = false)
    public void setLogService(LogService logService) {
        this.logService = logService;
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e, HttpServletRequest request) {
        if (e.getStatus() == 403) {
            auditForbidden(SecurityUtil.currentOrNull(), e.getMessage(), request);
        }
        return ResponseEntity.status(e.getStatus())
                .body(ApiResponse.error(e.getStatus(), e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException e, HttpServletRequest request) {
        auditForbidden(SecurityUtil.currentOrNull(), "无权限执行该操作", request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(403, "无权限执行该操作"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error(400, msg));
    }

    /**
     * upload-file 组件类型化异常：按稳定状态码映射（400/404/409/507），
     * 不透传内部细节，仅记日志供排查。
     *
     * <p>rc.6 起 /upload 由组件 Servlet 承载，其错误体由组件自行写出（http.error-body），
     * 以下映射仅作为「若有组件异常逃逸到 MVC 路径」的防御性兜底。越权（FORBIDDEN）审计已迁至
     * {@link cn.chenxinjie.pathfinder.security.UploadAccessAuditListener} 的决策点，此处不再重复写审计。</p>
     */
    @ExceptionHandler(cn.chenxinjie.uploadfile.core.exception.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadAccessDenied(cn.chenxinjie.uploadfile.core.exception.AccessDeniedException e) {
        return uploadFileError(403, "无权操作该上传任务", e);
    }

    @ExceptionHandler(cn.chenxinjie.uploadfile.core.exception.UploadValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadValidation(cn.chenxinjie.uploadfile.core.exception.UploadValidationException e) {
        return uploadFileError(400, "上传参数不合法，请检查后重试", e);
    }

    @ExceptionHandler(cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleChecksum(cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException e) {
        return uploadFileError(400, "分片校验失败，请重传该分片", e);
    }

    @ExceptionHandler(cn.chenxinjie.uploadfile.core.exception.UploadTaskNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTaskNotFound(cn.chenxinjie.uploadfile.core.exception.UploadTaskNotFoundException e) {
        return uploadFileError(404, "上传任务不存在或已过期，请重新上传", e);
    }

    @ExceptionHandler(cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadMergeConflict(cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException e) {
        return uploadFileError(409, "文件正在上传/合并中，请稍后再试", e);
    }

    @ExceptionHandler(cn.chenxinjie.uploadfile.core.exception.QuotaExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleQuota(cn.chenxinjie.uploadfile.core.exception.QuotaExceededException e) {
        return uploadFileError(507, "存储配额不足，无法继续上传", e);
    }

    private ResponseEntity<ApiResponse<Void>> uploadFileError(int status, String message, Exception e) {
        log.warn("upload-file 组件错误 status={} detail={}", status, e.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.error(status, message));
    }

    /**
     * X1：403 越权统一留痕（success=0）。成功/业务失败由业务方在服务内显式记录，
     * 此处只兜底「被拦截的越权请求」；审计写入异常不掩盖原始响应。
     */
    private void auditForbidden(AuthUser user, String message, HttpServletRequest request) {
        if (logService == null) {
            return;
        }
        try {
            String method = request == null ? null : request.getMethod();
            String uri = request == null ? null : request.getRequestURI();
            logService.record(user, "FORBIDDEN", "API", method, uri, message, false);
        } catch (Exception auditError) {
            log.warn("越权失败审计写入失败 message={}", message, auditError);
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        // 500 脱敏（PLAN PF-903）：对外固定文案，异常细节只进服务端日志，避免泄露路径/类名等内部信息
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "系统内部错误，请稍后重试"));
    }
}
