package cn.chenxinjie.pathfinder.config;

import cn.chenxinjie.pathfinder.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 操作日志归档调度（每日 3:00）：12 个月前的日志导出 CSV 后清理（G10）。
 */
@Component
public class LogArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogArchiveScheduler.class);

    private final LogService logService;

    public LogArchiveScheduler(LogService logService) {
        this.logService = logService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void archive() {
        try {
            int n = logService.archive(LocalDateTime.now().minusMonths(12));
            if (n > 0) {
                log.info("审计日志归档：已归档 {} 条", n);
            }
        } catch (Exception e) {
            log.error("审计日志归档失败", e);
        }
    }
}
