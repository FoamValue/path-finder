package cn.chenxinjie.pathfinder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 操作审计日志。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "operation_log")
public class OperationLog extends BaseEntity {

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 64)
    private String operatorName;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "operation_type", length = 32)
    private String operationType;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "target_name", length = 255)
    private String targetName;

    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "success", nullable = false)
    private Integer success = 1;
}
