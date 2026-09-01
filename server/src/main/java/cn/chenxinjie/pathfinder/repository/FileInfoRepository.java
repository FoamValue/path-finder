package cn.chenxinjie.pathfinder.repository;

import cn.chenxinjie.pathfinder.entity.FileInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

public interface FileInfoRepository extends JpaRepository<FileInfo, Long>, JpaSpecificationExecutor<FileInfo> {

    long countByDelFlagAndStatusAndCreatedAtBefore(Integer delFlag, String status, LocalDateTime before);

    List<FileInfo> findByDelFlagAndStatusAndCreatedAtBefore(Integer delFlag, String status, LocalDateTime before);

    long countByDelFlagAndDeptId(Integer delFlag, Long deptId);

    long countByDelFlagAndOwnerId(Integer delFlag, Long ownerId);

    Page<FileInfo> findByDelFlagAndStatus(Integer delFlag, String status, Pageable pageable);

    List<FileInfo> findByDelFlagAndStatusAndFileMd5(Integer delFlag, String status, String fileMd5);
}
