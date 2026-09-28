package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.dto.PageResult;
import cn.chenxinjie.pathfinder.entity.OperationLog;
import cn.chenxinjie.pathfinder.entity.User;
import cn.chenxinjie.pathfinder.repository.OperationLogRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.util.SqlLike;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作审计：记录、查询、归档（PRD F7 / TSDD 9.4）。
 */
@Service
public class LogService {

    private final OperationLogRepository operationLogRepository;
    private final PathProperties pathProperties;

    public LogService(OperationLogRepository operationLogRepository, PathProperties pathProperties) {
        this.operationLogRepository = operationLogRepository;
        this.pathProperties = pathProperties;
    }

    public void record(AuthUser operator, String type, String targetType, String targetId,
                       String targetName, String detail, boolean success) {
        record(operator, type, targetType, targetId, targetName, detail, success, null, null);
    }

    /**
     * 带客户端信息的审计（rc.8 AccessContext：method/uri/remoteAddr/userAgent）。
     * 供组件 Servlet 路径的越权审计复用（{@code UploadAccessAuditListener}）。
     */
    public void record(AuthUser operator, String type, String targetType, String targetId,
                       String targetName, String detail, boolean success, String ip, String userAgent) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operator == null ? null : operator.getId());
        log.setOperatorName(operator == null ? "anonymous" : operator.getUsername());
        log.setIp(truncate(ip, 64));
        log.setUserAgent(truncate(userAgent, 255));
        log.setOperationType(type);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setTargetName(targetName);
        log.setDetail(detail);
        log.setSuccess(success ? 1 : 0);
        operationLogRepository.save(log);
    }

    public void recordLogin(Long userId, String username, String ip, String ua, boolean success, String detail) {
        OperationLog log = new OperationLog();
        log.setOperatorId(userId);
        log.setOperatorName(username);
        log.setIp(ip);
        log.setUserAgent(ua);
        log.setOperationType("LOGIN");
        log.setDetail(detail);
        log.setSuccess(success ? 1 : 0);
        operationLogRepository.save(log);
    }

    public PageResult<OperationLog> page(String operatorName, String operationType, Integer success,
                                         LocalDateTime start, LocalDateTime end, int pageNum, int pageSize) {
        Specification<OperationLog> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (operatorName != null && !operatorName.isBlank()) {
                ps.add(cb.like(root.get("operatorName"), SqlLike.containsPattern(operatorName), '\\'));
            }
            if (operationType != null && !operationType.isBlank()) {
                ps.add(cb.equal(root.get("operationType"), operationType));
            }
            if (success != null) {
                ps.add(cb.equal(root.get("success"), success));
            }
            if (start != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (end != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<OperationLog> page = operationLogRepository.findAll(spec,
                PageRequest.of(pageNum - 1, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResult.of(page, pageNum, pageSize);
    }

    /**
     * 归档：将 12 个月前的日志导出 CSV 至 archive 目录后批量删除（G10）。
     */
    @Transactional
    public int archive(LocalDateTime before) {
        List<OperationLog> logs = operationLogRepository.findByCreatedAtBefore(before);
        if (logs.isEmpty()) {
            return 0;
        }
        try {
            Path dir = pathProperties.getStorage().archivePath();
            Files.createDirectories(dir);
            String file = "operation_log_" + before.toLocalDate() + ".csv";
            StringBuilder sb = new StringBuilder(
                    "id,operatorName,operationType,targetType,targetId,targetName,success,createdAt,detail\n");
            for (OperationLog l : logs) {
                sb.append(l.getId()).append(',').append(csv(l.getOperatorName())).append(',')
                  .append(csv(l.getOperationType())).append(',').append(csv(l.getTargetType())).append(',')
                  .append(csv(l.getTargetId())).append(',').append(csv(l.getTargetName())).append(',')
                  .append(l.getSuccess()).append(',').append(l.getCreatedAt()).append(',')
                  .append(csv(l.getDetail())).append('\n');
            }
            Files.writeString(dir.resolve(file), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("审计日志归档失败", e);
        }
        operationLogRepository.deleteAll(logs);
        return logs.size();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private String csv(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
