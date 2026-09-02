package cn.chenxinjie.pathfinder.service;

import cn.chenxinjie.pathfinder.config.PathProperties;
import cn.chenxinjie.pathfinder.entity.OperationLog;
import cn.chenxinjie.pathfinder.repository.OperationLogRepository;
import cn.chenxinjie.pathfinder.security.AuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作审计服务（PRD F7 / TSDD 9.4，对应 TC-AUDIT-001/002/003/009）：
 * record/recordLogin 落库字段完整性、按 12 个月归档导出 CSV 并清理。
 */
class LogServiceTest {

    @TempDir
    Path tempDir;

    private OperationLogRepository logRepository;
    private LogService logService;

    @BeforeEach
    void setUp() {
        logRepository = mock(OperationLogRepository.class);
        PathProperties props = new PathProperties();
        props.getStorage().setRoot(tempDir.toString());
        logService = new LogService(logRepository, props);
    }

    @Test
    void record_persistsOperatorAndOperationMeta() {
        AuthUser op = new AuthUser(1L, "admin", "系统管理员", "ADMIN", 1L, 0);
        logService.record(op, "RENAME", "FILE", "42", "a.txt", "重命名：a → b", true);

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(logRepository).save(captor.capture());
        OperationLog saved = captor.getValue();
        assertEquals(1L, saved.getOperatorId());
        assertEquals("admin", saved.getOperatorName());
        assertEquals("RENAME", saved.getOperationType());
        assertEquals("FILE", saved.getTargetType());
        assertEquals("42", saved.getTargetId());
        assertEquals("a.txt", saved.getTargetName());
        assertEquals(1, saved.getSuccess());
    }

    @Test
    void recordLogin_persistsIpUaAndFailure() {
        logService.recordLogin(9L, "zhangsan", "10.0.0.1", "Mozilla/5.0", false, "用户名或密码错误");

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(logRepository).save(captor.capture());
        OperationLog saved = captor.getValue();
        assertEquals("LOGIN", saved.getOperationType());
        assertEquals("10.0.0.1", saved.getIp());
        assertEquals("Mozilla/5.0", saved.getUserAgent());
        assertEquals(0, saved.getSuccess(), "失败登录必须 success=0 留痕");
    }

    @Test
    void archive_exportsCsvWithEscapingAndDeletes() throws Exception {
        OperationLog log = log(1L, "admin", "DOWNLOAD", "FILE", "7", "x.pdf", "下载", "he said \"hi\", ok",
                LocalDateTime.of(2024, 6, 1, 10, 0));
        when(logRepository.findByCreatedAtBefore(any())).thenReturn(List.of(log));

        int n = logService.archive(LocalDateTime.of(2025, 1, 1, 0, 0));

        assertEquals(1, n);
        verify(logRepository).deleteAll(List.of(log));
        Path csv = tempDir.resolve("archive").resolve("operation_log_2025-01-01.csv");
        assertTrue(Files.exists(csv), "归档 CSV 应生成");
        String content = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("id,operatorName,operationType,targetType,targetId,targetName,success,createdAt,detail\n"));
        assertTrue(content.contains("DOWNLOAD"));
        assertTrue(content.contains("\"he said \"\"hi\"\", ok\""), "detail 含逗号/引号时按 RFC4180 转义");
    }

    @Test
    void archive_noExpiredLogs_returnsZeroAndNoFile() {
        when(logRepository.findByCreatedAtBefore(any())).thenReturn(List.of());

        int n = logService.archive(LocalDateTime.now());

        assertEquals(0, n);
        verify(logRepository, never()).deleteAll(any());
        assertTrue(!Files.exists(tempDir.resolve("archive")), "无数据时不生成归档目录");
    }

    private OperationLog log(long id, String operator, String type, String targetType, String targetId,
                             String targetName, String detail, String quoteDetail, LocalDateTime createdAt) {
        OperationLog l = new OperationLog();
        l.setId(id);
        l.setOperatorName(operator);
        l.setOperationType(type);
        l.setTargetType(targetType);
        l.setTargetId(targetId);
        l.setTargetName(targetName);
        l.setDetail(quoteDetail);
        l.setSuccess(1);
        l.setCreatedAt(createdAt);
        return l;
    }
}
