package com.pathfinder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 回收站记录（软删除，保留 30 天）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "file_recycle_bin")
public class FileRecycleBin extends BaseEntity {

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "deleted_by", nullable = false)
    private Long deletedBy;

    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;
}
