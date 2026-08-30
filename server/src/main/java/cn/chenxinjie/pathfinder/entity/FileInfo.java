package cn.chenxinjie.pathfinder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文件元数据（核心表，含数据权限归属字段）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "file_info")
public class FileInfo extends BaseEntity {

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_md5", length = 64)
    private String fileMd5;

    @Column(name = "file_type", length = 32)
    private String fileType;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "space_type", nullable = false, length = 16)
    private String spaceType;

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "UPLOADING";

    @Column(name = "upload_identifier", length = 64)
    private String uploadIdentifier;

    @Column(name = "del_flag", nullable = false)
    private Integer delFlag = 0;

    @Column(name = "del_at")
    private LocalDateTime delAt;
}
